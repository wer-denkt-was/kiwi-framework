package de.kiwi.api.backgroundTask

import java.io.Serializable
import java.util.*

/**
 * Descriptor of a launched background task, combining its [taskHandle] and the underlying [request].
 *
 * The [taskHandle] is a *secure* random [UUID] identifying the task, e.g. for looking it up, canceling it, or
 * subscribing to its messages.
 * It must be kept secret: knowing the handle grants access to the task's messages.
 *
 * @see de.kiwi.api.application.service.BackgroundTaskService
 */
data class BackgroundTaskDescriptor(
    /** Secret handle identifying the task */
    val taskHandle: UUID,
    /** Request of the task, determining its handler */
    val request: BackgroundTaskRequest,
) : Serializable
