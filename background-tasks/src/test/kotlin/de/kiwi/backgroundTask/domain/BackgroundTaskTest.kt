package de.kiwi.backgroundTask.domain

import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.backgroundTask.TestUser
import de.kiwi.backgroundTask.TestUserDescriptor
import de.kiwi.backgroundTask.domain.interaction.*
import de.kiwi.backgroundTask.testObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.JsonNodeFactory
import java.time.*

class BackgroundTaskTest {
    private val testClock = Clock.fixed(Instant.parse("2026-11-06T12:00:00Z"), ZoneOffset.UTC)
    private val testNow = LocalDateTime.now(testClock)

    private val objectMapper = testObjectMapper()
    private val leaseDuration = Duration.ofMinutes(1)

    data class ExampleRequest(val message: String) : BackgroundTaskRequest()

    private fun createTask(
        user: BackgroundTaskUserDescriptor = TestUser(username = "tester"),
    ): BackgroundTask {
        val result = BackgroundTask.create(
            taskRequest = ExampleRequest(message = "hello"),
            taskUser = user,
            objectMapper = objectMapper,
            clock = testClock,
        )

        assertEquals(1, result.findAllByType<BackgroundTaskCreated>().size)
        return result.value
    }

    @Test
    fun createdTaskRecordsRequestAndUser() {
        val user = BackgroundTaskUserDescriptor.delegateOf(TestUserDescriptor("tester", userId = 42))
        val task = createTask(user = user)

        assertEquals(BackgroundTaskStatus.Created, task.status)
        assertTrue(task.isNew())
        assertEquals(ExampleRequest::class.java.name, task.taskRequestClassName)
        assertEquals(ExampleRequest("hello"), task.toTaskRequest(objectMapper))
        assertEquals(task.taskHandle, task.toDescriptor(objectMapper).taskHandle)
        assertEquals(testNow, task.createdOn)
        assertEquals(42L, task.createdByUserId)
        assertEquals("tester", task.createdByUsername)
    }

    @Test
    fun anonymousCreatorIsNotRecorded() {
        val anonymous = BackgroundTaskUserDescriptor.delegateOf(UserDescriptor.ANONYMOUS)
        val task = createTask(user = anonymous)
        assertNull(task.createdByUserId)
        assertNull(task.createdByUsername)
    }

    @Test
    fun taskHandlesAreUnique() {
        repeat(1_000) {
            assertNotEquals(
                createTask().taskHandle,
                createTask().taskHandle,
            )
        }
    }

    @Test
    fun completeLifecycle() {
        // 1. Create, launch
        val task = createTask()
        assertTrue(task.launched().value)
        assertEquals(BackgroundTaskStatus.Launched, task.status)

        // 2. Start
        val started = task.started("test-worker", leaseDuration, testClock)
        assertEquals(1, started.findAllByType<BackgroundTaskStarted>().size)
        assertEquals(BackgroundTaskStatus.Running, task.status)
        assertEquals(testNow, task.startedOn)
        assertFalse(task.launched().value)

        // 3. Progress
        assertTrue(task.updateProgress(BackgroundTaskProgress(40), objectMapper))
        assertFalse(task.updateProgress(BackgroundTaskProgress(40), objectMapper))
        assertEquals(40, task.progressPercent)

        // 4. Complete
        val resultValue = JsonNodeFactory.instance.objectNode().put("answer", 42)
        val completed = task.completed(resultValue, objectMapper, testClock)
        assertEquals(1, completed.findAllByType<BackgroundTaskCompleted>().size)
        assertEquals(BackgroundTaskStatus.Completed, task.status)
        assertEquals(testNow, task.completedOn)
        assertEquals(100, task.progressPercent)

        // Details view
        val details = task.toDetails(objectMapper)
        assertEquals(BackgroundTaskStatus.Completed, details.status)
        assertEquals(resultValue, details.result)
        assertNull(details.error)
    }

    @Test
    fun nullResultIsNotStored() {
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        task.completed(result = JsonNodeFactory.instance.nullNode(), objectMapper = objectMapper, clock = testClock)
        assertNull(task.resultJson)
        assertNull(task.toDetails(objectMapper).result)
    }

    @Test
    fun progressRequiresRunningTask() {
        val task = createTask()
        assertThrows<InvalidBackgroundTaskStatus> { task.updateProgress(BackgroundTaskProgress(10), objectMapper) }
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertThrows<IllegalArgumentException> { task.updateProgress(BackgroundTaskProgress(101), objectMapper) }
        assertThrows<IllegalArgumentException> { task.updateProgress(BackgroundTaskProgress(-1), objectMapper) }
    }

