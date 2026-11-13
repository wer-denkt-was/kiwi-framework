package de.kiwi.backgroundTask.application

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.domain.BackgroundTask
import org.springframework.core.convert.ConversionService
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.ObjectMapper
import java.util.*

private val log = slf4j<BackgroundTaskServiceBean>()

/**
 * Default implementation of [BackgroundTaskService], persisting tasks and enqueueing them in the [BackgroundTaskQueue].
 *
 * Launching a task is a dual write (database and queue):
 * The task is persisted in the caller's transaction (or a new one), the queue's availability is checked before the
 * commit, and the task is enqueued after the commit.
 * If enqueueing fails, the task is marked as failed in a compensating transaction.
 *
 * Failed tasks, pending tasks (e.g. their launch has been lost), tasks waiting for their retry, and running tasks whose
 * worker's lease expired, can be [resumed][resume] (e.g. by an administrator via the `backgroundTasks` management
 * endpoint): They are enqueued again for a new execution attempt.
 */
class BackgroundTaskServiceBean(
    private val taskRepository: BackgroundTaskRepository,
    private val dataRepository: BackgroundTaskDataRepository,
    private val taskQueue: BackgroundTaskQueue,
    private val conversions: ConversionService,
    private val transactions: TransactionService,
    private val clockProvider: ClockProvider,
    private val objectMapper: ObjectMapper,
    private val eventBus: EventBus,
) : BackgroundTaskService {

    override fun launch(request: BackgroundTaskRequest, user: UserDescriptor): BackgroundTaskDescriptor {
        // A read-only caller transaction would be joined, but the task would never be persisted (or not reliably)
        check(!isReadOnlyTransactionActive()) {
            "BackgroundTask can't be launched in a read-only transaction: ${request.javaClass.name}"
        }

        // Fail early for unknown categories, as the task could never be enqueued
        val category = request.category
        if (!taskQueue.isKnownCategory(category)) {
            throw BackgroundTaskQueue.UnknownTaskCategory(category)
        }

        val clock = clockProvider.clock()
        val taskUser = convertTaskUserDescriptor(user)

        // Join the caller's transaction: The task is only enqueued if the caller commits
        return transactionWithEvents(user, requireNew = false) { record ->
            val task = taskRepository.save(
                record(
                    BackgroundTask.create(
                        taskRequest = request,
                        taskUser = taskUser,
                        objectMapper = objectMapper,
                        clock = clock,
                    ),
                ),
            )

            val taskDescriptor = BackgroundTaskDescriptor(taskHandle = task.taskHandle, request = request)
            enqueueTaskAfterCommit(taskDescriptor, taskUser)
            return@transactionWithEvents taskDescriptor
        }
    }

    override fun lookup(taskHandle: UUID): BackgroundTaskDescriptor? {
        return transactions.read {
            val task = taskRepository.findByHandle(taskHandle)
            task?.toDescriptor(objectMapper)
        }
    }

    override fun lookupDetails(taskHandle: UUID): BackgroundTaskDetails? {
        return transactions.read {
            val task = taskRepository.findByHandle(taskHandle)
            task?.toDetails(objectMapper)
        }
    }

    override fun cancel(taskHandle: UUID, user: UserDescriptor): Boolean {
        val clock = clockProvider.clock()
        val taskUser = convertTaskUserDescriptor(user)

        // Mark the task as canceled. A task that is not running yet will be skipped by the worker picking it up.
        val shouldSignalWorker = transactionWithEvents(user, requireNew = true) { record ->
            val task = taskRepository.findWriteLockedByHandle(taskHandle)
                ?: throw BackgroundTaskQueue.UnknownTaskHandle(taskHandle)

            val wasRunning = task.status == BackgroundTaskStatus.Running
            val wasCanceled = record(task.canceled(clock))
            // A canceled task is never resumed, so its scratchpad isn't needed anymore
            if (wasCanceled) dataRepository.deleteAllByTaskId(task.id)
            wasCanceled && wasRunning
        }

        // Signal the worker running the task after the commit, so the task isn't locked while awaiting the confirmation.
        // For a task that is not running yet, the worker picking it up broadcasts the Canceled message.
        return shouldSignalWorker && try {
            taskQueue.enqueueCancel(taskHandle, taskUser)
        } catch (ex: Exception) {
            log.warn("Signaling cancellation of background task [{}] failed: {}", taskHandle, ex.message, ex)
            false
        }
    }

    override fun resume(taskHandle: UUID, discardState: Boolean): BackgroundTaskDescriptor {
        val clock = clockProvider.clock()
        return transactions.writeNew {
            val task = taskRepository.findWriteLockedByHandle(taskHandle)
                ?: throw BackgroundTaskQueue.UnknownTaskHandle(taskHandle)

            val taskUser = task.toTaskUser(objectMapper)
            val taskDescriptor = task.toDescriptor(objectMapper)

            val category = taskDescriptor.request.category
            if (!taskQueue.isKnownCategory(category)) {
                throw BackgroundTaskQueue.UnknownTaskCategory(category)
            }

            val result = task.resumed(clock, discardState)
            if (discardState) {
                val deleted = dataRepository.deleteAllByTaskId(task.id)
                log.info("Discarded scratchpad of BackgroundTask #{} ({} entries)", task.id, deleted)
            }
            eventBus.publish(result.events, taskUser)

            log.info("Resuming BackgroundTask #{} [{}]", task.id, task.taskHandle)
            enqueueTaskAfterCommit(taskDescriptor, taskUser)
            return@writeNew taskDescriptor
        }
    }

    private fun convertTaskUserDescriptor(user: UserDescriptor): BackgroundTaskUserDescriptor = when {
        // User already implements BackgroundTaskUserDescriptor
        user is BackgroundTaskUserDescriptor -> user

        // Conversion for type of user to BackgroundTaskUserDescriptor is provided by application
        conversions.canConvert(user.javaClass, BackgroundTaskUserDescriptor::class.java) ->
            checkNotNull(conversions.convert(user, BackgroundTaskUserDescriptor::class.java)) {
                "Failed to convert UserDescriptor for background task: ${user.javaClass.name}"
            }

        // Fallback: Serialize by wrapping user as delegate of BackgroundTaskUserDescriptor
        else -> BackgroundTaskUserDescriptor.delegateOf(user)
    }

    private fun enqueueTaskAfterCommit(task: BackgroundTaskDescriptor, user: BackgroundTaskUserDescriptor) {
        val sync = object : TransactionSynchronization {
            // Before commit completes -> Heuristically check queue availability
            override fun beforeCommit(readOnly: Boolean) {
                if (!taskQueue.checkAvailable()) {
                    throw BackgroundTaskQueue.TaskEnqueueFailed(task, reason = "queue-unavailable")
                }
            }

            // Commit completed successfully -> Publish to queue, compensate for failure
            override fun afterCommit() {
                publishBackgroundTask(task, user)
            }
        }

        // Register synchronization in transaction for publishing
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(sync)
        } else {
            throw BackgroundTaskQueue.TaskEnqueueFailed(task, reason = "transaction-unavailable")
        }
    }

    /**
     * Enqueue the committed task, and mark it as launched.
     * Runs after the commit: Database access requires a new transaction, and exceptions must not propagate.
     */
    private fun publishBackgroundTask(taskDescriptor: BackgroundTaskDescriptor, user: BackgroundTaskUserDescriptor) {
        // Try to launch the task onto the task queue
        val launched = try {
            taskQueue.enqueueLaunch(taskDescriptor, user)
            true
        } catch (ex: Exception) {
            log.error("Publishing background task [${taskDescriptor.taskHandle}] failed: ${ex.message}", ex)
            compensateFailedPublishBackgroundTask(taskDescriptor, user, ex)
            false
        }

        // If launch was successful, transition task entity from created to launched
        if (launched) {
            try {
                // The worker may have started the task already, then it stays running
                transactions.writeNew {
                    val taskEntity = taskRepository.findWriteLockedByHandle(taskDescriptor.taskHandle)
                    taskEntity?.launched()
                }
            } catch (ex: Exception) {
                log.warn(
                    "Marking background task [{}] as launched failed: {}",
                    taskDescriptor.taskHandle, ex.message, ex,
                )
            }
        }
    }

    private fun compensateFailedPublishBackgroundTask(
        task: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
        exception: Exception,
    ) {
        val clock = clockProvider.clock()
        try {
            // Start new transaction, mark still-pending task as launch-failed
            transactionWithEvents(user, requireNew = true) { record ->
                val task = taskRepository.findWriteLockedByHandle(task.taskHandle)
                if (task != null && task.isPending()) {
                    val error = BackgroundTaskError.of(exception)
                    record(task.failed(exception, error, objectMapper, clock))
                }
            }
        } catch (ex: Exception) {
            log.error("Compensating failed publish of background task [${task.taskHandle}] failed: ${ex.message}", ex)
        }
    }

    private fun isReadOnlyTransactionActive(): Boolean {
        return TransactionSynchronizationManager.isActualTransactionActive() &&
                TransactionSynchronizationManager.isCurrentTransactionReadOnly()
    }

    private inline fun <T> transactionWithEvents(
        user: UserDescriptor,
        requireNew: Boolean,
        crossinline block: (DomainResult.Recorder) -> T,
    ): T {
        return transactions.write(requireNew = requireNew) {
            val result = DomainResult(block)
            eventBus.publish(result.events, user)
            result.value
        }
    }
}
