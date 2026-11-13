package de.kiwi.backgroundTask.infrastructure

import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * [BackgroundTaskMessaging] sending messages via Spring's [SimpMessagingTemplate] (e.g. STOMP over WebSockets)
 * to the topic `/topic/<topicPrefix>.<taskHandle>`.
 */
class SimpBackgroundTaskMessaging(
    /** Messaging template sending the messages */
    val messaging: SimpMessagingTemplate,
    /** Prefix of the topic names, followed by `.<taskHandle>` */
    val topicPrefix: String,
) : BackgroundTaskMessaging {

    override fun broadcastMessage(message: BackgroundTaskMessage) {
        val taskHandle = message.taskHandle
        val topic = "/topic/$topicPrefix.$taskHandle"
        messaging.convertAndSend(topic, message)
    }
}
