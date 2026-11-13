package de.kiwi.backgroundTask.application

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.backgroundTask.BackgroundTaskError
import de.kiwi.api.backgroundTask.BackgroundTaskProgress
import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityNotFound
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.backgroundTask.domain.BackgroundTask
import de.kiwi.backgroundTask.domain.BackgroundTaskData
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

/**
 * Transactional state changes of the task lifecycle executed by the workers.
 * Each method runs in its own transaction, loads and write-locks the task, and publishes the recorded domain events
 * on behalf of the given user.
 *
 * Changes by an execution are only applied while it *owns* the task, i.e. the task is running in the execution's
 * [attempt][BackgroundTask.attempt]: An execution superseded by a resumed attempt (or a task canceled concurrently)
 * doesn't change the task anymore.
 *
 * Scratchpad retention: The scratchpad is deleted when a task is completed successfully or canceled, and retained when
 * it fails or waits for its retry, so it can be resumed or retried.
 */
class BackgroundTaskLifecycleManager(
    private val taskRepository: BackgroundTaskRepository,
    private val dataRepository: BackgroundTaskDataRepository,
    private val transactions: TransactionService,
    private val clockProvider: ClockProvider,
    private val objectMapper: ObjectMapper,
    private val eventBus: EventBus,
) {
    /**
     * Result of [startTask]: The task's status afterward, its current attempt, its progress (retained from previous
     * attempts, if the task is retried or resumed), and whether it has been canceled while pending
     */
    data class StartResult(
        /** Status of the task afterward: [Running][BackgroundTaskStatus.Running] if started by the call */
        val status: BackgroundTaskStatus,
        /** Current execution attempt of the task */
        val attempt: Int,
        /** Progress of the task, retained from previous attempts if the task is retried or resumed */
        val progress: BackgroundTaskProgress = BackgroundTaskProgress.NONE,
        /**
         * `true` if the task has been canceled while it was pending (e.g. waiting for its retry),
         * `false` if it is not canceled, or has been canceled while running
         */
        val isCanceledWhilePending: Boolean = false,
    )

    /**
     * Result of [syncTask]: The task's status and current attempt, whether the execution still owns the task, and whether
     * the progress changed
     */
    data class SyncResult(
        /** Current status of the task */
        val status: BackgroundTaskStatus,
        /** Current execution attempt of the task */
        val attempt: Int,
        /** `true` if the syncing execution still owns the task (running in its attempt) */
        val isOwned: Boolean,
        /** `true` if the synced progress differs from the stored progress */
        val progressChanged: Boolean,
    )

    /**
     * Result of closing a task: Its final [status], whether this call closed it ([isClosedByCall]), whether the given
     * final progress changed ([progressChanged]), and whether the execution had been superseded ([isSuperseded])
     */
    data class CloseResult(
        /** Status of the task afterward */
        val status: BackgroundTaskStatus,
        /**
         * `true` if the task has been closed by this call (not concurrently before), or its retry has been scheduled by
         * this call ([scheduleRetry])
         */
        val isClosedByCall: Boolean,
        /** `true` if the given final progress differs from the stored progress */
        val progressChanged: Boolean = false,
        /** `true` if the closing execution has been superseded by another attempt, the task is unchanged */
        val isSuperseded: Boolean = false,
        /** When the retry of the task is due, if it has been scheduled by this call ([scheduleRetry]) */
        val retryOn: LocalDateTime? = null,
    )

    /** Thrown when an execution changes a task it doesn't own anymore, with the task's [status] and [attempt] */
    class TaskNotOwned(
        taskId: BackgroundTaskId,
        executionAttempt: Int,
        /** Current status of the task */
        val status: BackgroundTaskStatus,
        /** Current execution attempt of the task */
        val attempt: Int,
    ) : IllegalStateException(
        "BackgroundTask #$taskId is not running in attempt $executionAttempt anymore ($status in attempt $attempt)",
    )

    /** Lookup the task's entity ID by its UUID handle */
    fun findTaskId(taskHandle: UUID): BackgroundTaskId? {
        return transactions.read {
            taskRepository.findIdByTaskHandle(taskHandle)
        }
    }

    /** Start the task as a new attempt of the given worker, if it is ready (not canceled or running already) */
    fun startTask(
        taskId: BackgroundTaskId,
        workerName: String,
        leaseDuration: Duration,
        user: UserDescriptor,
    ): StartResult {
        return transactionWithTask(taskId, user) { task, record ->
            val clock = clockProvider.clock()
            if (task.status.isPending) {
                record(task.started(workerName, leaseDuration, clock))
            }
            StartResult(
                status = task.status,
                attempt = task.attempt,
                progress = task.getProgress(objectMapper),
                isCanceledWhilePending = task.isCanceledWhilePending(),
            )
        }
    }

    /**
     * Sync a running execution: Store the given [progress] (if any), renew the lease, and return the task's status,
     * if the execution still owns the task
     */
    fun syncTask(
        taskId: BackgroundTaskId,
        attempt: Int,
        progress: BackgroundTaskProgress?,
        leaseDuration: Duration,
        user: UserDescriptor,
    ): SyncResult {
        return transactionWithTask(taskId, user) { task, _ ->
            val clock = clockProvider.clock()
            if (!task.isOwnedBy(attempt)) {
                SyncResult(task.status, task.attempt, isOwned = false, progressChanged = false)
            } else {
                val progressChanged = progress != null && task.updateProgress(progress, objectMapper)
                task.renewLease(leaseDuration, clock)
                SyncResult(task.status, task.attempt, isOwned = true, progressChanged = progressChanged)
            }
        }
    }

    /** Complete the task with the handler's [resultValue], unless it has been closed or superseded in the meantime */
    fun completeTask(
        taskId: BackgroundTaskId,
        attempt: Int,
        resultValue: JsonNode,
        events: List<DomainEvent>,
        progress: BackgroundTaskProgress?,
        user: UserDescriptor,
    ): CloseResult = closeIfOwned(taskId, attempt, user) { task, record ->
        record(events)
        val clock = clockProvider.clock()
        val progressChanged = progress != null && task.updateProgress(progress, objectMapper)
        record(task.completed(resultValue, objectMapper, clock))
        dataRepository.deleteAllByTaskId(taskId)
        CloseResult(task.status, isClosedByCall = true, progressChanged = progressChanged)
    }

    /** Fail the task with the given [exception], unless it has been closed or superseded in the meantime */
    fun failTask(
        taskId: BackgroundTaskId,
        attempt: Int?,
        exception: Throwable,
        events: List<DomainEvent>,
        progress: BackgroundTaskProgress?,
        user: UserDescriptor,
    ): CloseResult = closeIfOwned(taskId, attempt, user) { task, record ->
        record(events)
        val clock = clockProvider.clock()
        val progressChanged = progress != null && task.status.isRunning && task.updateProgress(progress, objectMapper)
        val error = BackgroundTaskError.of(exception)
        record(task.failed(exception, error, objectMapper, clock))
        // The scratchpad is retained, so the task can be resumed
        CloseResult(task.status, isClosedByCall = true, progressChanged = progressChanged)
    }

    /**
     * Schedule the retry of the failed task after the given [retryDelay], as requested by its handler, unless it has
     * been closed or superseded in the meantime: The task waits as [RetryScheduled][BackgroundTaskStatus.RetryScheduled]
     * for its next attempt, with its scratchpad retained. The caller enqueues the retry once this call returned.
     */
    fun scheduleRetry(
        taskId: BackgroundTaskId,
        attempt: Int,
        exception: Throwable,
        retryDelay: Duration,
        events: List<DomainEvent>,
        progress: BackgroundTaskProgress?,
        user: UserDescriptor,
    ): CloseResult {
        return closeIfOwned(taskId, attempt, user) { task, record ->
            record(events)
            val clock = clockProvider.clock()
            val progressChanged = progress != null && task.updateProgress(progress, objectMapper)
            val error = BackgroundTaskError.of(exception)
            val retryOn = record(task.retryScheduled(exception, error, retryDelay, objectMapper, clock))
            CloseResult(task.status, isClosedByCall = true, progressChanged = progressChanged, retryOn = retryOn)
        }
    }

    /**
     * Cancel the task, unless it has been closed or superseded in the meantime. A task canceled before (e.g. by the
     * service) is reported as closed by this call, so the worker broadcasts the cancellation after closing it.
     */
    fun cancelTask(
        taskId: BackgroundTaskId,
        attempt: Int?,
        events: List<DomainEvent>,
        progress: BackgroundTaskProgress?,
        user: UserDescriptor,
    ): CloseResult = transactionWithTask(taskId, user) { task, record ->
        val clock = clockProvider.clock()
        if (task.isSupersededFor(attempt)) {
            CloseResult(task.status, isClosedByCall = false, isSuperseded = true)
        } else {
            record(events)
            val progressChanged = progress != null &&
                    task.status.isRunning &&
                    task.updateProgress(progress, objectMapper)

            if (!task.isClosed()) record(task.canceled(clock))
            if (task.isCanceled()) dataRepository.deleteAllByTaskId(taskId)
            CloseResult(
                status = task.status,
                isClosedByCall = task.isCanceled(),
                progressChanged = progressChanged,
            )
        }
    }

    /**
     * Fail the task of the given handle with the given [exception], if it hasn't been started (i.e. it is
     * [pending][BackgroundTaskStatus.isPending], also if it waits for its retry), e.g. if it has been rejected by the
     * worker that received it, or its retry couldn't be enqueued.
     * The task's events are published on behalf of the user that launched it.
     * @return Result of closing the task, `null` if the task doesn't exist
     */
    fun failUnstartedTask(taskHandle: UUID, exception: Throwable): CloseResult? {
        return transactions.write {
            val clock = clockProvider.clock()
            val task = taskRepository.findWriteLockedByHandle(taskHandle) ?: return@write null
            if (!task.status.isPending) {
                return@write CloseResult(task.status, isClosedByCall = false)
            }
            val error = BackgroundTaskError.of(exception)
            val result = task.failed(exception, error, objectMapper, clock)
            eventBus.publish(result.events, task.toTaskUser(objectMapper))
            CloseResult(task.status, isClosedByCall = true)
        }
    }

    /** Load the scratchpad of the task: The values as JSON (`null` for a `null` value) by key */
    fun loadScratchpad(taskId: BackgroundTaskId): Map<String, String?> {
        return transactions.read {
            val entries = dataRepository.findAllByTaskId(taskId)
            entries.associate { it.dataKey to it.valueJson }
        }
    }

    /** Store a scratchpad value of the task, if the execution of the given [attempt] still owns it */
    fun putScratchpadValue(taskId: BackgroundTaskId, attempt: Int, key: String, valueJson: String?) {
        transactions.writeNew {
            val task = requireOwnedTask(taskId, attempt)
            upsertScratchpadValue(task, key, valueJson)
        }
    }

    /**
     * Complete a stage of the task, if the execution of the given [attempt] still owns it: Stores the stage's result
     * checkpoint as a scratchpad value (unless [resultKey] is `null`), and publishes the [events] recorded by the stage
     * on behalf of the given [user], in one transaction (so the events of a completed stage are published exactly once)
     */
    fun completeStage(
        taskId: BackgroundTaskId,
        attempt: Int,
        resultKey: String?,
        resultJson: String?,
        events: List<DomainEvent>,
        user: UserDescriptor,
    ) {
        if (resultKey != null || events.isNotEmpty()) {
            transactions.writeNew {
                val task = requireOwnedTask(taskId, attempt)
                if (resultKey != null) upsertScratchpadValue(task, resultKey, resultJson)
                if (events.isNotEmpty()) eventBus.publish(events, user)
            }
        }
    }

    private fun upsertScratchpadValue(task: BackgroundTask, key: String, valueJson: String?) {
        val clock = clockProvider.clock()
        dataRepository.findByTaskIdAndKey(task.id, key)
            ?.update(valueJson, clock)
            ?: dataRepository.save(
                BackgroundTaskData.create(
                    task = task,
                    dataKey = key,
                    valueJson = valueJson,
                    clock = clock,
                ),
            )
    }

    /** Remove a scratchpad value of the task, if the execution of the given [attempt] still owns it */
    fun removeScratchpadValue(taskId: BackgroundTaskId, attempt: Int, key: String) {
        transactions.writeNew {
            requireOwnedTask(taskId, attempt)
            dataRepository.deleteByTaskIdAndKey(taskId, key)
        }
    }

    private fun requireOwnedTask(taskId: BackgroundTaskId, attempt: Int): BackgroundTask {
        // Read-locked: A locking read sees the latest committed version of the task, which is required by databases
        // with snapshot isolation (e.g. MariaDB), as the task is updated concurrently by the execution's supervisor
        val task = taskRepository.findReadLockedById(taskId) ?: throw EntityNotFound<BackgroundTask>(taskId)
        if (!task.isOwnedBy(attempt)) throw TaskNotOwned(taskId, attempt, task.status, task.attempt)
        return task
    }

    private inline fun closeIfOwned(
        taskId: BackgroundTaskId,
        attempt: Int?,
        user: UserDescriptor,
        crossinline doClose: (BackgroundTask, DomainResult.Recorder) -> CloseResult,
    ): CloseResult {
        return transactionWithTask(taskId, user) { task, record ->
            when {
                // Not owned by this attempt -> Has been superseded
                task.isSupersededFor(attempt) -> {
                    CloseResult(task.status, isClosedByCall = false, isSuperseded = true)
                }
                // Has already been closed -> Not closed by our call
                task.isClosed() -> {
                    CloseResult(task.status, isClosedByCall = false)
                }
                // Owned and not closed -> Close now, evaluate close result
                else -> {
                    doClose(task, record)
                }
            }
        }
    }

    private inline fun <T> transactionWithTask(
        taskId: BackgroundTaskId,
        user: UserDescriptor,
        crossinline block: (BackgroundTask, DomainResult.Recorder) -> T,
    ): T {
        return transactions.write {
            val task = taskRepository.findWriteLockedById(taskId) ?: throw EntityNotFound<BackgroundTask>(taskId)
            val result = DomainResult { record -> block(task, record) }
            if (result.events.isNotEmpty()) eventBus.publish(result.events, user)
            result.value
        }
    }

    private fun BackgroundTask.isOwnedBy(attempt: Int): Boolean {
        return status.isRunning && this.attempt == attempt
    }

    /**
     * `true` if the execution of the given [attempt] (`null` for a task that wasn't started) has been superseded:
     * Another attempt has been started, or the task has been resumed and waits for its next attempt. Unlike
     * [isOwnedBy], a task closed in the execution's attempt (e.g. canceled by the service) isn't superseded.
     */
    private fun BackgroundTask.isSupersededFor(attempt: Int?): Boolean {
        return attempt != null && (this.attempt != attempt || status.isPending)
    }
}
