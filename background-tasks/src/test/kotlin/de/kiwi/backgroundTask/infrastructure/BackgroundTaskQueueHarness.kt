package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.TestUser
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.io.Closeable
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds

private val log = slf4j<BackgroundTaskQueueHarness>()

/**
 * Test harness for [BackgroundTaskQueue] implementations, with a worker for the `default` and one for the `special`
 * category. Tests shared by all queue implementations are implemented here.
 */
class BackgroundTaskQueueHarness(
    private val queue: BackgroundTaskQueue,
    val messaging: TestBackgroundTaskMessaging = TestBackgroundTaskMessaging(),
    val user: BackgroundTaskUserDescriptor = TestUser("test"),
) : Closeable {
    companion object {
        const val DEFAULT_CATEGORY = BackgroundTaskRequest.DEFAULT_CATEGORY
        const val SPECIAL_CATEGORY = "special"
        const val UNKNOWN_CATEGORY = "unknown"
        const val FAILING_ID = -1

        fun createTaskDescriptor(id: Int, category: String = DEFAULT_CATEGORY): BackgroundTaskDescriptor {
            return BackgroundTaskDescriptor(
                taskHandle = UUID.randomUUID(),
                request = DummyRequest(id, category),
            )
        }
    }

    val defaultWorker = TestBackgroundTaskWorker(
        name = "default",
        acceptedCategories = setOf(DEFAULT_CATEGORY),
        messaging = messaging,
        failSubmit = ::failSubmit,
    )

    val specialWorker = TestBackgroundTaskWorker(
        name = "special",
        acceptedCategories = setOf(SPECIAL_CATEGORY),
        messaging = messaging,
        failSubmit = ::failSubmit,
    )

    private fun failSubmit(descriptor: BackgroundTaskDescriptor): Boolean {
        return (descriptor.request as DummyRequest).id == FAILING_ID
    }

    init {
        log.info("Created BackgroundTaskQueueHarness for implementation: ${queue.javaClass.name}")
        queue.registerWorker(defaultWorker)
        queue.registerWorker(specialWorker)
    }

    override fun close() {
        defaultWorker.close()
        specialWorker.close()
        messaging.clear()
    }

    /** Tasks are submitted to the worker accepting their category, with the launching user */
    fun launchTasksTest() {
        val descriptor1 = createTaskDescriptor(1, DEFAULT_CATEGORY)
        val descriptor2 = createTaskDescriptor(2, DEFAULT_CATEGORY)
        val descriptor3 = createTaskDescriptor(3, SPECIAL_CATEGORY)

        log.info("Launching tasks: $descriptor1, $descriptor2, $descriptor3")
        queue.enqueueLaunch(descriptor1, user)
        queue.enqueueLaunch(descriptor2, user)
        queue.enqueueLaunch(descriptor3, user)

        val submission1 = defaultWorker.awaitSubmission(descriptor1.taskHandle, timeout = 2.seconds).get()
        // Worker executes one task at a time: complete the first to receive the second
        submission1.complete()
        val submission2 = defaultWorker.awaitSubmission(descriptor2.taskHandle, timeout = 2.seconds).get()
        val submission3 = specialWorker.awaitSubmission(descriptor3.taskHandle, timeout = 2.seconds).get()

        assertEquals(descriptor1, submission1.descriptor)
        assertEquals(descriptor2, submission2.descriptor)
        assertEquals(descriptor3, submission3.descriptor)
        assertEquals(user, submission1.user)
        assertTrue(specialWorker.getSubmissions().none { it.descriptor.request.category != SPECIAL_CATEGORY })
        assertTrue(defaultWorker.getSubmissions().none { it.descriptor.request.category != DEFAULT_CATEGORY })
    }

    /** A running task is canceled by the worker executing it */
    fun cancelTaskTest() {
        val descriptor = createTaskDescriptor(0, DEFAULT_CATEGORY)

        log.info("Launching $descriptor task to cancel it")
        queue.enqueueLaunch(descriptor, user)

        val submission = defaultWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()
        messaging.awaitMessage<BackgroundTaskMessage.StatusChanged.Started>(
            timeout = Duration.ofSeconds(2),
            taskHandle = descriptor.taskHandle,
        ).get()

        log.info("Task submitted and started, will now cancel")

        val canceled = queue.enqueueCancel(descriptor.taskHandle, user)
        val cancellation = defaultWorker.awaitCancellation(descriptor.taskHandle, timeout = 2.seconds).get()
        messaging.awaitMessage<BackgroundTaskMessage.StatusChanged.Canceled>(
            timeout = Duration.ofSeconds(2),
            taskHandle = descriptor.taskHandle,
        ).get()

        assertTrue(canceled)
        assertEquals(submission, cancellation.submission)
        assertEquals(user, cancellation.user)
        assertTrue(specialWorker.getCancellations().isEmpty())
    }

    /** Tasks of a category without queue or worker are rejected */
    fun unknownCategoryTest() {
        assertTrue(queue.isKnownCategory(DEFAULT_CATEGORY))
        assertTrue(queue.isKnownCategory(SPECIAL_CATEGORY))
        assertFalse(queue.isKnownCategory(UNKNOWN_CATEGORY))
        assertThrows<BackgroundTaskQueue.UnknownTaskCategory> {
            queue.enqueueLaunch(createTaskDescriptor(4, UNKNOWN_CATEGORY), user)
        }
    }

    /**
     * An unregistered worker doesn't receive new tasks, but its running task can still be canceled.
     * The [otherWorker] should accept two tasks at once, as a queue may redeliver the running task to it.
     */
    fun unregisterWorkerTest(otherWorker: TestBackgroundTaskWorker) {
        val running = createTaskDescriptor(5, SPECIAL_CATEGORY)
        queue.enqueueLaunch(running, user)
        specialWorker.awaitSubmission(running.taskHandle, timeout = 2.seconds).get()

        queue.unregisterWorker(specialWorker)
        assertTrue(queue.enqueueCancel(running.taskHandle, user))
        queue.registerWorker(otherWorker)

        val following = createTaskDescriptor(6, SPECIAL_CATEGORY)
        queue.enqueueLaunch(following, user)
        otherWorker.awaitSubmission(following.taskHandle, timeout = 2.seconds).get()
        assertTrue(specialWorker.getSubmissions().none { it.descriptor == following })
    }

    /**
     * A task waiting for its retry is submitted again after the retry delay
     * (by the worker that executed it, as it is the only worker accepting its category)
     */
    fun retryTaskTest(delay: Duration) {
        val descriptor = createTaskDescriptor(8, SPECIAL_CATEGORY)
        queue.enqueueLaunch(descriptor, user)
        val submission = specialWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()

        val retryRequested = System.nanoTime()
        submission.complete(ExecutionOutcome.Retry(delay))
        specialWorker.awaitSubmitAttempts(descriptor.taskHandle, count = 2, timeout = 5.seconds)
        val retried = Duration.ofNanos(System.nanoTime() - retryRequested)

        assertTrue(retried >= delay) { "Retried after $retried, before the delay $delay" }
        assertEquals(listOf(descriptor, descriptor), specialWorker.getSubmissions().map { it.descriptor })
        assertEquals(user, specialWorker.getSubmissions().last().user)
        assertTrue(specialWorker.rejections.isEmpty()) { "${specialWorker.rejections}" }

        // The retried task can be canceled
        assertTrue(queue.enqueueCancel(descriptor.taskHandle, user))
    }

    data class DummyRequest(val id: Int, val categoryName: String) : BackgroundTaskRequest() {
        override val category get() = categoryName
    }
}
