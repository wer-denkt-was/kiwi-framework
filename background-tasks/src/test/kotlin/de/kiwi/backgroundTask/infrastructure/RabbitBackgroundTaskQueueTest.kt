package de.kiwi.backgroundTask.infrastructure

import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties
import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskCategory
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import de.kiwi.backgroundTask.infrastructure.BackgroundTaskQueueHarness.Companion.DEFAULT_CATEGORY
import de.kiwi.backgroundTask.infrastructure.BackgroundTaskQueueHarness.Companion.FAILING_ID
import de.kiwi.backgroundTask.infrastructure.BackgroundTaskQueueHarness.Companion.SPECIAL_CATEGORY
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.rabbitmq.RabbitMQContainer
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds

private const val LIMITED_CATEGORY = "limited"

@Testcontainers
class RabbitBackgroundTaskQueueTest {

    companion object {
        @JvmStatic
        @Container
        val container = RabbitMQContainer("rabbitmq:4.4")
    }

    private val rabbitFixture = RabbitBackgroundTaskQueueFixture()

    private lateinit var rabbitConfig: KiwiBackgroundTaskProperties.TaskQueue.RabbitMq
    private lateinit var queue: RabbitBackgroundTaskQueue
    private lateinit var harness: BackgroundTaskQueueHarness

    @BeforeEach
    fun setUp() {
        rabbitFixture.setUp(container)

        // Separate exchange and queues for each test, so no messages are left over from other tests
        val uniqueTestPrefix = UUID.randomUUID().toString()
        rabbitConfig = KiwiBackgroundTaskProperties.TaskQueue.RabbitMq(
            exchange = "$uniqueTestPrefix.background_tasks",
            taskLaunchQueue = "$uniqueTestPrefix.launch_task",
            workerControlQueue = "$uniqueTestPrefix.worker_control",
            deadLetterExchange = "$uniqueTestPrefix.background_tasks_dlx",
            deadLetterTaskLaunchQueue = "$uniqueTestPrefix.launch_task_dlq",
            waitForReplyTimeout = Duration.ofSeconds(2),
        )

        queue = rabbitFixture.createQueue(rabbitConfig)
        harness = BackgroundTaskQueueHarness(queue, TestBackgroundTaskMessaging())
    }

    @AfterEach
    fun tearDown() {
        harness.close()
        rabbitFixture.tearDown()
    }

    @Test
    fun launchTask() {
        harness.launchTasksTest()
    }

    @Test
    fun cancelTask() {
        harness.cancelTaskTest()
    }

    @Test
    fun unknownCategory() {
        harness.unknownCategoryTest()
    }

    @Test
    fun retryTask() {
        harness.retryTaskTest(delay = Duration.ofMillis(300))
    }

    @Test
    fun retryTaskWithoutDelay() {
        harness.retryTaskTest(delay = Duration.ZERO)
    }

    @Test
    fun retryIsDelayed() {
        val descriptor = BackgroundTaskQueueHarness.createTaskDescriptor(id = 11)
        queue.enqueueLaunch(descriptor, harness.user)
        val submission = harness.defaultWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()

        // The retry waits in the task-launch queue as a delayed message, until it is due
        val retryEnqueuedNanos = System.nanoTime()
        submission.complete(ExecutionOutcome.Retry(Duration.ofSeconds(2)))
        Thread.sleep(1000)
        assertEquals(1, harness.defaultWorker.submitAttempts(descriptor.taskHandle))

        // Once due, it is delivered again
        harness.defaultWorker.awaitSubmitAttempts(descriptor.taskHandle, count = 2, timeout = 10.seconds)
        val elapsed = Duration.ofNanos(System.nanoTime() - retryEnqueuedNanos)
        assertTrue(elapsed >= Duration.ofMillis(1900)) { "Retried after $elapsed" }
        assertEquals(0L, rabbitFixture.countMessages(rabbitConfig.deadLetterTaskLaunchQueue))
        assertTrue(harness.defaultWorker.rejections.isEmpty())
    }

    @Test
    fun retryIsNotHeldBackByLongerRetries() {
        val longDelayed = BackgroundTaskQueueHarness.createTaskDescriptor(id = 12)
        val shortDelayed = BackgroundTaskQueueHarness.createTaskDescriptor(id = 13)
        queue.enqueueLaunch(longDelayed, harness.user)
        harness.defaultWorker.awaitSubmission(longDelayed.taskHandle, timeout = 2.seconds).get()
            .complete(ExecutionOutcome.Retry(Duration.ofMinutes(1)))

        // The retry enqueued later, but due earlier, is delivered first
        queue.enqueueLaunch(shortDelayed, harness.user)
        harness.defaultWorker.awaitSubmission(shortDelayed.taskHandle, timeout = 2.seconds).get()
            .complete(ExecutionOutcome.Retry(Duration.ofMillis(300)))
        harness.defaultWorker.awaitSubmitAttempts(shortDelayed.taskHandle, count = 2, timeout = 5.seconds)
        assertEquals(1, harness.defaultWorker.submitAttempts(longDelayed.taskHandle))
    }

