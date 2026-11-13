package de.kiwi.api.backgroundTask

/**
 * Lifecycle status of a background task.
 *
 * A task is [Created] when [BackgroundTaskService.launch][de.kiwi.api.application.service.BackgroundTaskService.launch]
 * persists it, and becomes [Launched] once it has been enqueued. A worker picking up the task marks it as [Running],
 * and finally closes it as [Completed], [Failed] or [Canceled]. If the handler requests a retry after a failure, the
 * task waits as [RetryScheduled] until it is picked up again for its next attempt. Closed tasks never change their
 * status again, except [Failed] tasks, which may be resumed by an administrator (returning to [Created] for a new
 * attempt).
 */
enum class BackgroundTaskStatus(
    /** `true` if the task is waiting to be started by a worker */
    val isPending: Boolean = false,
    /** `true` if a worker is currently executing the task */
    val isRunning: Boolean = false,
    /** `true` if the task has reached a final status */
    val isClosed: Boolean = false,
) {
    /** Task has been created, but not enqueued yet */
    Created(isPending = true),

    /** Task has been enqueued, but not picked up by a worker yet */
    Launched(isPending = true),

    /** Task is currently being executed by a worker */
    Running(isRunning = true),

    /**
     * Task failed, and waits for its retry requested by the handler: It is enqueued again after the retry delay, and
     * picked up by a worker for its next attempt
     */
    RetryScheduled(isPending = true),

    /** Task has been completed successfully */
    Completed(isClosed = true),

    /** Task has failed, either during execution or because it could not be enqueued */
    Failed(isClosed = true),

    /** Task has been canceled, before or during its execution */
    Canceled(isClosed = true),
}