    @Test
    fun startBeforeLaunched() {
        // The worker may pick up the task before the service marked it as launched
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertEquals(BackgroundTaskStatus.Running, task.status)
        assertFalse(task.launched().value) // already launched, no change flagged
        assertEquals(BackgroundTaskStatus.Running, task.status) // still running
    }

    @Test
    fun failedTaskRecordsError() {
        val task = createTask()
        task.started(
            workerName = "test-worker",
            leaseDuration = leaseDuration,
            clock = testClock,
        )

        val exception = IllegalStateException("broken")
        val events = task.failed(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            objectMapper = objectMapper,
            clock = testClock,
        )

        assertEquals(1, events.findAllByType<BackgroundTaskFailed>().size)
        assertEquals(BackgroundTaskStatus.Failed, task.status)
        assertEquals(testNow, task.completedOn)

        // Well-known exceptions are rendered with their message
        val error = task.toDetails(objectMapper).error
        assertEquals("illegal-state", error?.message)
        assertEquals(mapOf("reason" to "broken"), error?.arguments)
        assertEquals(IllegalStateException::class.java.name, error?.errorType)
    }

    @Test
    fun failedTaskDoesNotDiscloseUnknownError() {
        val task = createTask()
        task.started(
            workerName = "test-worker",
            leaseDuration = leaseDuration,
            clock = testClock,
        )

        // Unknown exception with sensitive internal data!
        val exception = java.io.FileNotFoundException("/secret/path/config.yaml")
        task.failed(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            objectMapper = objectMapper,
            clock = testClock,
        )

        // Details of other exceptions are not disclosed, only their simple type
        val error = task.toDetails(objectMapper).error
        assertEquals(BackgroundTaskError.UNKNOWN_ERROR, error?.message)
        assertEquals(mapOf("type" to "FileNotFoundException"), error?.arguments)
        assertEquals(DomainException.Unknown::class.java.name, error?.errorType)
    }

    @Test
    fun failedTaskRecordsDomainError() {
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)

        val exception = InvalidBackgroundTaskStatus(BackgroundTaskStatus.Running, reason = "test")
        task.failed(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            objectMapper = objectMapper,
            clock = testClock,
        )

