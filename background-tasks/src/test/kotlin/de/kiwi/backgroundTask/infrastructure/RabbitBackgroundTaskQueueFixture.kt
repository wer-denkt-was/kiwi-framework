package de.kiwi.backgroundTask.infrastructure

import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskCategory
import de.kiwi.backgroundTask.testObjectMapper
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.context.support.GenericApplicationContext
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.net.URI
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskQueue.RabbitMq as TaskRabbitMqConfig

class RabbitBackgroundTaskQueueFixture {
    private lateinit var connectionFactory: CachingConnectionFactory
    private lateinit var applicationContext: GenericApplicationContext
    private val queues = mutableListOf<RabbitBackgroundTaskQueue>()

    fun setUp(container: RabbitMQContainer) {
        val rabbitUri = URI.create(container.amqpUrl)
        connectionFactory = CachingConnectionFactory(rabbitUri)
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED)
        connectionFactory.isPublisherReturns = true
        applicationContext = GenericApplicationContext()
        applicationContext.refresh()
    }

    fun tearDown() {
        queues.forEach { it.destroy() }
        queues.clear()
        connectionFactory.destroy()
        applicationContext.close()
    }

    fun createQueue(
        rabbitConfig: TaskRabbitMqConfig,
        additionalCategories: Map<String, TaskCategory> = emptyMap(),
    ): RabbitBackgroundTaskQueue {
        val standardCategories = mapOf(
            BackgroundTaskQueueHarness.DEFAULT_CATEGORY to TaskCategory(),
            BackgroundTaskQueueHarness.SPECIAL_CATEGORY to TaskCategory(),
        )

        val queue = RabbitBackgroundTaskQueue(
            connectionFactory = connectionFactory,
            categoryConfigs = standardCategories + additionalCategories,
            rabbitConfig = rabbitConfig,
            objectMapper = testObjectMapper(),
        )
        queue.setApplicationContext(applicationContext)
        queue.afterPropertiesSet()
        queues.add(queue)
        return queue
    }

    /** Declare a classic queue (not a quorum queue) with the given name */
    fun declareClassicQueue(queueName: String) {
        val admin = RabbitAdmin(connectionFactory)
        admin.declareQueue(Queue(queueName, /* durable = */ true, false, false, mapOf("x-queue-type" to "classic")))
    }

    /**
     * Stop all consumers:
     * Unacknowledged messages are re-queued, so the remaining messages can be counted
     */
    fun stopConsumersAndCountMessages(rabbitConfig: TaskRabbitMqConfig, category: String): Long? {
        queues.forEach { it.destroy() }
        queues.clear()

        val launchQueuePrefix = rabbitConfig.taskLaunchQueue
        return countMessages("$launchQueuePrefix.$category")
    }

    /**
     * Close the shared connection, as if it was lost (e.g. by a network outage): The broker deletes the exclusive
     * queues of the connection, and the next use of the connection factory establishes a new connection
     */
    fun resetConnection() {
        connectionFactory.resetConnection()
    }

    /** Count the messages in the given queue, `null` if it doesn't exist */
    fun countMessages(queueName: String): Long? {
        val admin = RabbitAdmin(connectionFactory)
        val queueInfo = admin.getQueueInfo(queueName)
        return queueInfo?.messageCount
    }

    fun sendToLaunchQueue(rabbitConfig: TaskRabbitMqConfig, category: String, content: String) {
        val launchQueuePrefix = rabbitConfig.taskLaunchQueue
        val launchQueueName = "$launchQueuePrefix.$category"
        val template = RabbitTemplate(connectionFactory)
        val messageProps = MessageProperties()
        val message = Message(content.toByteArray(Charsets.UTF_8), messageProps)
        template.send(rabbitConfig.exchange, launchQueueName, message)
    }
}
