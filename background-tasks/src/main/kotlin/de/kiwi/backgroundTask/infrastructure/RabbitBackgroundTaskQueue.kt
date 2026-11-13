package de.kiwi.backgroundTask.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.rabbitmq.client.Channel
import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskQueue.TaskEnqueueFailed
import de.kiwi.backgroundTask.application.BackgroundTaskWorker
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import org.springframework.amqp.AmqpException
import org.springframework.amqp.core.*
import org.springframework.amqp.core.Queue
import org.springframework.amqp.rabbit.config.DirectRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.connection.CorrelationData
import org.springframework.amqp.rabbit.core.RabbitAdmin
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonTypeRef
import tools.jackson.module.kotlin.readValue
import java.io.Serializable
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskCategory as TaskCategoryProps
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskQueue.RabbitMq as RabbitMqProps

private val log = slf4j<RabbitBackgroundTaskQueue>()

/**
 * Header of a delayed message (RabbitMQ 4.4+, quorum queues):
 * Time (epoch milliseconds) at which the broker delivers the message to consumers, not before
 */
internal const val DELIVERY_TIME_HEADER = "x-opt-delivery-time"

/**
 * Minimum RabbitMQ version 4.4 (major and minor) supporting delayed messages ([DELIVERY_TIME_HEADER]) in quorum queues
 */
internal val MIN_BROKER_VERSION = KotlinVersion(4, 4)

/**
 * [BackgroundTaskQueue] implementation using RabbitMQ,
 * distributing tasks to workers in any process instance connected to the broker.
 *
 * Requires RabbitMQ 4.4 or later for delayed messages in quorum queues; checked on initialization,
 * by the broker version announced on connection.
 *
 * Topology, declared on initialization:
 * - One durable direct exchange ([RabbitMqProps.exchange]).
 * - A task-launch queue per task category (`<taskLaunchQueue>.<category>`), a quorum queue bound to the exchange with
 *   its name. Initialization fails if the queue exists with another type (e.g. a classic queue): It must be drained
 *   and deleted to be redeclared.
 *   Each registered worker consumes the queues of the categories it accepts, with a prefetch count of its
 *   [maximum concurrency][BackgroundTaskWorker.maxConcurrentTasks]. The prefetch count applies per consumer, i.e.
 *   per category queue: a worker accepting several categories may receive that many tasks of *each* category at
 *   once, the excess waiting in its executor.
 *   Launch messages are acknowledged when the task execution has finished, so the broker redelivers tasks of a worker
 *   that died during execution. A task that failed during its execution is acknowledged as well, as the failure is
 *   recorded with the task (which may be resumed, publishing a new launch message). Only messages of tasks which
 *   couldn't be *launched* (unreadable message, failed submission, or the worker failed to start the task) are rejected
 *   without re-queueing, and dead-lettered.
 *   With a [maximum length][TaskCategoryProps.maxQueueLength], a full queue rejects new launch messages
 *   (`x-overflow: reject-publish`), so enqueueing fails instead of dropping the oldest pending task.
 *   When a task failed and its handler requested a retry ([ExecutionOutcome.Retry]), its launch message is published
 *   to the task-launch queue again, as a delayed message ([DELIVERY_TIME_HEADER] set to the time the retry is due),
 *   *before* the original launch message is acknowledged. The broker delivers it to a worker once it is due.
 *   Delayed messages count toward the maximum length of the queue. If the retry can't be published (e.g. the queue is
 *   full), the task is rejected (failed, so it can be resumed).
 * - A durable dead-letter exchange ([RabbitMqProps.deadLetterExchange]) and queue
 *   ([RabbitMqProps.deadLetterTaskLaunchQueue]), receiving the rejected launch messages of all categories.
 *   The dead-letter queue is not consumed, it retains the messages for inspection. Administration should monitor
 *   and purge this queue regularly.
 * - An exclusive, auto-deleted worker-control queue per process instance (`<workerControlQueue>.<random>`).
 *   While a local worker executes a task, the queue is bound to the exchange with the task's control routing key
 *   (`<taskControlBinding>.<taskHandle>`), so control messages like cancellations reach the executing worker,
 *   which replies to confirm them.
 *
 * All declarations are redeclared when the connection is re-established (see
 * [RabbitAdmin.setRedeclareManualDeclarations]), e.g. after a network outage or broker restart: The exclusive
 * worker-control queue is deleted by the broker when its connection is closed, and non-durable queues when the broker
 * restarts. Task-control bindings removed in the meantime are not redeclared.
 *
 * Publisher confirms (`spring.rabbitmq.publisher-confirm-type=correlated`) should be enabled on the connection
 * factory: enqueueing then waits for the broker to confirm the launch message, and fails if the broker rejected it
 * (e.g. its queue is full). Without confirms, rejected launch messages are lost, and their tasks stay launched.
 * With publisher returns (`spring.rabbitmq.publisher-returns=true`), unroutable launch messages fail as well.
 */
