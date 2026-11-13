package de.kiwi.backgroundTask.application

import de.kiwi.api.backgroundTask.BackgroundTaskHandler
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.api.domain.DomainException

/**
 * Resolver for the [BackgroundTaskHandler] responsible for a given [BackgroundTaskRequest].
 */
interface BackgroundTaskResolver {
    /**
     * Resolve the handler of the given [request].
     * @throws MissingBackgroundTaskHandler if no handler is responsible for the request
     */
    fun <R : BackgroundTaskRequest> resolve(request: R): BackgroundTaskHandler<R>
}

/** Thrown if no [BackgroundTaskHandler] is responsible for a [BackgroundTaskRequest] */
data class MissingBackgroundTaskHandler(val requestClassName: String) : DomainException(
    status = Status.InternalError,
    code = "missing-background-task-handler",
    args = mapOf("requestClassName" to requestClassName),
)
