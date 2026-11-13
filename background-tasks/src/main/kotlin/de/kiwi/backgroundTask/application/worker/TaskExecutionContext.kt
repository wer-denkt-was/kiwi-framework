package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.BackgroundTaskLifecycleManager
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.broadcastSafely
import de.kiwi.backgroundTask.domain.BackgroundTaskId

private val log = slf4j<TaskExecutionContext>()

/**
 * [BackgroundTaskContext] of a task execution:
 * - The [status] is provided by the [control] of the execution.
 * - Progress is synced by a [TaskSupervisor] (throttled, together with renewing the lease); the supervisor also
 *   detects if the task was canceled, or the execution superseded, and requests the execution to stop accordingly.
 * - Notifications and custom messages are broadcast immediately.
 * - The [scratchpad] writes through to the [BackgroundTaskLifecycleManager], as long as the execution owns the task.
 * - [Stages][stages] store their checkpoint and publish their events when completed, in one transaction.
 *
 * @param initialProgress Progress of the task when the execution started (retained from a previous attempt)
 */
internal class TaskExecutionContext(
    val taskId: BackgroundTaskId,
    override val attempt: Int,
    private val initialProgress: BackgroundTaskProgress,
    private val descriptor: BackgroundTaskDescriptor,
    override val user: BackgroundTaskUserDescriptor,
    private val control: TaskExecutionControl,
    private val env: TaskExecutionEnvironment,
) : BackgroundTaskContext {

    override val taskHandle get() = descriptor.taskHandle
    override val request get() = descriptor.request
    override val status get() = control.status

    private val executionScratchpad = TaskExecutionScratchpad(env.objectMapper, ScratchpadStoreAdapter())

    override val scratchpad: BackgroundTaskScratchpad
        get() = executionScratchpad

    /**
     * `true` once the execution has been closed, then messages and progress are discarded.
     * Volatile field, as it may be mutated in a concurrent environment.
     */
    @Volatile
    private var closed = false

    /**
     * Supervisor assigned to this task, running in a background thread alongside task execution.
     */
    private val supervisor = TaskSupervisor(
        name = "task-supervisor-$taskId",
        progressInterval = env.progressInterval,
        leaseRenewalInterval = env.leaseRenewalInterval,
        sync = ::sync,
    )

    override fun checkActive() {
        val currentStatus = status
        if (!currentStatus.isActive || Thread.currentThread().isInterrupted) {
            // Don't clear the interrupted-flag (will be cleared by the execution)
            throw InterruptedException("BackgroundTask #$taskId is not active anymore: $currentStatus")
        }
    }

    override fun reportProgress(progress: BackgroundTaskProgress) {
        if (!closed) {
            supervisor.reportProgress(progress)
        }
    }

    override fun sendNotification(message: String, arguments: Map<String, Any?>) {
        broadcastUserMessage(BackgroundTaskMessage.Notification(taskHandle, message, arguments))
    }

    override fun sendMessage(messageType: String, details: Any?) {
        broadcastUserMessage(BackgroundTaskMessage.Custom(taskHandle, messageType, details))
    }

    override fun <T> stages(declaration: BackgroundTaskStages.Declaration<T>): DomainResult<T> {
        // Instance managing task stages declaration/execution
        val stages = BackgroundTaskStagesRunner(
            context = this,
            initialProgress = initialProgress,
            completion = ::completeStage,
        )

        // Run task stages, return result (No events, as the events have been published with the stages already)
        val value = stages.runStages(declaration)
        return DomainResult.of(value)
    }

    /** Start supervising the running execution */
    fun startSupervision() = supervisor.start()

    /**
     * Close the context: Stops the supervision, discards messages sent afterward,
     * and returns the latest progress that hasn't been synced yet
     */
    fun close(): BackgroundTaskProgress? {
        closed = true
        return supervisor.close()
    }

    private fun broadcastUserMessage(message: BackgroundTaskMessage) {
        if (closed) {
            log.warn("BackgroundTask #{} [{}] has been closed, discarded message: {}", taskId, taskHandle, message)
        } else {
            env.messaging.broadcastSafely(message)
        }
    }

    /** Sync of the [supervisor]: Store the progress and renew the lease, or stop the execution if not owned anymore */
    private fun sync(progress: BackgroundTaskProgress?) {
        val result = env.lifecycle.syncTask(
            taskId = taskId,
            attempt = attempt,
            progress = progress,
            leaseDuration = env.leaseDuration,
            user = user,
        )
        if (!result.isOwned) {
            onTaskNotOwned(result.status, result.attempt)
        } else if (result.progressChanged && progress != null) {
            env.messaging.broadcastSafely(BackgroundTaskMessage.Progress(taskHandle, progress))
        }
    }

    /** The execution doesn't own the task anymore: It has been canceled, or superseded by a resumed attempt */
    private fun onTaskNotOwned(taskStatus: BackgroundTaskStatus, taskAttempt: Int) {
        if (taskStatus == BackgroundTaskStatus.Canceled && taskAttempt == attempt) {
            control.requestCancel()
        } else {
            log.warn(
                "BackgroundTask #{} [{}] is {} in attempt {}, aborting attempt {} of worker [{}]",
                taskId, taskHandle, taskStatus, taskAttempt, attempt, env.workerName,
            )
            control.requestAbort(TaskExecutionControl.AbortReason.Superseded)
        }
    }

    /** Completion of a stage: Store its result checkpoint, and publish its events, if the execution owns the task */
    private fun completeStage(
        stageName: String,
        resultKey: String?,
        resultValue: Any?,
        events: List<DomainEvent>,
    ) = ifOwned {
        log.debug("BackgroundTask #{}: Completing stage [{}]", taskId, stageName)
        if (resultKey == null) {
            env.lifecycle.completeStage(
                taskId = taskId,
                attempt = attempt,
                resultKey = null,
                resultJson = null,
                events = events,
                user = user,
            )
        } else {
            executionScratchpad.putWith(resultKey, resultValue) { resultJson ->
                env.lifecycle.completeStage(
                    taskId = taskId,
                    attempt = attempt,
                    resultKey = resultKey,
                    resultJson = resultJson,
                    events = events,
                    user = user,
                )
            }
        }
    }

    /** Write to the [BackgroundTaskLifecycleManager], stopping the execution if it doesn't own the task anymore */
    private inline fun ifOwned(write: () -> Unit) {
        try {
            write()
        } catch (ex: BackgroundTaskLifecycleManager.TaskNotOwned) {
            onTaskNotOwned(ex.status, ex.attempt)
            throw ex
        }
    }

    /** Adapter between [TaskExecutionScratchpad.PersistentStore] and [BackgroundTaskLifecycleManager] */
    private inner class ScratchpadStoreAdapter : TaskExecutionScratchpad.PersistentStore {
        override fun load() = env.lifecycle.loadScratchpad(taskId)

        override fun put(key: String, valueJson: String?) = ifOwned {
            env.lifecycle.putScratchpadValue(taskId, attempt, key, valueJson)
        }

        override fun remove(key: String) = ifOwned {
            env.lifecycle.removeScratchpadValue(taskId, attempt, key)
        }
    }
}
