package de.kiwi.backgroundTask.application.messaging

import de.kiwi.api.backgroundTask.BackgroundTaskError
import de.kiwi.api.backgroundTask.BackgroundTaskProgress
import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.util.*

/**
 * Message about a background task, broadcast by the worker executing it to the topic associated with the
 * [taskHandle], e.g. to clients subscribed to it via STOMP over WebSockets.
 *
 * Messages are serialized as JSON, with [messageType] identifying the kind of message. They are sent on a best-effort
 * basis; the persistent state of a task is available via
 * [BackgroundTaskService.lookupDetails][de.kiwi.api.application.service.BackgroundTaskService.lookupDetails].
 */
sealed interface BackgroundTaskMessage {
    /** Handle of the task this message is about */
    val taskHandle: UUID

    /** Type of the message, by default the simple class name, e.g. `Started` or `Notification` */
    val messageType: String get() = javaClass.simpleName

    /** Sent by the worker on task status change */
    sealed class StatusChanged(
        /** Status of the task after the change */
        val status: BackgroundTaskStatus,
    ) : BackgroundTaskMessage {

        /** Sent when a worker starts executing the task, [attempt] is greater than `1` if the task has been resumed */
        data class Started(
            override val taskHandle: UUID,
            /** Number of the execution attempt, `1` for the first execution */
            val attempt: Int,
        ) : StatusChanged(BackgroundTaskStatus.Running)

        /** Sent when the task has been completed successfully, with the handler's result value */
        data class Completed(
            override val taskHandle: UUID,
            /** Result value returned by the handler, serialized as JSON (`null` for `Unit` and `null`) */
            val completionResult: JsonNode,
        ) : StatusChanged(BackgroundTaskStatus.Completed)

        /**
         * Sent when the task has failed with an exception.
         * The [error] doesn't disclose details of exceptions other than
         * [DomainExceptions][de.kiwi.api.domain.DomainException] and well-known exceptions, see [BackgroundTaskError].
         */
        data class Failed(
            override val taskHandle: UUID,
            /** Client-facing error of the task */
            val error: BackgroundTaskError,
        ) : StatusChanged(BackgroundTaskStatus.Failed)

        /**
         * Sent when the task failed, and waits for the retry requested by its handler: A worker starts the next attempt
         * when the retry is due ([retryOn]), or later. Like [Failed.error], the [error] of the failed attempt doesn't
         * disclose exception details.
         */
        data class RetryScheduled(
            override val taskHandle: UUID,
            /** Client-facing error of the failed attempt */
            val error: BackgroundTaskError,
            /** Number of the failed attempt */
            val attempt: Int,
            /** When the retry is due (local date-time of the server's clock), at the earliest */
            val retryOn: LocalDateTime?,
        ) : StatusChanged(BackgroundTaskStatus.RetryScheduled)

        /** Sent when the task has been canceled, before or during its execution */
        data class Canceled(
            override val taskHandle: UUID,
        ) : StatusChanged(BackgroundTaskStatus.Canceled)
    }

    /** Progress of the running task, sent at a limited rate when the progress changed */
    data class Progress(
        override val taskHandle: UUID,
        /** Latest progress of the task, including its current stage */
        val progress: BackgroundTaskProgress,
    ) : BackgroundTaskMessage

    /** Notification of the running task, e.g. a message code with its [arguments] for localization */
    data class Notification(
        override val taskHandle: UUID,
        /** Notification message, e.g. a message code */
        val message: String,
        /** Arguments of the [message], e.g. for localization */
        val arguments: Map<String, *> = emptyMap<String, Nothing>(),
    ) : BackgroundTaskMessage

    /** Custom message of the given [messageType] of the running task, with [details] serialized as JSON */
    data class Custom(
        override val taskHandle: UUID,
        override val messageType: String,
        /** Details of the message, serialized as JSON */
        val details: Any?,
    ) : BackgroundTaskMessage
}