class RabbitBackgroundTaskQueue(
    private val connectionFactory: ConnectionFactory,
    private val categoryConfigs: Map<String, TaskCategoryProps>,
    private val rabbitConfig: RabbitMqProps,
    private val objectMapper: ObjectMapper,
) : BackgroundTaskQueue, ApplicationContextAware, InitializingBean, DisposableBean {
    private lateinit var applicationContext: ApplicationContext
    private lateinit var rabbitTemplate: RabbitTemplate
    private lateinit var rabbitAdmin: RabbitAdmin

    private lateinit var listenerExecutor: ExecutorService
    private lateinit var listenerFactory: DirectRabbitListenerContainerFactory

    private var exchange: Exchange? = null
    private var workerControlQueue: Queue? = null
    private var workerControlListener: DirectMessageListenerContainer? = null
    private val categoryTaskLaunchQueues = ConcurrentHashMap<String, Queue>()
    private val taskLaunchQueueListeners = ConcurrentHashMap<BackgroundTaskWorker, DirectMessageListenerContainer>()

    /**
     * Deliveries of tasks to the local workers, by task handle: A task may be delivered more than once (e.g.
     * redelivered, or resumed while a superseded execution is still running). The task-control binding is declared by
     * the first delivery, and removed when the last one has finished.
     *
     * Assignments are immutable, and changed atomically per task handle (see [ConcurrentHashMap.compute]), so the
     * declaration and removal of a task's binding are ordered, without blocking the deliveries of other tasks.
     */
    private val taskAssignments = ConcurrentHashMap<UUID, TaskAssignment>()

    /** Task-control [binding] of a task, and the [workers] it has been delivered to (the latest last) */
    private data class TaskAssignment(
        val binding: Binding,
        val workers: List<BackgroundTaskWorker>,
    )

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    override fun afterPropertiesSet() {
        initializeListener()
        initializeTemplate()
        try {
            checkBrokerVersion()
            initializeExchange()
            initializeDeadLetterQueue()
            initializeWorkerControlQueue()
            initializeTaskLaunchQueues()
        } catch (ex: Exception) {
            handleStartupException(ex)
        }

        rabbitTemplate.afterPropertiesSet()
        rabbitAdmin.afterPropertiesSet()
    }

    private fun handleStartupException(exception: Exception): Nothing {
        try {
            // Stop the worker-control listener, as the bean won't be destroyed
            destroy()
        } catch (suppressed: Throwable) {
            exception.addSuppressed(suppressed)
        }
        // Re-throw the startup exception
        throw exception
    }

    override fun destroy() {
        // Stop all listeners
        workerControlListener?.safeStop()
        workerControlListener = null
        taskLaunchQueueListeners.values.forEach { it.safeStop() }
        taskLaunchQueueListeners.clear()

        // Shutdown listener executor
        listenerExecutor.shutdown()

        // Cleanup bean state
        exchange = null
        workerControlQueue = null
        categoryTaskLaunchQueues.clear()
        taskAssignments.clear()

        // Don't redeclare the declarations of this instance anymore when the (shared) connection is re-established
        rabbitAdmin.resetAllManualDeclarations()
    }

    //#region BackgroundTaskQueue implementation

    override fun enqueueLaunch(descriptor: BackgroundTaskDescriptor, user: BackgroundTaskUserDescriptor) {
        val exchangeName = exchange!!.name
        val categoryName = descriptor.request.category
        val launchQueue = categoryTaskLaunchQueues[categoryName]
            ?: throw BackgroundTaskQueue.UnknownTaskCategory(categoryName)

        val launchQueueName = launchQueue.name
        val launchTaskBody = LaunchTaskBody(descriptor, user)
        val launchTaskMessage = createJsonMessage(launchTaskBody)

        val failureReason = publish(exchangeName, launchQueueName, launchTaskMessage)
        if (failureReason != null) {
            log.warn(
                "Background task launch (exchange = '{}', queue = '{}') failed: {}",
                exchangeName, launchQueueName, failureReason,
            )
            throw TaskEnqueueFailed(descriptor, reason = failureReason)
        }
    }

    /**
     * Enqueue the retry of a failed task after the given [retryDelay]: Publish its launch message to the category's
     * task-launch queue as a delayed message, due after the delay, and reject the task via the [previousWorker] that
     * executed it if that fails
     */
    private fun enqueueRetry(
        descriptor: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
        retryDelay: Duration,
        previousWorker: BackgroundTaskWorker,
    ) {
        val exchangeName = exchange!!.name
        val categoryName = descriptor.request.category
        val launchQueue = categoryTaskLaunchQueues[categoryName]

        val failureReason = if (launchQueue == null) {
            "unknown-category"
        } else {
            val retryMessage = createJsonMessage(LaunchTaskBody(descriptor, user)) {
                if (retryDelay.isPositive) setHeader(DELIVERY_TIME_HEADER, deliveryTime(retryDelay))
            }
            publish(exchangeName, launchQueue.name, retryMessage)
        }

        if (failureReason != null) {
            log.error(
                "Background task retry (exchange = '{}', queue = '{}') failed: {}",
                exchangeName, launchQueue?.name, failureReason,
            )
            val cause = IllegalStateException("Enqueueing the retry failed: $failureReason")
            previousWorker.reject(descriptor.taskHandle, BackgroundTaskQueue.TaskLaunchRejected("retry-failed", cause))
        }
    }

    /**
     * Publish the message, waiting for the broker's confirmation if publisher confirms are enabled.
     * @return the reason if publishing failed, or the broker rejected or returned the message, `null` on success
     */
    private fun publish(exchangeName: String, routingKey: String, message: Message): String? = try {
        if (connectionFactory.isPublisherConfirms) {
            sendConfirmed(exchangeName, routingKey, message)
        } else {
            rabbitTemplate.send(exchangeName, routingKey, message)
            null
        }
    } catch (ex: Exception) {
        ex.message ?: ex.javaClass.simpleName
    }

    /**
     * Send the message, and wait for the broker's confirmation.
     * @return the reason if the broker rejected or returned the message, `null` if it was sent successfully
     */
    private fun sendConfirmed(exchangeName: String, routingKey: String, message: Message): String? {
        check(connectionFactory.isPublisherConfirms) { "Publisher confirms disabled" }

        val correlation = CorrelationData()
        rabbitTemplate.send(exchangeName, routingKey, message, correlation)

        val confirm = try {
            val timeoutMillis = rabbitConfig.publishConfirmTimeout.toMillis()
            correlation.future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return "confirm-timeout"
        }

        return when {
            !confirm.ack -> "rejected: ${confirm.reason() ?: "nack"}"
            correlation.returned != null -> "unroutable: ${correlation.returned?.replyText}"
            else -> null // success
        }
    }

    override fun enqueueCancel(taskHandle: UUID, user: BackgroundTaskUserDescriptor): Boolean {
        return sendAndReceiveTaskControl(taskHandle, user, TaskControlOperation.Cancel)
    }

    override fun checkAvailable(): Boolean {
        return try {
            connectionFactory.createConnection().use { it.isOpen }
        } catch (ex: Exception) {
            log.warn("Exception checking Rabbit connection factory: {}", ex.message, ex)
            false
        }
    }

    override fun isKnownCategory(categoryName: String): Boolean {
        return categoryTaskLaunchQueues.containsKey(categoryName)
    }

    override fun registerWorker(worker: BackgroundTaskWorker) {
        if (taskLaunchQueueListeners.containsKey(worker)) {
            return // already registered
        }

        val workerListenToQueues = categoryTaskLaunchQueues
            .filterKeys { category -> worker.acceptsCategory(category) }
            .values

        val workerListener = listenerFactory.createListenerContainer()
        workerListener.setAutoDeclare(false)
        workerListener.setPrefetchCount(worker.maxConcurrentTasks.coerceAtLeast(1))
        workerListener.setApplicationContext(applicationContext)
        workerListener.setQueues(*workerListenToQueues.toTypedArray())
        workerListener.setMessageListener(
            ChannelAwareMessageListener { message, channel ->
                handleTaskLaunchMessage(message, channel!!, worker)
            },
        )

        taskLaunchQueueListeners[worker] = workerListener
        workerListener.start()
    }

    override fun unregisterWorker(worker: BackgroundTaskWorker) {
        // Stopping the consumers closes their channels: The broker re-queues the launch messages of tasks still being
        // executed by the worker, and redelivers them to other workers, which skip them if they've been started.
        // Task-control bindings are kept until the tasks have finished, so they can still be canceled.
        val listener = taskLaunchQueueListeners.remove(worker)
        listener?.safeStop()
    }

    private fun <R : Any> sendAndReceiveTaskControl(
        taskHandle: UUID,
        user: BackgroundTaskUserDescriptor,
        operation: TaskControlOperation<R>,
    ): R {
        val exchangeName = exchange!!.name
        val routingKey = taskControlRoutingKey(taskHandle)

        // Send task control message
        val taskControlBody = TaskControlBody(taskHandle, user, operation)
        val taskControlMessage = createJsonMessage(taskControlBody)
        val taskControlResponseMessage = try {
            rabbitTemplate.sendAndReceive(exchangeName, routingKey, taskControlMessage)
        } catch (_: AmqpMessageReturnedException) {
            // With publisher returns, a message is returned immediately if no worker is bound to the task
            throw BackgroundTaskQueue.TaskControlFailed(taskHandle, reason = "no-worker-bound")
        }
        if (taskControlResponseMessage == null) {
            throw BackgroundTaskQueue.TaskControlFailed(taskHandle, reason = "response-timeout")
        }

        // Process task control response
        val taskControlResponse = taskControlResponseMessage.readJsonBody<TaskControlResponse>()
        return when {
            taskControlResponse.taskHandle != taskHandle ->
                throw BackgroundTaskQueue.TaskControlFailed(taskHandle, reason = "invalid-response-task-handle")

            !taskControlResponse.responseOkay ->
                throw BackgroundTaskQueue.TaskControlFailed(taskHandle, reason = "response-not-okay")

            else -> objectMapper.treeToValue(taskControlResponse.responseData, operation.returnType)
        }
    }

    private inline fun createJsonMessage(body: Any, applyProps: MessageProperties.() -> Unit = { }): Message {
        val body = objectMapper.writeValueAsBytes(body)
        val props = MessageProperties()
        props.contentType = MessageProperties.CONTENT_TYPE_JSON
        props.contentEncoding = Charsets.UTF_8.name()
        props.apply(applyProps)
        return Message(body, props)
    }

    private fun taskControlRoutingKey(taskHandle: UUID): String {
        val prefix = rabbitConfig.taskControlBinding
        return "$prefix.$taskHandle"
    }

    //#endregion
    //#region Bean initialization

    private fun initializeListener() {
        listenerExecutor = Executors.newVirtualThreadPerTaskExecutor()
        listenerFactory = DirectRabbitListenerContainerFactory()
        listenerFactory.setTaskExecutor(listenerExecutor)
        listenerFactory.setConnectionFactory(connectionFactory)
        listenerFactory.setApplicationContext(applicationContext)
        listenerFactory.setAcknowledgeMode(AcknowledgeMode.MANUAL)
    }

    private fun initializeTemplate() {
        rabbitTemplate = RabbitTemplate(connectionFactory)
        rabbitTemplate.setConnectionFactory(connectionFactory)
        rabbitTemplate.setApplicationContext(applicationContext)
        rabbitTemplate.setReplyTimeout(rabbitConfig.waitForReplyTimeout.toMillis())

        if (connectionFactory.isPublisherConfirms) {
            // Unroutable messages are returned (instead of dropped) if publisher returns are enabled
            rabbitTemplate.setMandatory(connectionFactory.isPublisherReturns)
        } else {
            log.warn(
                """
                RabbitMQ BackgroundTask queue:
                Publisher confirms are disabled (enable with spring.rabbitmq.publisher-confirm-type=correlated),
                so launch messages rejected by the broker (e.g. if a task queue is full)
                are lost without failing their tasks
                """.trimIndent(),
            )
        }

        rabbitAdmin = RabbitAdmin(rabbitTemplate)
        rabbitAdmin.setApplicationContext(applicationContext)
        // Redeclare the queues, exchanges and bindings declared by this instance when the connection is re-established
        // (registered on the connection factory by afterPropertiesSet), as they aren't declared as context beans
        rabbitAdmin.isRedeclareManualDeclarations = true
    }

    /**
     * Check that the broker supports delayed messages in quorum queues (RabbitMQ 4.4 or later), by the version it
     * announced on connection: Fail if it is older, warn if it is unknown (e.g. another broker product)
     */
    private fun checkBrokerVersion() {
        val serverProperties = connectionFactory.createConnection().use { it.delegate.serverProperties }
        val product = serverProperties["product"]?.toString()
        val version = serverProperties["version"]?.toString()

        when (brokerSupportsDelayedMessages(version)) {
            true -> log.info("BackgroundTask queue broker: {} {}", product, version)

            false -> throw IllegalStateException(
                "RabbitMQ BackgroundTask queue requires RabbitMQ $MIN_BROKER_VERSION or later " +
                        "(delayed messages in quorum queues), but the broker is $product $version",
            )

            null -> log.warn(
                "RabbitMQ BackgroundTask queue requires RabbitMQ {} or later (delayed messages in quorum queues), " +
                        "but the broker version is unknown: {} {}",
                MIN_BROKER_VERSION, product, version,
            )
        }
    }

    private fun initializeExchange() {
        // Declare exchange
        exchange = DirectExchange(rabbitConfig.exchange, /* durable = */ true, /* autoDelete = */ false).also {
            log.info("Initializing BackgroundTask exchange: ${it.name}")
            rabbitAdmin.declareExchange(it)
        }
    }

    private fun initializeDeadLetterQueue() {
        // Declare dead-letter exchange and queue, receiving rejected task-launch messages of all categories
        val deadLetterExchange = DirectExchange(
            rabbitConfig.deadLetterExchange,
            /* durable = */ true,
            /* autoDelete = */ false,
        )
        val deadLetterQueue = Queue(rabbitConfig.deadLetterTaskLaunchQueue, /* durable = */ true)
        val deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(deadLetterQueue.name)

        log.info("Initializing BackgroundTask dead-letter queue: ${deadLetterQueue.name}")
        rabbitAdmin.declareExchange(deadLetterExchange)
        rabbitAdmin.declareQueue(deadLetterQueue)
        rabbitAdmin.declareBinding(deadLetterBinding)
    }

    private fun initializeWorkerControlQueue() {
        // Declare worker-control queue
        val prefix = rabbitConfig.workerControlQueue
        val namingStrategy = Base64UrlNamingStrategy("$prefix.")
        workerControlQueue = AnonymousQueue(namingStrategy).also { queue ->
            log.info("Initializing BackgroundTask worker-control queue: ${queue.name}")
            rabbitAdmin.declareQueue(queue) // Disposed automatically when channel is closed
        }

        // Listen to worker-control queue
        // Control messages are acknowledged automatically, and never requeued, as they are only relevant to this instance
        workerControlListener = listenerFactory.createListenerContainer().also { listener ->
            listener.setQueues(workerControlQueue!!)
            listener.setMessageListener(::handleTaskControlMessage)
            listener.acknowledgeMode = AcknowledgeMode.AUTO
            listener.setDefaultRequeueRejected(false)
            listener.start()
        }
    }

    private fun initializeTaskLaunchQueues() {
        // Initialize "default" category if no categories were configured, otherwise initialize all configured categories
        categoryConfigs
            .ifEmpty { mapOf(BackgroundTaskRequest.DEFAULT_CATEGORY to TaskCategoryProps(/* defaults */)) }
            .forEach { (categoryName, category) -> initializeCategoryTaskLaunchQueue(categoryName, category) }
    }

    private fun initializeCategoryTaskLaunchQueue(categoryName: String, category: TaskCategoryProps) {
        val prefix = rabbitConfig.taskLaunchQueue
        val queueName = "$prefix.$categoryName"
        val queueArgs = rabbitQueueArguments(category, rabbitConfig)

        // Quorum queues are durable, and neither exclusive nor auto-deleted
        val queue = Queue(queueName, /* durable = */ true, /* exclusive = */ false, /* autoDelete = */ false, queueArgs)
        categoryTaskLaunchQueues[categoryName] = queue

        // Declare and bind queue to exchange
        log.info("Initializing BackgroundTask category '${categoryName}' task-launch queue: ${queue.name}")
        try {
            rabbitAdmin.declareQueue(queue)
        } catch (ex: AmqpException) {
            // E.g. PRECONDITION_FAILED: The queue exists with another type or arguments
            throw IllegalStateException(
                "Declaring BackgroundTask task-launch queue '$queueName' as quorum queue failed: " +
                        "If it exists with another type or arguments, drain and delete it to redeclare it",
                ex,
            )
        }
        rabbitAdmin.declareBinding(
            BindingBuilder.bind(queue).to(exchange!!).with(queue.name).noargs(),
        )
    }

    //#endregion
    //#region Listener callback methods

    /**
     * Receive task-launch message from task-category-launch queue
     */
    private fun handleTaskLaunchMessage(taskLaunchMessage: Message, channel: Channel, worker: BackgroundTaskWorker) {
        val messageProps = taskLaunchMessage.messageProperties
        val deliveryTag = messageProps.deliveryTag

        // Deserialize task-launch body; reject message if it can't be read, e.g. if the request class is unknown:
        // Fail the task (if its handle can be read), then dead-letter the message
        val (task, user) = try {
            taskLaunchMessage.readJsonBody<LaunchTaskBody>()
        } catch (ex: Exception) {
            log.error(
                "Rejected unreadable task-launch message (worker = '{}'): {}",
                worker.workerHandle, ex.message, ex,
            )
            bestEffortReadTaskHandle(taskLaunchMessage)?.let { taskHandle ->
                worker.reject(taskHandle, BackgroundTaskQueue.TaskLaunchRejected("unreadable-message", ex))
            }
            channel.basicNack(deliveryTag, /* multiple = */ false, /* requeue = */ false)
            return
        }

        // Bind task-handle to worker-control queue (unless bound by another delivery of the task already), which means
        // the worker assumes responsibility for the task, then submit task-request to worker
        var assigned = false
        val future = try {
            taskAssignments.compute(task.taskHandle) { _, assignment ->
                assignment?.copy(workers = assignment.workers + worker)
                    ?: TaskAssignment(declareTaskControlBinding(task), listOf(worker))
            }
            assigned = true
            worker.submit(task, user)
        } catch (ex: Exception) {
            log.error(
                "Submitting task '{}' to worker '{}' failed: {}",
                task.taskHandle, worker.workerHandle, ex.message, ex,
            )
            CompletableFuture.failedFuture(ex)
        }

        // Register cleanup for task execution
        @Suppress("MISSING_DEPENDENCY_IN_INFERRED_TYPE_ANNOTATION_WARNING")
        future.whenComplete { outcome, error ->
            try {
                // The future completed exceptionally only if the task couldn't be launched (failures during the
                // execution are recorded with the task): Fail a task that hasn't been started before dead-lettering its
                // message, or no-op if it was started in the meantime
                if (error != null) {
                    val cause = error as? Exception ?: RuntimeException(error)
                    worker.reject(task.taskHandle, BackgroundTaskQueue.TaskLaunchRejected("launch-failed", cause))
                }

                // The task waits for its retry: Enqueue it before acknowledging the launch message, so the task is
                // redelivered (instead of being lost) if this instance crashes in between
                when (outcome) {
                    null, ExecutionOutcome.Executed -> Unit // done
                    is ExecutionOutcome.Retry -> enqueueRetry(
                        descriptor = task,
                        user = user,
                        retryDelay = outcome.delay,
                        previousWorker = worker,
                    )
                }

                // Confirm task completion to channel
                acknowledgeTaskCompletion(channel, deliveryTag, error)
            } catch (ex: Exception) {
                log.warn(
                    "Acknowledging task '{}' failed: {}",
                    task.taskHandle, ex.message, ex,
                )
            } finally {
                // Release this delivery, removing the task-control binding when it was the last one
                if (assigned) {
                    releaseTaskAssignment(task, worker)
                }
            }
        }
    }

    /** Release a delivery of the task to the worker, removing the task-control binding when it was the last one */
    private fun releaseTaskAssignment(task: BackgroundTaskDescriptor, worker: BackgroundTaskWorker) {
        taskAssignments.computeIfPresent(task.taskHandle) { _, assignment ->
            val remainingWorkers = assignment.workers - worker // removes this delivery only
            if (remainingWorkers.isNotEmpty()) {
                assignment.copy(workers = remainingWorkers)
            } else {
                removeTaskControlBinding(task, worker, assignment.binding)
                null // remove assignment
            }
        }
    }

    /** Read the task handle of an otherwise unreadable task-launch message, `null` if it can't be read either */
    private fun bestEffortReadTaskHandle(taskLaunchMessage: Message): UUID? = try {
        objectMapper.readTree(taskLaunchMessage.body)
            ?.get(LaunchTaskBody::task.name)
            ?.get(BackgroundTaskDescriptor::taskHandle.name)
            ?.takeIf { it.isString }
            ?.let { UUID.fromString(it.asString()) }
    } catch (_: Exception) {
        null // give up
    }

    private fun acknowledgeTaskCompletion(channel: Channel, deliveryTag: Long, error: Throwable?) {
        // Send ACK (task executed, successfully or not, or its retry enqueued) or NACK (task couldn't be launched)
        if (error == null) channel.basicAck(deliveryTag, /* multiple = */ false)
        else channel.basicNack(deliveryTag, /* multiple = */ false, /* requeue = */ false)
    }

    private fun declareTaskControlBinding(task: BackgroundTaskDescriptor): Binding {
        val routingKey = taskControlRoutingKey(task.taskHandle)
        val binding = BindingBuilder.bind(workerControlQueue!!).to(exchange!!).with(routingKey).noargs()
        rabbitAdmin.declareBinding(binding)
        return binding
    }

    private fun removeTaskControlBinding(
        task: BackgroundTaskDescriptor,
        worker: BackgroundTaskWorker,
        binding: Binding,
    ) {
        try {
            rabbitAdmin.removeBinding(binding)
        } catch (ex: Exception) {
            val taskHandle = task.taskHandle
            val workerHandle = worker.workerHandle
            log.warn(
                "Failed to remove task-control binding (task = '{}', worker = '{}'): {}",
                taskHandle, workerHandle, ex.message, ex,
            )
        }
    }

    /**
     * Receive task-control message from worker-control queue
     */
    private fun handleTaskControlMessage(taskControlMessage: Message) {
        // Deserialize task-control body from message
        val body = try {
            taskControlMessage.readJsonBody<TaskControlBody>()
        } catch (ex: Exception) {
            log.error("Discarded unreadable task-control message: {}", ex.message, ex)
            return
        }

        // Lookup worker responsible for task, execute control operation
        val responseContent = try {
            // Operations apply to the latest delivery of the task (e.g. the resumed execution)
            val responsibleWorker = taskAssignments[body.taskHandle]?.workers?.lastOrNull()
            responsibleWorker?.let {
                val context = TaskControlContext(body.taskHandle, body.user, responsibleWorker)
                body.operation.execute(context)
            }
        } catch (ex: Exception) {
            log.error(
                "Task-control operation {} for task '{}' failed: {}",
                body.operation.javaClass.simpleName, body.taskHandle, ex.message, ex,
            )
            null
        }

        // Send reply with control operation response
        val responseOkay = responseContent != null
        val responseData = objectMapper.valueToTree<JsonNode>(responseContent)
        replyToMessage(taskControlMessage) {
            TaskControlResponse(body.taskHandle, responseOkay, responseData)
        }
    }

    private inline fun <reified T> Message.readJsonBody(): T {
        return objectMapper.readValue<T>(body)
    }

    private inline fun replyToMessage(receivedMessage: Message, getResponseBody: () -> Any) {
        val receivedProps = receivedMessage.messageProperties
        receivedProps.replyToAddress?.let { replyTo ->
            val responseBody = getResponseBody()
            val responseMessage = createJsonMessage(responseBody) {
                correlationId = receivedProps.correlationId
            }

            rabbitTemplate.send(
                /* exchange = */ replyTo.exchangeName,
                /* routingKey = */ replyTo.routingKey,
                /* message = */ responseMessage,
            )
        }
    }

    //#endregion
}