    @Test
    fun launchQueueOfAnotherTypeFailsInitialization() {
        // E.g. a classic queue declared by an earlier version
        rabbitFixture.declareClassicQueue("${rabbitConfig.taskLaunchQueue}.$LIMITED_CATEGORY")
        val exception = assertThrows<IllegalStateException> {
            rabbitFixture.createQueue(rabbitConfig, mapOf(LIMITED_CATEGORY to TaskCategory()))
        }
        assertTrue(exception.message!!.contains("quorum queue")) { exception.message }
    }

    @Test
    fun cancelUnassignedTaskFails() {
        // No worker is bound to the task-control routing key:
        // The message is returned (with publisher returns), otherwise no worker confirms the signal before the timeout
        assertThrows<BackgroundTaskQueue.TaskControlFailed> {
            val unknownHandle = UUID.randomUUID()
            queue.enqueueCancel(unknownHandle, harness.user)
        }
    }

    @Test
    fun checkAvailable() {
        assertTrue(queue.checkAvailable())
    }

    @Test
    fun failedSubmissionIsRejectedWithoutRequeue() {
        val failing = BackgroundTaskQueueHarness.createTaskDescriptor(id = FAILING_ID)
        val following = BackgroundTaskQueueHarness.createTaskDescriptor(id = 1)
        queue.enqueueLaunch(failing, harness.user)
        queue.enqueueLaunch(following, harness.user)

        // The following task is still delivered, and the failing task is not redelivered
        harness.defaultWorker.awaitSubmission(following.taskHandle, timeout = 2.seconds).get().complete()
        Thread.sleep(500)
        assertEquals(1, harness.defaultWorker.submitAttempts(failing.taskHandle))

        // The failing task has been rejected (failed by the worker), not left unacknowledged, and dead-lettered
        assertTrue(harness.defaultWorker.rejections.containsKey(failing.taskHandle))
        val remainingMessageCount = rabbitFixture.stopConsumersAndCountMessages(rabbitConfig, DEFAULT_CATEGORY)
        assertEquals(0L, remainingMessageCount)
        assertEquals(1L, rabbitFixture.countMessages(rabbitConfig.deadLetterTaskLaunchQueue))
    }

    @Test
    fun unreadableMessageIsRejectedWithoutRequeue() {
        @Language("JSON") val unreadable = """{ "task": "UNREADABLE" }"""
        rabbitFixture.sendToLaunchQueue(rabbitConfig, DEFAULT_CATEGORY, unreadable)

        // The following task is still delivered, the unreadable message is dropped
        val following = BackgroundTaskQueueHarness.createTaskDescriptor(1)
        queue.enqueueLaunch(following, harness.user)
        harness.defaultWorker.awaitSubmission(following.taskHandle, timeout = 2.seconds).get().complete()
        Thread.sleep(500)

        val remainingMessageCount = rabbitFixture.stopConsumersAndCountMessages(rabbitConfig, DEFAULT_CATEGORY)
        assertEquals(0L, remainingMessageCount)
        assertEquals(1L, rabbitFixture.countMessages(rabbitConfig.deadLetterTaskLaunchQueue))
    }

    @Test
    fun unreadableMessageWithTaskHandleRejectsTask() {
        val taskHandle = UUID.randomUUID()
        @Language("JSON") val unreadable = """
            {
              "user": { },
              "task": {
                "taskHandle": "$taskHandle",
                "request": { "@class": "com.example.MissingRequest" }
              }
            }
        """.trimIndent()

        rabbitFixture.sendToLaunchQueue(rabbitConfig, DEFAULT_CATEGORY, unreadable)

        // The task of the unreadable message is failed by the worker, before the message is dead-lettered
        awaitCondition { harness.defaultWorker.rejections.containsKey(taskHandle) }
        val rejection = harness.defaultWorker.rejections.getValue(taskHandle) as BackgroundTaskQueue.TaskLaunchRejected
        assertEquals("unreadable-message", rejection.args["reason"])
    }

    @Test
    fun duplicateDeliveryKeepsTaskControl() {
        // A worker accepting two tasks at once receives the same task twice (e.g. redelivered)
        TestBackgroundTaskWorker(
            name = "duplicate",
            acceptedCategories = setOf(SPECIAL_CATEGORY),
            messaging = harness.messaging,
            maxConcurrentTasks = 2,
        ).use { worker ->
            queue.unregisterWorker(harness.specialWorker)
            queue.registerWorker(worker)

            val descriptor = BackgroundTaskQueueHarness.createTaskDescriptor(id = 7, category = SPECIAL_CATEGORY)
            queue.enqueueLaunch(descriptor, harness.user)
            queue.enqueueLaunch(descriptor, harness.user)
            awaitCondition {
                worker.submitAttempts(descriptor.taskHandle) == 2
            }

            // The duplicate finishes, while the first delivery keeps running: It can still be canceled
            worker.getSubmissions().last().complete()
            Thread.sleep(500)
            assertTrue(queue.enqueueCancel(descriptor.taskHandle, harness.user))
        }
    }

