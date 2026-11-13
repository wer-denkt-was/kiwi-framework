package de.kiwi.backgroundTask.application

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.domain.DomainException
import java.util.*

/**
 * Queue distributing launched background tasks to [BackgroundTaskWorker] instances,
 * and relaying control signals (e.g. cancellation) to the worker executing a task.
 *
 * The queue does not access the task entities, the task status is maintained by the
 * [BackgroundTaskService][de.kiwi.api.application.service.BackgroundTaskService] and the workers.
 * @see de.kiwi.backgroundTask.infrastructure.LocalBackgroundTaskQueue
 * @see de.kiwi.backgroundTask.infrastructure.RabbitBackgroundTaskQueue
 */
interface BackgroundTaskQueue {

    /**
     * Enqueue launching a new task, to be executed by a worker accepting the task's category.
     * @throws UnknownTaskCategory if no queue or worker exists for the task's category
     * @throws TaskEnqueueFailed if the task could not be enqueued
     */
    @Throws(UnknownTaskCategory::class, TaskEnqueueFailed::class)
    fun enqueueLaunch(descriptor: BackgroundTaskDescriptor, user: BackgroundTaskUserDescriptor)

    /**
     * Enqueue the signal to cancel a running task, and wait for the worker executing the task to confirm it.
     * @return `true` if the task is running and the signal has been received by its worker, `false` otherwise
     * @throws TaskControlFailed if the signal could not be sent, or was not confirmed in time
     */
    @Throws(UnknownTaskHandle::class, TaskControlFailed::class)
    fun enqueueCancel(taskHandle: UUID, user: BackgroundTaskUserDescriptor): Boolean

    /**
     * Check if the underlying queue is available for enqueueing messages.
     * This is a heuristic, indicating that the next enqueue will likely succeed, but doesn't guarantee success.
     * Used for checking queue availability before committing a transaction, alleviating the dual-write problem.
     */
    fun checkAvailable(): Boolean

    /**
     * `true` if tasks of the given category can be enqueued, i.e. the queue (or a worker) for the category exists.
     * Checked when launching a task, before it is persisted.
     */
    fun isKnownCategory(categoryName: String): Boolean

    /**
     * Register a [BackgroundTaskWorker] to accept requests from this queue.
     * The worker receives tasks of the categories it [accepts][BackgroundTaskWorker.acceptsCategory].
     */
    fun registerWorker(worker: BackgroundTaskWorker)

    /**
     * Unregister a [BackgroundTaskWorker], so it doesn't receive new tasks anymore.
     * Tasks already submitted to the worker are not affected, control signals for them are still relayed, but a
     * queue may redeliver them to other workers (which skip tasks that are not runnable anymore).
     */
    fun unregisterWorker(worker: BackgroundTaskWorker)

    /**
     * The task has been rejected by the queue or worker that received it, for the given [reason]
     * (e.g. `unreadable-message`, or `worker-shutdown`), see [BackgroundTaskWorker.reject]
     */
    class TaskLaunchRejected(reason: String, cause: Throwable? = null) : DomainException(
        status = Status.InternalError,
        code = "task-launch-rejected",
        args = mapOf("reason" to reason),
    ) {
        init {
            if (cause != null) initCause(cause)
        }
    }

    /** No queue or worker exists for the task [category] */
    data class UnknownTaskCategory(val category: String) : DomainException(
        status = Status.InternalError,
        code = "unknown-task-category",
        args = mapOf("category" to category),
    )

    /**
     * The task could not be enqueued for the given [reason].
     * The secret task handle is not included in the arguments (which may be disclosed), only in the [message] for logs.
     */
    data class TaskEnqueueFailed(val descriptor: BackgroundTaskDescriptor, val reason: String?) : DomainException(
        status = Status.InternalError,
        code = "task-enqueued-failed",
        args = mapOf(
            "taskClassName" to descriptor.request.javaClass.name,
            "reason" to reason,
        ),
    ) {
        override val message: String get() = "${super.message} [${descriptor.taskHandle}]"
    }

    /**
     * A control signal for the task could not be delivered for the given [reason].
     * The secret task handle is not included in the arguments (which may be disclosed), only in the [message] for logs.
     */
    data class TaskControlFailed(val taskHandle: UUID, val reason: String?) : DomainException(
        status = Status.InternalError,
        code = "task-control-failed",
        args = mapOf("reason" to reason),
    ) {
        override val message: String get() = "${super.message} [$taskHandle]"
    }

    /**
     * No task exists for the [taskHandle].
     * The secret task handle is not included in the arguments (which may be disclosed), only in the [message] for logs.
     */
    data class UnknownTaskHandle(val taskHandle: UUID) : DomainException(
        status = Status.InternalError,
        code = "unknown-task-handle",
    ) {
        override val message: String get() = "${super.message} [$taskHandle]"
    }
}