        val error = task.toDetails(objectMapper).error
        assertEquals("invalid-background-task-status", error?.message)
        assertEquals(mapOf("reason" to "test", "status" to "Running"), error?.arguments)
        assertEquals(InvalidBackgroundTaskStatus::class.java.name, error?.errorType)
    }

    @Test
    fun cancelIsIdempotent() {
        val task = createTask()
        task.launched()

        val first = task.canceled(testClock)
        assertTrue(first.value)
        assertEquals(1, first.findAllByType<BackgroundTaskCanceled>().size)

        val second = task.canceled(testClock)
        assertFalse(second.value)
        assertTrue(second.events.isEmpty())
        assertEquals(BackgroundTaskStatus.Canceled, task.status)
    }

    @Test
    fun closedTasksCannotChangeStatus() {
        // Task is completed, and must not change status again
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        task.completed(result = null, objectMapper = objectMapper, clock = testClock)
        Assumptions.assumeTrue(task.isClosed())

        // Any attempt to change status must fail
        assertThrows<InvalidBackgroundTaskStatus> {
            task.canceled(clock = testClock)
        }
        assertThrows<InvalidBackgroundTaskStatus> {
            task.started(
                workerName = "test-worker",
                leaseDuration = leaseDuration,
                clock = testClock,
            )
        }
        assertThrows<InvalidBackgroundTaskStatus> {
            task.completed(
                result = null,
                objectMapper = objectMapper,
                clock = testClock,
            )
        }
        assertThrows<InvalidBackgroundTaskStatus> {
            val exception = Exception()
            task.failed(
                exception = exception,
                error = BackgroundTaskError.of(exception),
                objectMapper = objectMapper,
                clock = testClock,
            )
        }

        // Status must forever remain "completed"
        assertEquals(BackgroundTaskStatus.Completed, task.status)
    }

    @Test
    fun canceledTaskCannotBeStarted() {
        val task = createTask()
        task.canceled(testClock)
        assertThrows<InvalidBackgroundTaskStatus> {
            task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        }
    }

    @Test
    fun taskMustRunToComplete() {
        val task = createTask()
        assertThrows<InvalidBackgroundTaskStatus> {
            task.completed(result = null, objectMapper = objectMapper, clock = testClock)
        }
    }

    @Test
    fun startedTaskHoldsLease() {
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)

        assertEquals(1, task.attempt)
        assertEquals("test-worker", task.leaseWorkerName)
        assertEquals(testNow + leaseDuration, task.leaseExpiresOn)
        assertFalse(task.isLeaseExpired(testClock))
        assertFalse(task.isResumable(testClock))

        // Lease expires unless renewed
        val laterClock = Clock.offset(testClock, leaseDuration + Duration.ofSeconds(1))
        assertTrue(task.isLeaseExpired(laterClock))
        assertTrue(task.isResumable(laterClock))

        task.renewLease(leaseDuration = leaseDuration, clock = laterClock)
        assertFalse(task.isLeaseExpired(laterClock))
    }

    @Test
    fun progressIncludesStage() {
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)

        val stage = BackgroundTaskProgress.Stage(
            name = "copy",
            index = 1,
            count = 3,
            completedItems = 2,
            totalItems = 8,
        )
        val stageWith3Items = stage.copy(
            completedItems = 3,
        )

        // First update changes progress, second update doesn't (no change)
        assertTrue(task.updateProgress(BackgroundTaskProgress(40, stage), objectMapper))
        assertFalse(task.updateProgress(BackgroundTaskProgress(40, stage), objectMapper))

        // Only a changed percentage counts as changed, but the stage is stored regardless
        assertFalse(task.updateProgress(BackgroundTaskProgress(40, stageWith3Items), objectMapper))
        assertEquals(40, task.progressPercent)
        assertEquals(BackgroundTaskProgress(40, stageWith3Items), task.getProgress(objectMapper))

        // Completion keeps the latest stage, but at 100 percent; the lease is released
        task.completed(result = null, objectMapper = objectMapper, clock = testClock)
        assertEquals(BackgroundTaskProgress(100, stageWith3Items), task.getProgress(objectMapper))
        assertNull(task.leaseExpiresOn)
    }

    @Test
    fun failedTaskIsResumable() {
        val task = createTask()
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        task.updateProgress(progress = BackgroundTaskProgress(30), objectMapper = objectMapper)

        // Task is resumable after failure
        val exception = IllegalStateException("broken")
        task.failed(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            objectMapper = objectMapper,
            clock = testClock,
        )
        assertTrue(task.isResumable(testClock))

        // Resume task
        val resumedResult = task.resumed(testClock)
        val resumed = resumedResult.findAllByType<BackgroundTaskResumed>().single()
        assertEquals(BackgroundTaskStatus.Failed, resumed.previousStatus)
        assertEquals(BackgroundTaskStatus.Created, task.status)
        assertFalse(resumed.stateDiscarded)
        assertNull(task.startedOn)
        assertNull(task.completedOn)
        assertNull(task.errorJson)
        assertEquals(30, task.progressPercent)

        // Next start is a new attempt
        task.started("other-worker", leaseDuration, testClock)
        assertEquals(2, task.attempt)
        assertEquals("other-worker", task.leaseWorkerName)
        assertNotNull(task.startedOn)
    }

    @Test
    fun failedTaskWaitsForRetry() {
        val task = createTask()
        task.started("test-worker", leaseDuration, testClock)
        task.updateProgress(BackgroundTaskProgress(30), objectMapper)

        // Fail task with a scheduled retry
        val exception = IllegalStateException("broken")
        val delay = Duration.ofSeconds(90)
        val result = task.retryScheduled(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            retryDelay = delay,
            objectMapper = objectMapper,
            clock = testClock,
        )
        val event = result.findAllByType<BackgroundTaskRetryScheduled>().single()
        assertEquals(delay, event.delay)
        assertSame(exception, event.exception)

        // Pending again, with the error of the failed attempt, the due retry, and the progress retained
        assertEquals(BackgroundTaskStatus.RetryScheduled, task.status)
        assertTrue(task.isPending())
        assertFalse(task.isClosed())
        assertEquals(testNow + delay, task.retryOn)
        assertEquals(BackgroundTaskError.of(exception), task.getError(objectMapper))
        assertNull(task.startedOn)
        assertNull(task.completedOn)
        assertNull(task.leaseExpiresOn)
        assertEquals(30, task.progressPercent)
        assertTrue(task.isResumable(testClock))
        val details = task.toDetails(objectMapper)
        assertEquals(task.retryOn, details.retryOn)
        assertEquals(task.getError(objectMapper), details.error)

        // The retry is the next attempt, discarding the error of the failed attempt
        task.started(workerName = "other-worker", leaseDuration = leaseDuration, clock = testClock)
        assertEquals(2, task.attempt)
        assertNull(task.retryOn)
        assertNull(task.errorJson)
        assertNotNull(task.startedOn)
    }

    @Test
    fun onlyRunningTasksWaitForRetry() {
        // Can't schedule retry before task is running
        val created = createTask()
        val exception = IllegalStateException("broken")
        val error = BackgroundTaskError.of(exception)
        assertThrows<InvalidBackgroundTaskStatus> {
            created.retryScheduled(
                exception = exception,
                error = error,
                retryDelay = Duration.ZERO,
                objectMapper = objectMapper,
                clock = testClock,
            )
        }

        // Can't schedule retry with negative duration
        val running = createTask()
        running.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertThrows<IllegalArgumentException> {
            running.retryScheduled(
                exception = exception,
                error = error,
                retryDelay = Duration.ofSeconds(-1),
                objectMapper = objectMapper,
                clock = testClock,
            )
        }
    }

    @Test
    fun taskWaitingForRetryCanBeCanceledFailedOrResumed() {
        // Function setting up a task awaiting a scheduled retry
        val exception = IllegalStateException("broken")
        val error = BackgroundTaskError.of(exception)
        fun waitingTask() = createTask().also { task ->
            task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
            task.retryScheduled(
                exception = exception,
                error = error,
                retryDelay = Duration.ofMinutes(1),
                objectMapper = objectMapper,
                clock = testClock,
            )
        }

        // Canceled while pending: Distinguished from a task canceled while running
        val canceled = waitingTask()
        assertTrue(canceled.canceled(testClock).value)
        assertTrue(canceled.isCanceledWhilePending())
        assertNull(canceled.retryOn)
        val canceledRunning = createTask()
        canceledRunning.started("test-worker", leaseDuration, testClock)
        canceledRunning.canceled(testClock)
        assertFalse(canceledRunning.isCanceledWhilePending())

        // Failed, e.g. if the retry couldn't be enqueued
        val failed = waitingTask()
        failed.failed(exception, error, objectMapper, testClock)
        assertEquals(BackgroundTaskStatus.Failed, failed.status)
        assertNull(failed.retryOn)

        // Resumed: Retried now
        val resumed = waitingTask()
        val event = resumed.resumed(testClock).findAllByType<BackgroundTaskResumed>().single()
        assertEquals(BackgroundTaskStatus.RetryScheduled, event.previousStatus)
        assertEquals(BackgroundTaskStatus.Created, resumed.status)
        assertNull(resumed.retryOn)
        assertNull(resumed.errorJson)
    }

    @Test
    fun restartedTaskResetsProgress() {
        val task = createTask()
        val progress = BackgroundTaskProgress(30, BackgroundTaskProgress.Stage("send", 1, 2))
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        task.updateProgress(progress, objectMapper)

        val exception = IllegalStateException("broken")
        task.failed(
            exception = exception,
            error = BackgroundTaskError.of(exception),
            objectMapper = objectMapper,
            clock = testClock,
        )

        // When state is discarded, progress is reset
        val events = task.resumed(clock = testClock, discardState = true)
        assertTrue(events.findAllByType<BackgroundTaskResumed>().single().stateDiscarded)
        assertEquals(BackgroundTaskStatus.Created, task.status)
        assertEquals(0, task.progressPercent)
        assertEquals(BackgroundTaskProgress.NONE, task.getProgress(objectMapper))
    }

    @Test
    fun onlyFailedPendingOrOrphanedTasksAreResumable() {
        // Pending tasks can be resumed (their launch may have been lost), pending since they've been resumed
        val created = createTask()
        assertTrue(created.isResumable(testClock))
        val laterClock = Clock.offset(testClock, Duration.ofMinutes(5))
        created.resumed(laterClock)
        assertEquals(BackgroundTaskStatus.Created, created.status)
        assertEquals(LocalDateTime.now(laterClock), created.pendingSince)

        val launched = createTask()
        launched.launched()
        launched.resumed(testClock)
        assertEquals(BackgroundTaskStatus.Created, launched.status)

        // Running tasks can't be resumed ...
        val running = createTask()
        running.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertThrows<InvalidBackgroundTaskStatus> { running.resumed(testClock) }

        // ... until their lease has expired
        val leaseExpiredClock = Clock.offset(testClock, leaseDuration.plusSeconds(1))
        running.resumed(clock = leaseExpiredClock)
        assertEquals(BackgroundTaskStatus.Created, running.status)

        // Completed tasks can't be resumed
        val completed = createTask()
        completed.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        completed.completed(result = null, objectMapper = objectMapper, clock = testClock)
        assertThrows<InvalidBackgroundTaskStatus> { completed.resumed(testClock) }

        // Canceled tasks can't be resumed
        val canceled = createTask()
        canceled.canceled(testClock)
        assertThrows<InvalidBackgroundTaskStatus> { canceled.resumed(testClock) }
    }

    @Test
    fun pendingSinceTracksWaitingForAWorker() {
        val failure = IllegalStateException("Failed")
        val error = BackgroundTaskError.of(failure)
        val task = createTask()
        assertEquals(task.createdOn, task.pendingSince)

        // Not pending while running, or closed
        task.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertNull(task.pendingSince)

        // Pending again while waiting for its retry, and after resuming it
        val retryClock = Clock.offset(testClock, Duration.ofMinutes(1))
        task.retryScheduled(failure, error, Duration.ofMinutes(1), objectMapper, retryClock)
        assertEquals(LocalDateTime.now(retryClock), task.pendingSince)

        val resumeClock = Clock.offset(testClock, Duration.ofMinutes(2))
        task.resumed(resumeClock)
        assertEquals(LocalDateTime.now(resumeClock), task.pendingSince)

        task.failed(failure, error, objectMapper, resumeClock)
        assertNull(task.pendingSince)

        val canceled = createTask()
        canceled.canceled(testClock)
        assertNull(canceled.pendingSince)
    }

    @Test
    fun stalledTasks() {
        val failure = IllegalStateException("Failed")
        val error = BackgroundTaskError.of(failure)
        val stalledAfter = Duration.ofMinutes(10)
        val stalledClock = Clock.offset(testClock, stalledAfter.plusSeconds(1))

        // Pending tasks are stalled once they've been pending for longer than the threshold
        val created = createTask()
        assertFalse(created.isStalled(testClock, stalledAfter))
        assertTrue(created.isStalled(stalledClock, stalledAfter))
        val launched = createTask()
        launched.launched()
        assertTrue(launched.isStalled(stalledClock, stalledAfter))

        // Running tasks are stalled as soon as their lease expired
        val running = createTask()
        running.started(workerName = "test-worker", leaseDuration = leaseDuration, clock = testClock)
        assertFalse(running.isStalled(testClock, stalledAfter))
        assertTrue(running.isStalled(Clock.offset(testClock, leaseDuration.plusSeconds(1)), stalledAfter))

        // Tasks waiting for their retry are stalled once their retry is overdue by more than the threshold
        val retryDelay = Duration.ofMinutes(30)
        running.retryScheduled(failure, error, retryDelay, objectMapper, testClock)
        assertFalse(running.isStalled(stalledClock, stalledAfter))
        assertFalse(running.isStalled(Clock.offset(testClock, retryDelay + stalledAfter), stalledAfter))
        assertTrue(running.isStalled(Clock.offset(testClock, retryDelay + stalledAfter.plusSeconds(1)), stalledAfter))

        // Closed tasks are never stalled
        val failed = createTask()
        failed.failed(failure, error, objectMapper, testClock)
        assertFalse(failed.isStalled(stalledClock, stalledAfter))
    }

    @Test
    fun statusFlags() {
        // Three status categories, with distinct flags
        assertEquals(
            listOf(BackgroundTaskStatus.Created, BackgroundTaskStatus.Launched, BackgroundTaskStatus.RetryScheduled),
            BackgroundTaskStatus.entries.filter { it.isPending },
        )
        assertEquals(
            listOf(BackgroundTaskStatus.Running),
            BackgroundTaskStatus.entries.filter { it.isRunning },
        )
        assertEquals(
            listOf(BackgroundTaskStatus.Completed, BackgroundTaskStatus.Failed, BackgroundTaskStatus.Canceled),
            BackgroundTaskStatus.entries.filter { it.isClosed },
        )
    }
}
