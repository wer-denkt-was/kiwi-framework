package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainResult
import java.util.*

/**
 * Context of a background task execution, passed to [BackgroundTaskHandler] methods.
 * Provides the handler with the task's identity, its execution status, progress reporting,
 * messages to the clients subscribed to the task, and the task's [scratchpad].
 *
 * For handlers divided into stages, [stages] reports the progress based on stages and work items.
 */
interface BackgroundTaskContext {
    /** Secret handle of the task */
    val taskHandle: UUID

    /** Request of the task */
    val request: BackgroundTaskRequest

    /** Descriptor of the user that launched the task, deserialized on the worker executing the task */
    val user: BackgroundTaskUserDescriptor

    /**
     * Number of the current execution attempt, `1` for the first execution.
     * Incremented each time the task is retried after a failure (see [BackgroundTaskHandler.onFailure])
     * or resumed (e.g. after a failure, or a worker crash), see [isResumed].
     */
    val attempt: Int

    /** `true` if the task has been executed before, i.e. it is retried or resumed, see [attempt] */
    val isResumed: Boolean get() = attempt > 1

    /**
     * Execution status of the task as seen by the worker, updated when the task is canceled or aborted.
     * Querying it doesn't access the database.
     */
    val status: BackgroundTaskExecutionStatus

    /**
     * Scratchpad of the task, storing data that survives a failed or interrupted execution,
     * so a resumed task can skip work that has been done before.
     */
    val scratchpad: BackgroundTaskScratchpad

    /**
     * Cancellation point: Throws an [InterruptedException] if the task is not
     * [active][BackgroundTaskExecutionStatus.isActive] anymore, or the executing thread has been interrupted.
     * The handler should let the exception propagate.
     */
    @Throws(InterruptedException::class)
    fun checkActive()

    /**
     * Report the overall progress of the task in percent (`0` to `100`).
     * Progress is stored on the task and broadcast to the clients asynchronously, at a limited rate (only the latest
     * progress is guaranteed to be stored and broadcast). Reporting progress doesn't block, and is no cancellation
     * point.
     */
    fun reportProgress(percent: Int) = reportProgress(BackgroundTaskProgress(percent))

    /**
     * Report the progress of the task, including its current stage, see [reportProgress]
     */
    fun reportProgress(progress: BackgroundTaskProgress)

    /**
     * Send a notification to the clients subscribed to the task, e.g. a message code with its [arguments] for
     * localization. Notifications are sent immediately, on a best-effort basis; they are not stored.
     */
    fun sendNotification(message: String, arguments: Map<String, Any?> = emptyMap())

    /**
     * Send a custom message of the given [messageType] to the clients subscribed to the task, with [details]
     * serialized as JSON. Messages are sent immediately, on a best-effort basis; they are not stored.
     */
    fun sendMessage(messageType: String, details: Any?)

    /**
     * Run the stages declared by the given [declaration], as documented in [BackgroundTaskStages]
     * @see BackgroundTaskStages
     */
    fun <T> stages(declaration: BackgroundTaskStages.Declaration<T>): DomainResult<T>
}
