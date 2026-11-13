package de.kiwi.backgroundTask

import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage.StatusChanged
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.test.context.TestPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.time.Duration

/** Integration tests with the RabbitMQ queue */
@Testcontainers
@TestPropertySource(
    properties = [
        "kiwi.background-task.queue.provider=rabbit-mq",
        "kiwi.background-task.categories.default.max-queue-length=1000",
        "kiwi.background-task.categories.special.max-queue-length=1000",
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
    ],
)
class RabbitBackgroundTaskIntegrationTest : AbstractBackgroundTaskIntegrationTest() {
    companion object {
        @JvmStatic
        @Container
        @ServiceConnection
        val container = RabbitMQContainer("rabbitmq:4.4")
    }

    @Autowired
    lateinit var connectionFactory: ConnectionFactory

    @Autowired
    lateinit var properties: KiwiBackgroundTaskProperties

    @Test
    fun failedTaskIsNotDeadLettered() {
        val rabbitAdmin = RabbitAdmin(connectionFactory)
        val deadLetterQueue = properties.queue.rabbitMq.deadLetterTaskLaunchQueue
        rabbitAdmin.purgeQueue(deadLetterQueue)

        val failing = backgroundTaskService.launch(FailingTaskRequest("I am a failure", delayMs = 0), testUser)
        backgroundTaskMessaging.awaitMessage<StatusChanged.Failed>(Duration.ofSeconds(5), failing.taskHandle).get()

        // The failure has been recorded with the task, its launch message is acknowledged, not dead-lettered
        Thread.sleep(500)
        assertEquals(0L, rabbitAdmin.getQueueInfo(deadLetterQueue)?.messageCount)
    }
}