//#region Message data structures

/** Body of a task-launch message */
internal data class LaunchTaskBody(
    val task: BackgroundTaskDescriptor,
    val user: BackgroundTaskUserDescriptor,
) : Serializable

/** Body of a task-control message */
internal data class TaskControlBody(
    val taskHandle: UUID,
    val user: BackgroundTaskUserDescriptor,
    val operation: TaskControlOperation<*>,
) : Serializable


/** Body of the reply to a task-control message, [responseOkay] if a local worker executed the operation */
internal data class TaskControlResponse(
    val taskHandle: UUID,
    val responseOkay: Boolean,
    val responseData: JsonNode,
) : Serializable

//#endregion
//#region Task control operations

/** Context of a task-control operation, executed on the instance of the worker executing the task */
internal class TaskControlContext(
    val taskHandle: UUID,
    val user: BackgroundTaskUserDescriptor,
    val worker: BackgroundTaskWorker,
) {
    fun cancelWorkerTask() = worker.cancel(taskHandle, user)
}

/** Operation sent in a task-control message, executed by the instance of the worker executing the task */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@operation")
internal sealed interface TaskControlOperation<R : Any> {
    /** Type of the operation's response, for deserializing it (not part of the message) */
    @get:JsonIgnore
    val returnType: TypeReference<R>

