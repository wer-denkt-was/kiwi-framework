package de.kiwi.backgroundTask.domain.interaction

import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.SubjectDomainEvent
import de.kiwi.backgroundTask.domain.BackgroundTask
import de.kiwi.backgroundTask.domain.schema.BackgroundTaskFieldsets
import java.time.Duration

/** A new [BackgroundTask] has been created by launching it */
class BackgroundTaskCreated(task: BackgroundTask) : SubjectDomainEvent<BackgroundTask>(task) {
    override val detailsProvider = BackgroundTaskFieldsets.short
}

/** A worker has started executing the [task] */
data class BackgroundTaskStarted(val task: BackgroundTask) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
    )
}

/** The [task] has been completed successfully */
data class BackgroundTaskCompleted(val task: BackgroundTask) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
    )
}

/**
 * The [task] has been resumed for a new execution attempt, after it was in the [previousStatus]
 * (failed, pending, waiting for its retry, or running with an expired lease)
 */
data class BackgroundTaskResumed(
    /** The resumed task */
    val task: BackgroundTask,
    /** Status of the task before it was resumed */
    val previousStatus: BackgroundTaskStatus,
    /** `true` if the task's scratchpad and progress has been discarded, restarting the task from a clean slate */
    val stateDiscarded: Boolean = false,
) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
        "previousStatus" to previousStatus.name,
        "previousAttempt" to task.attempt,
        "stateDiscarded" to stateDiscarded,
    )
}

/**
 * The running [task] failed with the given [exception], and its handler requested a retry after the given [delay]:
 * The task waits for its next attempt
 */
data class BackgroundTaskRetryScheduled(
    /** The task waiting for its retry */
    val task: BackgroundTask,
    /** Exception the failed attempt threw */
    val exception: Throwable,
    /** Delay before the retry, as requested by the handler (and capped by the worker) */
    val delay: Duration,
) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
        "failedAttempt" to task.attempt,
        "retryDelay" to delay.toString(),
        "errorClassName" to exception.javaClass.name,
        "errorMessage" to exception.message,
    )
}

/** The [task] has been canceled */
data class BackgroundTaskCanceled(val task: BackgroundTask) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
    )
}

/** The [task] has failed with the given [exception] */
data class BackgroundTaskFailed(val task: BackgroundTask, val exception: Throwable) : DomainEvent() {
    override val details = mapOf(
        "task" to BackgroundTaskFieldsets.short.extract(task),
        "errorClassName" to exception.javaClass.name,
        "errorMessage" to exception.message,
    )
}
