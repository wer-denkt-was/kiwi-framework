package de.kiwi.api.application.service

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskDetails
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.api.domain.UserDescriptor
import java.util.*

/**
 * Service providing functionality to launch and manage background tasks
 * that run distributed over independent worker process instances.
 *
 * Provided by the `kiwi-background-tasks` module.
 */
interface BackgroundTaskService {

    /**
     * Launch a new background task, which will be enqueued for execution on a worker process.
     *
     * The task is persisted in the caller's transaction (or a new one, if there is none), and is enqueued after that
     * transaction has been committed: If the caller's transaction rolls back, the task is never enqueued.
     * The returned [BackgroundTaskDescriptor] contains the *secret* task handle, which identifies the task e.g. for
     * subscribing to its messages.
     *
     * Launching fails immediately if the task's category is unknown to the queue. If the queue is unavailable,
     * committing the transaction fails. If enqueueing fails after the commit, the task is marked as failed.
     * Launching a task in a read-only transaction fails with an [IllegalStateException].
     */
    fun launch(request: BackgroundTaskRequest, user: UserDescriptor): BackgroundTaskDescriptor

    /**
     * Find the [BackgroundTaskDescriptor] of a launched background task, or `null` if the given handle does not exist
     */
    fun lookup(taskHandle: UUID): BackgroundTaskDescriptor?

    /**
     * Find the current status and details of a launched background task, including its result once completed,
     * or `null` if the given handle does not exist
     */
    fun lookupDetails(taskHandle: UUID): BackgroundTaskDetails?

    /**
     * Send a signal to cancel a launched or running background task.
     * The task is marked as canceled immediately, but the function returns before the task is definitively stopped:
     * - If the task has been launched but is not yet running, it will be skipped by the worker picking it up, which
     *   then broadcasts the `Canceled` message.
     * - If the task is running, the function returns after the cancellation signal is *confirmed* by the worker
     *   running the background task, which interrupts the task's execution.
     *
     * Throws an exception if the given [taskHandle] does not exist, or the task has already been completed or failed.
     * @return `true` if the task cancellation was received by the worker running the task, `false` if the task was not
     * running yet, has already been canceled, or the worker did not confirm the signal.
     */
    fun cancel(taskHandle: UUID, user: UserDescriptor): Boolean

    /**
     * Resume a task as a new execution attempt, that is either:
     * - Failed
     * - Pending (e.g. its launch has been lost)
     * - Waiting for its retry (e.g. its retry message has been lost)
     * - Running, whose worker's lease expired
     *
     * The task is enqueued again (on behalf of the user that launched it), and executed with its retained progress
     * and scratchpad. Resuming is an administrative operation (e.g. via the `backgroundTasks` management endpoint):
     * Callers are responsible for authorizing it.
     *
     * With [discardState], the task is restarted from a clean slate instead: Its scratchpad (including the
     * checkpoints of its stages) is deleted, and its progress is reset, so the next attempt repeats all work.
     * @throws de.kiwi.api.domain.DomainException if the task doesn't exist, isn't resumable, or can't be enqueued
     */
    fun resume(taskHandle: UUID, discardState: Boolean = false): BackgroundTaskDescriptor
}