    fun execute(context: TaskControlContext): R

    /** Cancel the task, replying `true` if the worker executes the task */
    @JsonTypeName("cancel")
    data object Cancel : TaskControlOperation<Boolean> {
        override val returnType = jacksonTypeRef<Boolean>()
        override fun execute(context: TaskControlContext): Boolean {
            // The worker assignment is kept until the execution has finished, see handleTaskLaunchMessage
            return context.cancelWorkerTask()
        }
    }
}

//#endregion
//#region Helper functions

/**
 * Check if the RabbitMQ broker [version] (e.g. `4.4.0`, `4.4.0-rc.1`, `4.5.0+12.gabc`) supports delayed messages,
 * `null` if the version is unknown
 */
internal fun brokerSupportsDelayedMessages(version: String?): Boolean? {
    val match = version?.let { VERSION_PATTERN.find(it.trim()) } ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    return major > MIN_BROKER_VERSION.major || (major == MIN_BROKER_VERSION.major && minor >= MIN_BROKER_VERSION.minor)
}

private val VERSION_PATTERN = Regex("^(\\d+)\\.(\\d+)")

/** Delivery time ([DELIVERY_TIME_HEADER]) of a delayed message, due after the [delay] (saturated) */
private fun deliveryTime(delay: Duration): Long {
    val now = System.currentTimeMillis()
    val delayMillis = try {
        delay.toMillis()
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }
    return if (delayMillis > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis
}

/**
 * Create RabbitMQ queue arguments (quorum queue) for the given task-category config props.
 * Note: Quorum queue is required to support "x-opt-delivery-time" for delayed messaging
 */
private fun rabbitQueueArguments(category: TaskCategoryProps, rabbitConfig: RabbitMqProps) = buildMap {
    put("x-queue-type", "quorum")
    put("x-dead-letter-exchange", rabbitConfig.deadLetterExchange)
    put("x-dead-letter-routing-key", rabbitConfig.deadLetterTaskLaunchQueue)
    category.maxQueueLength?.let { maxQueueLength ->
        put("x-max-length", maxQueueLength)
        put("x-overflow", "reject-publish")
    }
}

/** Safely [stop] the container, catching Exceptions that may be thrown and issue a log warning instead */
private fun DirectMessageListenerContainer.safeStop() {
    try {
        stop()
    } catch (ex: Exception) {
        log.warn("Exception while closing DirectMessageListenerContainer: {}", ex.message, ex)
    }
}

//#endregion
