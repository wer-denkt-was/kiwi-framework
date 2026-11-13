package de.kiwi.api.backgroundTask

/**
 * Status of a background task execution, as seen by the handler via [BackgroundTaskContext.status].
 * @property isActive `true` if the execution should continue
 */
enum class BackgroundTaskExecutionStatus(val isActive: Boolean) {
    /**
     * The task is currently running
     */
    Running(isActive = true),

    /**
     * The task has been canceled: The handler should stop, the task will be closed as canceled
     */
    CancelRequested(isActive = false),

    /**
     * The execution has been aborted, e.g. because the worker is shutting down,
     * or another worker has taken over the task: The handler should stop, the task may be resumed later
     */
    AbortRequested(isActive = false),
}
