package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainException
import tools.jackson.databind.JsonNode
import java.time.LocalDateTime
import java.util.*
import java.util.concurrent.TimeoutException

/**
 * Current status and details of a background task, as recorded by the server and the worker executing it.
 * @see de.kiwi.api.application.service.BackgroundTaskService.lookupDetails
 */
data class BackgroundTaskDetails(
    /** Secret handle of the task */
    val taskHandle: UUID,
    /** Class name of the task's [BackgroundTaskRequest] */
    val requestClassName: String,
    /** Current status of the task */
    val status: BackgroundTaskStatus,
    /** Last progress reported by the task handler (stored at a limited rate), `100` percent once completed */
    val progress: BackgroundTaskProgress,
    /** Number of the latest execution attempt, `0` if the task hasn't been started yet */
    val attempt: Int,
    /** When the task has been launched */
    val createdOn: LocalDateTime,
    /** ID of the authenticated user that launched the task, if any */
    val createdByUserId: Long?,
    /** Name of the authenticated user that launched the task, if any */
    val createdByUsername: String?,
    /** When a worker started executing the task (the latest attempt) */
    val startedOn: LocalDateTime?,
    /** When the task has been closed (completed, failed, or canceled) */
    val completedOn: LocalDateTime?,
    /** Result value returned by the task handler, if [completed][BackgroundTaskStatus.Completed] */
    val result: JsonNode?,
    /**
     * Error of the task, if [failed][BackgroundTaskStatus.Failed], or of the failed attempt while the task waits for its
     * [retry][BackgroundTaskStatus.RetryScheduled]
     */
    val error: BackgroundTaskError?,
    /** When the retry of the task is due, if [RetryScheduled][BackgroundTaskStatus.RetryScheduled] */
    val retryOn: LocalDateTime? = null,
)

/**
 * Error of a failed background task, as sent to clients and returned in [BackgroundTaskDetails].
 *
 * The contents match the error response rendering of the Kiwi web layer (`message`, `arguments`, `errorType`):
 * - [DomainExceptions][DomainException] are rendered with their code, arguments, and class name.
 * - Well-known general exceptions ([WELL_KNOWN_ERRORS], e.g. [IllegalArgumentException] as `illegal-argument`) are
 *   rendered with their error code, their message as the argument `reason`, and their class name.
 * - Other exceptions are rendered as a generic [UNKNOWN_ERROR], with their simple class name as the argument `type`,
 *   so internals (e.g. SQL statements or file paths in exception messages) are not disclosed to clients.
 */
data class BackgroundTaskError(
    /** Error code of the [DomainException], of a well-known exception, or [UNKNOWN_ERROR] */
    val message: String,
    /** Arguments of the [DomainException], the `reason` of a well-known exception, or the `type` otherwise */
    val arguments: Map<String, *>,
    /** Class name of the [DomainException] or well-known exception, or of [DomainException.Unknown] otherwise */
    val errorType: String,
) {
    companion object {
        /** Error code of exceptions which are neither [DomainExceptions][DomainException] nor well-known */
        const val UNKNOWN_ERROR = "unknown"

        /**
         * Error codes of well-known general exceptions, whose message is included as the argument `reason`.
         * The first entry matching the exception (including subclasses) applies.
         */
        val WELL_KNOWN_ERRORS = mapOf(
            "illegal-argument" to IllegalArgumentException::class.java,
            "illegal-state" to IllegalStateException::class.java,
            "unsupported-operation" to UnsupportedOperationException::class.java,
            "no-such-element" to NoSuchElementException::class.java,
            "timeout" to TimeoutException::class.java,
        )

        /** Create the client-facing [BackgroundTaskError] of the given [exception] */
        fun of(exception: Throwable): BackgroundTaskError {
            if (exception is DomainException) {
                return BackgroundTaskError(
                    message = exception.code,
                    arguments = exception.args,
                    errorType = exception.eventName,
                )
            }

            val wellKnownCode = WELL_KNOWN_ERRORS.entries.firstNotNullOfOrNull { (code, type) ->
                if (type.isInstance(exception)) code else null
            }
            return if (wellKnownCode != null) {
                BackgroundTaskError(
                    message = wellKnownCode,
                    arguments = mapOf("reason" to exception.message),
                    errorType = exception.javaClass.name,
                )
            } else {
                BackgroundTaskError(
                    message = UNKNOWN_ERROR,
                    arguments = mapOf("type" to exception.javaClass.simpleName),
                    errorType = DomainException.Unknown::class.java.name,
                )
            }
        }
    }
}
