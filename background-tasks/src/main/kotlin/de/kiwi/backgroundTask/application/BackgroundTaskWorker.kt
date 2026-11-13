package de.kiwi.backgroundTask.application

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import java.io.Closeable
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Worker interface for executing [BackgroundTaskRequests][BackgroundTaskRequest].
 * Workers are registered in the [BackgroundTaskQueue], which submits tasks of the categories accepted by the worker.
 */
interface BackgroundTaskWorker : Closeable {
    /**
     * Unique handle of the worker instance, used to identify the worker as a queue consumer
     * and for addressing the worker for messaging.
     */
    val workerHandle: UUID

    /**
     * Maximum number of tasks this worker executes concurrently.
     * Queues use it to limit the number of tasks assigned to the worker at once, so that pending tasks remain
     * available to other workers.
     *
     * Note: The RabbitMQ queue applies the limit per category queue (prefetch count of each consumer), so a worker
     * accepting several categories may be assigned up to [maxConcurrentTasks] tasks of *each* category, the excess
     * waiting in the worker's executor.
     */
    val maxConcurrentTasks: Int

    /**
     * `true` if this worker accepts the given [BackgroundTaskRequest] category name.
     * @see BackgroundTaskRequest.category
     */
    fun acceptsCategory(categoryName: String): Boolean

    /**
     * Submit a new [BackgroundTaskDescriptor] for this worker to execute.
     *
     * This will look up the responsible [BackgroundTaskHandler][de.kiwi.api.backgroundTask.BackgroundTaskHandler]
     * for the given [BackgroundTaskRequest], and begin execution of the background task.
     *
     * @return [CompletableFuture] which completes when execution has finished: Exceptionally if the task couldn't be
     * *launched* (e.g. it is unknown, no handler can be resolved, or the worker rejected it), so the queue may discard
     * (e.g. dead-letter) its launch message. Normally if the task has been executed, also if it failed during its
     * execution, as the failure is recorded with the task (which may be resumed):
     * With [ExecutionOutcome.Retry] if the task is waiting for its retry, which the queue must enqueue (or
     * [reject] the task, if it can't).
     */
    fun submit(
        descriptor: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
    ): CompletableFuture<ExecutionOutcome>

    /**
     * Request a running background task executed by this worker to be canceled.
     * @param taskHandle Handle of the [BackgroundTaskDescriptor] identifying the task to be canceled
     * @return `true` if a task with the given handle is currently being executed by this worker, `false` otherwise
     */
    fun cancel(taskHandle: UUID, user: BackgroundTaskUserDescriptor): Boolean

    /**
     * Reject the task of the given handle, which this worker received, but can't execute (e.g. its launch message is
     * unreadable, or the worker has been shut down before starting it): The task fails with the given [exception],
     * unless it has been started already (e.g. by another worker). Invoked by the queue before discarding the task
     * (e.g. dead-lettering its launch message), or if it can't enqueue the task's retry, so the task can be resumed.
     */
    fun reject(taskHandle: UUID, exception: Exception)

    /**
     * Gracefully shut down this worker, after it has been [unregistered][BackgroundTaskQueue.unregisterWorker]:
     * Stop accepting new tasks, and wait up to [timeout] for the submitted tasks to finish. Tasks still running
     * afterward are aborted, i.e. interrupted and marked as failed (so they can be resumed).
     */
    fun shutdown(timeout: Duration)

    /**
     * Outcome of a task execution, completing the [future][CompletableFuture] returned by [submit]
     */
    sealed interface ExecutionOutcome {
        /**
         * The task has been executed (completed, failed, or canceled), or skipped (e.g. not runnable anymore)
         */
        data object Executed : ExecutionOutcome

        /**
         * The task failed, and awaits its retry requested by the handler
         * ([RetryScheduled][de.kiwi.api.backgroundTask.BackgroundTaskStatus.RetryScheduled]):
         * The queue should enqueue the task again, to be delivered to a worker after the given [delay]
         * @property delay Minimum delay before the task is delivered again
         */
        data class Retry(val delay: Duration) : ExecutionOutcome
    }
}
