package de.kiwi.backgroundTask.infrastructure

import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import de.kiwi.backgroundTask.infrastructure.BackgroundTaskQueueHarness.Companion.FAILING_ID
import de.kiwi.backgroundTask.infrastructure.BackgroundTaskQueueHarness.Companion.createTaskDescriptor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.*
import kotlin.time.Duration.Companion.seconds

class LocalBackgroundTaskQueueTest {
    private lateinit var queue: LocalBackgroundTaskQueue
    private lateinit var harness: BackgroundTaskQueueHarness

    @BeforeEach
    fun setUp() {
        queue = LocalBackgroundTaskQueue()
        harness = BackgroundTaskQueueHarness(queue)
    }

    @AfterEach
    fun tearDown() {
        harness.close()
        queue.destroy()
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
    fun unregisterWorker() {
        TestBackgroundTaskWorker(
            name = "other",
            acceptedCategories = setOf(BackgroundTaskQueueHarness.SPECIAL_CATEGORY),
            messaging = harness.messaging,
            maxConcurrentTasks = 2,
        ).use {
            harness.unregisterWorkerTest(it)
        }
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
    fun failedRetryIsRejected() {
        val descriptor = createTaskDescriptor(9)
        queue.enqueueLaunch(descriptor, harness.user)
        val submission = harness.defaultWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()

        // No worker accepts the task's category anymore when the retry is due: The task is rejected by its worker
        queue.unregisterWorker(harness.defaultWorker)
        submission.complete(ExecutionOutcome.Retry(Duration.ofMillis(100)))
        awaitCondition { harness.defaultWorker.rejections.containsKey(descriptor.taskHandle) }

        val rejection = harness.defaultWorker.rejections.getValue(descriptor.taskHandle)
        assertEquals("retry-failed", (rejection as BackgroundTaskQueue.TaskLaunchRejected).args["reason"])
        assertEquals(1, harness.defaultWorker.submitAttempts(descriptor.taskHandle))
    }

    @Test
    fun retryAfterDestroyIsRejected() {
        val descriptor = createTaskDescriptor(10)
        queue.enqueueLaunch(descriptor, harness.user)
        val submission = harness.defaultWorker.awaitSubmission(descriptor.taskHandle, timeout = 2.seconds).get()

        queue.destroy()
        submission.complete(ExecutionOutcome.Retry(Duration.ZERO))
        assertTrue(harness.defaultWorker.rejections.containsKey(descriptor.taskHandle))
    }

    private fun awaitCondition(timeout: Duration = Duration.ofSeconds(5), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Condition not met within $timeout" }
            Thread.sleep(20)
        }
    }

    @Test
    fun cancelUnassignedTaskReturnsFalse() {
        assertFalse(queue.enqueueCancel(UUID.randomUUID(), harness.user))
    }

    @Test
    fun failedSubmissionIsReportedAndReleased() {
        val descriptor = createTaskDescriptor(FAILING_ID)
        assertThrows<BackgroundTaskQueue.TaskEnqueueFailed> { queue.enqueueLaunch(descriptor, harness.user) }

        // Task is not assigned to the worker anymore
        assertFalse(queue.enqueueCancel(descriptor.taskHandle, harness.user))
        assertTrue(harness.defaultWorker.getCancellations().isEmpty())
    }
}