    @Test
    fun declarationsAreRecoveredAfterConnectionLoss() {
        val running = BackgroundTaskQueueHarness.createTaskDescriptor(id = 1)
        queue.enqueueLaunch(running, harness.user)
        harness.defaultWorker.awaitSubmission(running.taskHandle, timeout = 2.seconds).get()

        // Losing the connection deletes the exclusive worker-control queue, including the task-control bindings
        rabbitFixture.resetConnection()

        // Once reconnected, the worker-control queue and the binding of the running task have been redeclared,
        // so the cancellation reaches the worker (the listeners restart their consumers asynchronously: Retry
        // sending it, as a cancellation may be delivered after its request has timed out)
        val cancellation = harness.defaultWorker.awaitCancellation(running.taskHandle, timeout = 30.seconds)
        awaitCondition(Duration.ofSeconds(30)) {
            runCatching { queue.enqueueCancel(running.taskHandle, harness.user) }
            cancellation.isDone
        }
        cancellation.get()

        // Tasks are still launched (of another category, as the default worker is busy with the redelivered task:
        // Its unacknowledged launch message has been requeued when the connection was lost)
        val following = BackgroundTaskQueueHarness.createTaskDescriptor(id = 2, category = SPECIAL_CATEGORY)
        queue.enqueueLaunch(following, harness.user)
        harness.specialWorker.awaitSubmission(following.taskHandle, timeout = 30.seconds).get()
    }

    private fun awaitCondition(timeout: Duration = Duration.ofSeconds(5), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within $timeout" }
            Thread.sleep(50)
        }
    }

    @Test
    fun unregisterWorker() {
        TestBackgroundTaskWorker(
            name = "other",
            acceptedCategories = setOf(SPECIAL_CATEGORY),
            messaging = harness.messaging,
            maxConcurrentTasks = 2,
        ).use {
            harness.unregisterWorkerTest(it)
        }
    }

    @Test
    fun fullQueueRejectsLaunch() {
        // No worker consumes the limited category, so its queue fills up
        val limitedQueue = rabbitFixture.createQueue(
            rabbitConfig = rabbitConfig,
            additionalCategories = mapOf(
                LIMITED_CATEGORY to TaskCategory(maxQueueLength = 1),
            ),
        )

        val descriptors = (1..3).map { id ->
            BackgroundTaskQueueHarness.createTaskDescriptor(id = id, category = LIMITED_CATEGORY)
        }

        // A quorum queue rejects messages once it exceeds its maximum length, i.e. it holds one more message
        limitedQueue.enqueueLaunch(descriptors[0], harness.user)
        limitedQueue.enqueueLaunch(descriptors[1], harness.user)
        assertThrows<BackgroundTaskQueue.TaskEnqueueFailed> {
            limitedQueue.enqueueLaunch(descriptors[2], harness.user)
        }
        assertEquals(2L, rabbitFixture.countMessages("${rabbitConfig.taskLaunchQueue}.$LIMITED_CATEGORY"))
    }

    @Test
    fun workerReceivesOnlyAsManyTasksAsItExecutesConcurrently() {
        val descriptor1 = BackgroundTaskQueueHarness.createTaskDescriptor(id = 1)
        val descriptor2 = BackgroundTaskQueueHarness.createTaskDescriptor(id = 2)
        queue.enqueueLaunch(descriptor1, harness.user)
        queue.enqueueLaunch(descriptor2, harness.user)
        harness.defaultWorker.awaitSubmission(descriptor1.taskHandle, timeout = 2.seconds).get()

        // The second task is not prefetched by the busy worker, but delivered to a worker registered later
        val otherWorker = TestBackgroundTaskWorker("other", setOf(DEFAULT_CATEGORY), harness.messaging)
        otherWorker.use { otherWorker ->
            val otherQueue = rabbitFixture.createQueue(rabbitConfig)
            otherQueue.registerWorker(otherWorker)
            otherWorker.awaitSubmission(descriptor2.taskHandle, timeout = 2.seconds).get()
            assertEquals(listOf(descriptor1), harness.defaultWorker.getSubmissions().map { it.descriptor })
        }
    }

    @Test
    fun controlMessagesAreAcknowledged() {
        // Cancel more tasks than the default prefetch count:
        // Without acknowledgements, signals stop being delivered
        repeat(260) { index ->
            val descriptor = BackgroundTaskQueueHarness.createTaskDescriptor(index)
            queue.enqueueLaunch(descriptor, harness.user)
            harness.defaultWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()
            assertTrue(queue.enqueueCancel(descriptor.taskHandle, harness.user)) { "Cancel task #$index" }
        }
    }
}
