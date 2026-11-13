package de.kiwi.backgroundTask.application.messaging

import de.kiwi.api.util.slf4j

/**
 * Interface for infrastructure broadcasting messages about a background task, used by the workers.
 * Implementations should not throw on delivery failures, as messages are sent on a best-effort basis.
 * @see de.kiwi.backgroundTask.infrastructure.SimpBackgroundTaskMessaging
 */
interface BackgroundTaskMessaging {

    /** Broadcast the given background task message to the topic associated with its task handle */
    fun broadcastMessage(message: BackgroundTaskMessage)
}

private val log = slf4j<BackgroundTaskMessaging>()

/** Broadcast the given message on a best-effort basis, logging (instead of propagating) failures */
fun BackgroundTaskMessaging.broadcastSafely(message: BackgroundTaskMessage) {
    try {
        broadcastMessage(message)
    } catch (ex: Exception) {
        log.warn(
            "Failed to broadcast {} of BackgroundTask [{}]: {}",
            message.messageType, message.taskHandle, ex.message, ex,
        )
    }
}
