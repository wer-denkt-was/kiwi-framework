package de.kiwi.backgroundTask

import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.backgroundTask.*
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.*
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage.StatusChanged
import de.kiwi.backgroundTask.domain.BackgroundTask
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import de.kiwi.backgroundTask.domain.InvalidBackgroundTaskStatus
import de.kiwi.backgroundTask.infrastructure.TestBackgroundTaskMessaging
import de.kiwi.backgroundTask.infrastructure.actuate.BackgroundTaskEndpoint
import de.kiwi.backgroundTask.infrastructure.actuate.BackgroundTaskStatsEndpoint
import de.kiwi.framework.support.FieldsetSourceGeneratorTool
import de.kiwi.framework.support.TypescriptFieldsetSourceRenderer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.IntNode
import java.time.Clock
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture

private val log = slf4j<AbstractBackgroundTaskIntegrationTest>()

private const val MARIADB_LTS = "12.3"

/**
 * Integration tests of the background task module, with the Kiwi framework autoconfiguration and a MariaDB database.
 * Two `default` workers and one `special` worker are configured, each executing one task at a time.
 * Retry delays are capped at 30 seconds.
 * Subclasses select the queue provider.
 */
@SpringBootTest(
    classes = [BackgroundTaskTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.datasource.url=jdbc:tc:mariadb:$MARIADB_LTS:///background_tasks",
        "spring.liquibase.change-log=classpath:db/changelog/db.background-tasks-master.xml",
        "spring.jpa.hibernate.ddl-auto=validate",
        "kiwi.background-task.worker.enabled=true",
        "kiwi.background-task.worker.max-retry-delay=30s",
        "management.endpoints.web.exposure.include=backgroundTasks,backgroundTaskStats",
        "kiwi.background-task.worker.instances[0].include-categories=default",
        "kiwi.background-task.worker.instances[1].include-categories=default",
        "kiwi.background-task.worker.instances[2].include-categories=special",
    ],
)
abstract class AbstractBackgroundTaskIntegrationTest {
    protected val testUser = TestUserDescriptor("Test User", userId = 42)
    protected val defaultTimeout: Duration = Duration.ofSeconds(5)
    protected val jsonMapper: ObjectMapper = testObjectMapper()

    @Autowired
    lateinit var resourceLoader: ResourceLoader

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var backgroundTaskService: BackgroundTaskService

    @Autowired
    lateinit var backgroundTaskMessaging: TestBackgroundTaskMessaging

    @Autowired
    lateinit var backgroundTaskRepository: BackgroundTaskRepository

    @Autowired
    lateinit var backgroundTaskDataRepository: BackgroundTaskDataRepository

    @Autowired
    lateinit var transactionService: TransactionService

    @Autowired
    lateinit var taskInvocations: TaskInvocations


    @Autowired
    lateinit var backgroundTaskManagement: BackgroundTaskManagement

    @Autowired
    lateinit var backgroundTaskEndpoint: BackgroundTaskEndpoint

    @Autowired
    lateinit var backgroundTaskStatsEndpoint: BackgroundTaskStatsEndpoint

    @Autowired
    lateinit var sentMails: SentMails

    @AfterEach
    fun tearDown() {
        backgroundTaskMessaging.clear()
    }

    @Test
    fun workersAreRegistered() {
        val workers = applicationContext.getBeansOfType<BackgroundTaskWorker>()
        assertEquals(setOf("backgroundTaskWorker-0", "backgroundTaskWorker-1", "backgroundTaskWorker-2"), workers.keys)
        assertTrue(workers.getValue("backgroundTaskWorker-2").acceptsCategory("special"))
        assertFalse(workers.getValue("backgroundTaskWorker-2").acceptsCategory("default"))
    }

    @Test
    fun backgroundTaskFieldsetSources() {
        FieldsetSourceGeneratorTool
            .scanContext(resourceLoader, applicationContext)
            .generateBuildArtifacts("background-tasks", TypescriptFieldsetSourceRenderer())
    }

    @Test
    fun launchTasks() {
        val descriptors = arrayOf(
            backgroundTaskService.launch(ExampleTaskRequest(message = "Hello World 1", delayMs = 500), testUser),
            backgroundTaskService.launch(ExampleTaskRequest(message = "Hello World 2", delayMs = 400), testUser),
            backgroundTaskService.launch(ExampleTaskRequest(message = "Hello World 3", delayMs = 600), testUser),
            backgroundTaskService.launch(ExampleTaskRequest(message = "Hello World 4", delayMs = 100), testUser),
            backgroundTaskService.launch(SpecialTaskRequest(message = "SPECIAL Hello World!", delayMs = 200), testUser),
        )
        log.info("Launched tasks: {}", descriptors.toList())

        awaitMessageForTasks<StatusChanged.Started>(*descriptors)
        awaitMessageForTasks<StatusChanged.Completed>(*descriptors)
        assertStatusForTaskEntities(BackgroundTaskStatus.Completed, *descriptors)

        // Completion result is the handler's result value, the launching user is passed to the handler
        val completed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptors[0].taskHandle)
            .get()
        assertEquals("Done: Hello World 1", completed.completionResult.asString())

        val special = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptors[4].taskHandle)
            .get()
        assertEquals("SPECIAL Hello World!", special.completionResult["special"].asString())
        assertEquals(setOf(testUser.username), taskInvocations.usersFor(descriptors[0].taskHandle))
        assertEquals(listOf("handle"), taskInvocations.methodsFor(descriptors[0].taskHandle))

        val task = findTask(descriptors[0])
        assertEquals(42L, task.createdByUserId)
        assertEquals(testUser.username, task.createdByUsername)
        assertNotNull(task.startedOn)
        assertNotNull(task.completedOn)

        // Result is stored, and available in the task details
        val details = backgroundTaskService.lookupDetails(descriptors[0].taskHandle)
        assertNotNull(details)
        assertEquals(BackgroundTaskStatus.Completed, details!!.status)
        assertEquals(100, details.progress.percent)
        assertEquals(1, details.attempt)
        assertEquals("Done: Hello World 1", details.result?.asString())
        assertEquals(ExampleTaskRequest::class.java.name, details.requestClassName)
        assertNull(details.error)

        // Unknown task handles return null
        assertNull(backgroundTaskService.lookupDetails(UUID.randomUUID()))
    }

    @Test
    fun tasksWithProgress() {
        val progress1Request = ProgressTaskRequest(
            listOf(
                ProgressTaskRequest.ProgressStep(percent = 10, delayMs = 100),
                ProgressTaskRequest.ProgressStep(percent = 20, delayMs = 50),
                ProgressTaskRequest.ProgressStep(percent = 20, delayMs = 10),
                ProgressTaskRequest.ProgressStep(percent = 40, delayMs = 40),
                ProgressTaskRequest.ProgressStep(percent = 70, delayMs = 200),
                ProgressTaskRequest.ProgressStep(percent = 90, delayMs = 10),
            ),
        )
        val progress2Request = ProgressTaskRequest(
            listOf(
                ProgressTaskRequest.ProgressStep(percent = 33, delayMs = 200),
                ProgressTaskRequest.ProgressStep(percent = 66, delayMs = 300),
                ProgressTaskRequest.ProgressStep(percent = 99, delayMs = 100),
            ),
        )

        val progress1Descriptor = backgroundTaskService.launch(progress1Request, testUser)
        val progress2Descriptor = backgroundTaskService.launch(progress2Request, testUser)
        awaitMessageForTasks<StatusChanged.Completed>(progress1Descriptor, progress2Descriptor)
        assertStatusForTaskEntities(BackgroundTaskStatus.Completed, progress1Descriptor, progress2Descriptor)

        // Progress is throttled: Only broadcast if it changed, in the order it was emitted, and the latest progress is
        // always broadcast (at the latest when the task is closed)
        assertProgressMessages(listOf(10, 20, 40, 70, 90), progressMessagesFor(progress1Descriptor))
        assertProgressMessages(listOf(33, 66, 99), progressMessagesFor(progress2Descriptor))

        // Notification is sent (immediately) before the steps, the custom message after the steps, all before the status
        // message. Progress is broadcast asynchronously, so the final progress may follow the custom message.
        val messageTypes2 = backgroundTaskMessaging.messagesFor(progress2Descriptor.taskHandle).map { it.messageType }
        assertEquals(listOf("Started", "Notification"), messageTypes2.take(2))
        assertEquals("Completed", messageTypes2.last())
        assertEquals(listOf("stepsDone"), messageTypes2.drop(2).dropLast(1).filter { it != "Progress" }) {
            "Unexpected messages: $messageTypes2"
        }
        val notification2 = backgroundTaskMessaging.messagesFor(progress2Descriptor.taskHandle)
            .filterIsInstance<BackgroundTaskMessage.Notification>()
            .single()
        assertEquals("progress-started", notification2.message)
        assertEquals(3, notification2.arguments["steps"])

        val details1 = backgroundTaskService.lookupDetails(progress1Descriptor.taskHandle)!!
        assertEquals(100, details1.progress.percent)
        assertEquals(IntNode(6), details1.result)
    }

    @Test
    fun progressIsThrottled() {
        // Default throttle interval of 1 second: The task emits all its progress within a single interval
        val request = ProgressTaskRequest((1..100).map { ProgressTaskRequest.ProgressStep(percent = it, delayMs = 0) })
        val descriptor = backgroundTaskService.launch(request, testUser)
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)

        // The first progress is synced immediately, the latest one when the task is closed
        val progress = progressMessagesFor(descriptor)
        assertTrue(progress.size <= 2) { "Progress not throttled: $progress" }
        assertProgressMessages((1..100).toList(), progress)
        assertEquals(100, backgroundTaskService.lookupDetails(descriptor.taskHandle)?.progress?.percent)
    }

    @Test
    fun progressIsRecordedOnTask() {
        val request = ProgressTaskRequest(
            listOf(
                ProgressTaskRequest.ProgressStep(percent = 25, delayMs = 0),
                ProgressTaskRequest.ProgressStep(percent = 50, delayMs = 60_000),
            ),
        )
        val descriptor = backgroundTaskService.launch(request, testUser)

        backgroundTaskMessaging
            .awaitMessage<BackgroundTaskMessage.Progress>(defaultTimeout, descriptor.taskHandle) {
                it.progress.percent == 25
            }
            .join()

        assertEquals(25, backgroundTaskService.lookupDetails(descriptor.taskHandle)?.progress?.percent)
        assertEquals(BackgroundTaskStatus.Running, backgroundTaskService.lookupDetails(descriptor.taskHandle)?.status)

        assertTrue(backgroundTaskService.cancel(descriptor.taskHandle, testUser))
        awaitMessageForTasks<StatusChanged.Canceled>(descriptor)
    }

    @Test
    fun stagedTaskReportsStageProgress() {
        val request = MailingTaskRequest(recipients = generateRecipients("staged", 20), delayPerMailMs = 20)
        val descriptor = backgroundTaskService.launch(request, testUser)
        val completed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptor.taskHandle)
            .get()

        // Result of the last stage is the task result
        assertEquals(20, completed.completionResult["sent"].asInt())
        assertEquals(1, completed.completionResult["attempt"].asInt())
        assertEquals(request.recipients, sentMails.mailsTo(request.recipients))
        assertEquals(listOf("prepare", "send", "summarize"), sentMails.stagesExecuted(request))

        // Progress includes the stages and work items, and increases monotonically
        val progress = backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<BackgroundTaskMessage.Progress>()
            .map { it.progress }
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.map { it.percent }.sorted(), progress.map { it.percent })
        val sendProgress = progress.mapNotNull { it.stage }.filter { it.name == "send" }
        assertTrue(sendProgress.all { it.index == 1 && it.count == 3 && it.totalItems == 20 }) { "$sendProgress" }

        val details = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!
        assertEquals(100, details.progress.percent)
        assertEquals("summarize", details.progress.stage?.name)

        // Scratchpad is deleted when the task is completed
        assertTrue(scratchpadKeys(descriptor).isEmpty())
    }

    @Test
    fun failedTaskIsResumedWithoutRepeatingSideEffects() {
        val request = MailingTaskRequest(recipients = generateRecipients("resumed", 10), failAfter = 4)
        val descriptor = backgroundTaskService.launch(request, testUser)
        awaitMessageForTasks<StatusChanged.Failed>(descriptor)
        assertEquals(request.recipients.take(4), sentMails.mailsTo(request.recipients))
        // Events of the completed stage are published, events of the failed stage are discarded
        assertEquals(listOf("prepared:10"), sentMails.eventsPublished(request))
        val failedProgress = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!.progress

        // Scratchpad retains the result of the completed stage, and the sent mails
        val keys = scratchpadKeys(descriptor)
        assertTrue(BackgroundTaskStages.stageResultKey("prepare") in keys) { "$keys" }
        assertEquals(4, keys.count { it.startsWith(BackgroundTaskStages.stageItemKey("send", "")) }) { "$keys" }

        // Resume via the management endpoint
        val taskId = findTask(descriptor).id.toString()
        val info = backgroundTaskEndpoint.showTask(taskId).body as BackgroundTaskManagement.TaskInfo
        assertTrue(info.resumable)
        assertEquals(BackgroundTaskStatus.Failed, info.status)
        assertEquals(keys.sorted(), info.scratchpadKeys)

        backgroundTaskMessaging.clear()
        val resumed = backgroundTaskEndpoint.operation(taskId, "resume")
        assertEquals(200, resumed.status)
        val started = backgroundTaskMessaging.awaitMessage<StatusChanged.Started>(defaultTimeout, descriptor.taskHandle)
        val completed =
            backgroundTaskMessaging.awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptor.taskHandle)
        assertEquals(2, started.get().attempt)
        assertEquals(2, completed.get().completionResult["attempt"].asInt())
        assertEquals(10, completed.get().completionResult["sent"].asInt())

        // Each mail has been sent exactly once, the completed stage has been skipped
        assertEquals(request.recipients, sentMails.mailsTo(request.recipients))
        assertEquals(listOf("prepare", "send", "send", "summarize"), sentMails.stagesExecuted(request))
        assertEquals(listOf("handle", "onFailure", "handle"), taskInvocations.methodsFor(descriptor.taskHandle))
        // Events of the skipped stage aren't published again
        assertEquals(listOf("prepared:10", "sent:10"), sentMails.eventsPublished(request))

        // Progress of the resumed attempt starts at the progress of the failed attempt, and doesn't drop
        val resumedProgress = backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<BackgroundTaskMessage.Progress>()
            .map { it.progress.percent }
        assertTrue(failedProgress.percent > 0) { "$failedProgress" }
        assertTrue(resumedProgress.all { it >= failedProgress.percent }) { "$failedProgress: $resumedProgress" }
        assertEquals(resumedProgress.sorted(), resumedProgress)

        val details = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!
        assertEquals(BackgroundTaskStatus.Completed, details.status)
        assertEquals(2, details.attempt)
        assertNull(details.error)
        assertTrue(scratchpadKeys(descriptor).isEmpty())

        // A completed task can't be resumed
        assertEquals(409, backgroundTaskEndpoint.operation(taskId, "resume").status)
    }

    @Test
    fun failedTaskIsRestartedFromCleanSlate() {
        val request = MailingTaskRequest(recipients = generateRecipients("restarted", 6), failAfter = 2)
        val descriptor = backgroundTaskService.launch(request, testUser)
        awaitMessageForTasks<StatusChanged.Failed>(descriptor)
        assertEquals(request.recipients.take(2), sentMails.mailsTo(request.recipients))
        assertTrue(scratchpadKeys(descriptor).isNotEmpty())

        // Restart via the management endpoint: The scratchpad is discarded, all work is repeated
        val taskId = findTask(descriptor).id.toString()
        backgroundTaskMessaging.clear()
        assertEquals(200, backgroundTaskEndpoint.operation(taskId, "restart").status)
        val completed = backgroundTaskMessaging.awaitMessage<StatusChanged.Completed>(
            timeout = defaultTimeout,
            taskHandle = descriptor.taskHandle,
        )
        assertEquals(2, completed.get().completionResult["attempt"].asInt())

        assertEquals(request.recipients.take(2) + request.recipients, sentMails.mailsTo(request.recipients))
        assertEquals(
            listOf("prepare", "send", "prepare", "send", "summarize"),
            sentMails.stagesExecuted(request),
        )
        // Events of the first attempt's completed stage are published again by the restarted attempt
        assertEquals(listOf("prepared:6", "prepared:6", "sent:6"), sentMails.eventsPublished(request))

        // A completed task can't be restarted
        val httpConflict = 409
        assertEquals(httpConflict, backgroundTaskEndpoint.operation(taskId, "restart").status)
    }

    @Test
    fun failedTaskIsRetriedWithoutRepeatingSideEffects() {
        val retryDelay = Duration.ofSeconds(1)
        val request = MailingTaskRequest(
            recipients = generateRecipients("retried", 10),
            failAfter = 4,
            retryDelayMs = retryDelay.toMillis(),
        )
        val descriptor = backgroundTaskService.launch(request, testUser)

        // The failed attempt waits for its retry: Pending, with the error of the failed attempt and the due time
        val retryScheduled = backgroundTaskMessaging
            .awaitMessage<StatusChanged.RetryScheduled>(defaultTimeout, descriptor.taskHandle)
            .get()
        val retryScheduledAt = System.nanoTime()
        val secondStart = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Started>(defaultTimeout, descriptor.taskHandle) { it.attempt == 2 }
            .thenApply { System.nanoTime() }

        assertEquals(1, retryScheduled.attempt)
        assertEquals("illegal-state", retryScheduled.error.message)
        assertEquals("mail-server-unavailable", retryScheduled.error.arguments["reason"])
        val waiting = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!
        assertEquals(BackgroundTaskStatus.RetryScheduled, waiting.status)
        assertEquals(retryScheduled.error, waiting.error)
        assertNull(waiting.startedOn)
        assertNull(waiting.completedOn)
        assertTrue(waiting.progress.percent > 0) { "${waiting.progress}" }
        val retryOn = waiting.retryOn ?: fail("Retry time missing: $waiting")
        assertTrue(Duration.between(retryOn, retryScheduled.retryOn).abs() <= Duration.ofSeconds(1)) {
            "Retry due $retryOn, broadcast ${retryScheduled.retryOn}"
        }

        // The retry is executed after the delay, as the next attempt, skipping the completed work
        val completed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptor.taskHandle)
            .get()
        val retriedAfter = Duration.ofNanos(secondStart.get() - retryScheduledAt)
        assertTrue(retriedAfter >= retryDelay.minusMillis(200)) { "Retried after $retriedAfter" }
        assertEquals(2, completed.completionResult["attempt"].asInt())
        assertEquals(10, completed.completionResult["sent"].asInt())

        assertEquals(request.recipients, sentMails.mailsTo(request.recipients))
        assertEquals(listOf("prepare", "send", "send", "summarize"), sentMails.stagesExecuted(request))
        assertEquals(listOf("handle", "onFailure", "handle"), taskInvocations.methodsFor(descriptor.taskHandle))
        assertEquals(listOf("prepared:10", "sent:10"), sentMails.eventsPublished(request))
        assertEquals(
            listOf("Started", "RetryScheduled", "Started", "Completed"),
            statusMessagesFor(descriptor),
        )

        val details = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!
        assertEquals(BackgroundTaskStatus.Completed, details.status)
        assertEquals(2, details.attempt)
        assertNull(details.error)
        assertNull(details.retryOn)
        assertTrue(scratchpadKeys(descriptor).isEmpty())
    }

    @Test
    fun retriesAreLimitedByTheHandler() {
        val request = RetryingTaskRequest(failAttempts = 10, retryDelayMs = 50, maxAttempts = 3)
        val descriptor = backgroundTaskService.launch(request, testUser)

        // The third attempt fails without a retry
        val failed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Failed>(defaultTimeout, descriptor.taskHandle)
            .get()
        assertEquals("Attempt 3 failed", failed.error.arguments["reason"])
        assertEquals(
            List(3) { listOf("handle", "onFailure") }.flatten(),
            taskInvocations.methodsFor(descriptor.taskHandle),
        )
        assertEquals(
            listOf("Started", "RetryScheduled", "Started", "RetryScheduled", "Started", "Failed"),
            statusMessagesFor(descriptor),
        )

        val details = backgroundTaskService.lookupDetails(descriptor.taskHandle)!!
        assertEquals(BackgroundTaskStatus.Failed, details.status)
        assertEquals(3, details.attempt)
        assertEquals(failed.error, details.error)
        assertNull(details.retryOn)
    }

    @Test
    fun retryWithoutDelay() {
        val request = RetryingTaskRequest(failAttempts = 2, retryDelayMs = 0)
        val descriptor = backgroundTaskService.launch(request, testUser)

        val completed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptor.taskHandle)
            .get()
        assertEquals("Done in attempt 3", completed.completionResult.asString())
        assertEquals(
            listOf("handle", "onFailure", "handle", "onFailure", "handle"),
            taskInvocations.methodsFor(descriptor.taskHandle),
        )
    }

    @Test
    fun taskWaitingForRetryIsCanceled() {
        val request = RetryingTaskRequest(failAttempts = 1, retryDelayMs = 1500)
        val descriptor = backgroundTaskService.launch(request, testUser)
        awaitMessageForTasks<StatusChanged.RetryScheduled>(descriptor)

        // The task isn't running, so no worker is signaled: It is canceled immediately
        assertFalse(backgroundTaskService.cancel(descriptor.taskHandle, testUser))
        assertStatusForTaskEntities(BackgroundTaskStatus.Canceled, descriptor)
        assertNull(findTask(descriptor).retryOn)

        // The cancellation is broadcast when the retry is picked up, without executing the task or its onCancel again
        awaitMessageForTasks<StatusChanged.Canceled>(descriptor)
        assertEquals(listOf("handle", "onFailure"), taskInvocations.methodsFor(descriptor.taskHandle))
        assertEquals(listOf("Started", "RetryScheduled", "Canceled"), statusMessagesFor(descriptor))
        assertStatusForTaskEntities(BackgroundTaskStatus.Canceled, descriptor)
    }

    @Test
    fun taskWaitingForRetryIsListedAndResumed() {
        val request = RetryingTaskRequest(failAttempts = 1, retryDelayMs = 60_000)
        val descriptor = backgroundTaskService.launch(request, testUser)
        awaitMessageForTasks<StatusChanged.RetryScheduled>(descriptor)
        val task = findTask(descriptor)
        val taskId = task.id.toString()

        // The requested delay of a minute has been capped to the maximum retry delay
        val retryDelay = Duration.between(task.createdOn, task.retryOn)
        assertTrue(retryDelay < Duration.ofSeconds(40)) { "Retry delay $retryDelay" }

        // Waiting for a retry is reported separately from pending tasks
        val stats = backgroundTaskManagement.getStats()
        assertTrue(stats.status.getValue(BackgroundTaskStatus.RetryScheduled) >= 1) { "$stats" }
        assertTrue(stats.oldestRetryOn!! <= task.retryOn) { "$stats" }
        assertEquals(
            stats.status.getValue(BackgroundTaskStatus.Created) + stats.status.getValue(BackgroundTaskStatus.Launched),
            stats.pending,
        )

        // Listed by default, with the error of the failed attempt, and resumable
        val info = backgroundTaskManagement.listTasks(statuses = null, stalled = false, limit = 1000)
            .single { it.id == taskId }
        assertEquals(BackgroundTaskStatus.RetryScheduled, info.status)
        assertTrue(info.resumable)
        assertNotNull(info.retryOn)
        assertEquals("illegal-state", info.error?.message)

        // Resuming retries it now
        assertEquals(200, backgroundTaskEndpoint.operation(taskId, "resume").status)
        val completed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Completed>(defaultTimeout, descriptor.taskHandle)
            .get()
        assertEquals("Done in attempt 2", completed.completionResult.asString())
        assertNull(findTask(descriptor).retryOn)
    }

    @Test
    fun statsReportPendingFailedAndOrphanedTasks() {
        val manager = applicationContext.getBean<BackgroundTaskLifecycleManager>()
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
        val request = ExampleTaskRequest(message = "Stats", delayMs = 0)
        val twoHoursAgo = Clock.offset(Clock.systemUTC(), Duration.ofHours(-2))

        // A task waiting for a worker for two hours, a task that failed to launch, an orphaned task
        val (pending, unstarted, orphaned) = transactionService.write {
            val pending = BackgroundTask.create(request, taskUser, jsonMapper, twoHoursAgo).value
            val unstarted = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            val orphaned = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            orphaned.started("crashed-worker", Duration.ZERO, Clock.systemUTC())
            listOf(pending, unstarted, orphaned).map { backgroundTaskRepository.save(it) }
        }
        manager.failUnstartedTask(unstarted.taskHandle, BackgroundTaskQueue.TaskLaunchRejected("test"))

        run { // all tasks stats
            val response = backgroundTaskStatsEndpoint.stats(period = null)
            assertEquals(200, response.status)

            val stats = assertInstanceOf<BackgroundTaskManagement.TaskStats>(response.body)
            assertEquals(BackgroundTaskStatus.entries.toSet(), stats.status.keys)

            val pendingCount = 0 +
                    stats.status.getValue(BackgroundTaskStatus.Created) +
                    stats.status.getValue(BackgroundTaskStatus.Launched)

            assertEquals(pendingCount, stats.pending)
            assertTrue(stats.oldestPendingSince!! <= pending.createdOn) { "${stats.oldestPendingSince}" }
            // The task pending for two hours, and the orphaned task
            assertTrue(stats.stalled >= 2) { "$stats" }
        }

        run { // period tasks stats
            val response = backgroundTaskStatsEndpoint.stats(period = "1h")
            assertEquals(200, response.status)

            val period = assertInstanceOf<BackgroundTaskManagement.PeriodStats>(response.body)
            assertEquals(Duration.ofHours(1), period.duration)
            assertEquals(period.now - Duration.ofHours(1), period.since)
            assertTrue(period.pendingSincePeriodStart >= 1) { "$period" }
            assertTrue(period.failedUnstarted >= 1) { "$period" }
            assertTrue(period.failed >= period.failedUnstarted) { "$period" }
            assertTrue(period.created >= 2) { "$period" }
            assertTrue(period.started >= 1) { "$period" }
            assertTrue(period.failedByRequestClass.getValue(ExampleTaskRequest::class.java.name) >= 1) { "$period" }

            // The task pending for two hours isn't counted as created in a period of the last hour, but in 3 hours
            val longPeriod = backgroundTaskManagement.getPeriodStats(Duration.ofHours(3))
            assertTrue(longPeriod.created >= period.created + 1) { "$longPeriod" }
        }

        // Clean up: Cancel the pending task, resume the orphaned task
        backgroundTaskService.cancel(pending.taskHandle, testUser)
        backgroundTaskManagement.resumeTask(orphaned.id.toString())
        awaitMessageForTasks<StatusChanged.Completed>(BackgroundTaskDescriptor(orphaned.taskHandle, request))
    }

    @Test
    fun invalidStatsRequests() {
        assertEquals(200, backgroundTaskStatsEndpoint.stats(period = null).status)
        assertEquals(200, backgroundTaskStatsEndpoint.stats(period = "PT15M").status)
        assertEquals(400, backgroundTaskStatsEndpoint.stats(period = "soon").status)
        assertEquals(400, backgroundTaskStatsEndpoint.stats(period = "0s").status)
        assertEquals(400, backgroundTaskStatsEndpoint.stats(period = "-1h").status)
        assertEquals(400, backgroundTaskStatsEndpoint.stats(period = "365d").status)
    }

    @Test
    fun orphanedTaskIsListedAndResumed() {
        // Task started by a worker that crashed: Running, but its lease expired
        val request = ExampleTaskRequest(message = "Orphaned", delayMs = 0)
        val taskHandle = transactionService.write {
            val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
            val task = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            task.started("crashed-worker", Duration.ZERO, Clock.systemUTC())
            backgroundTaskRepository.save(task).taskHandle
        }
        val descriptor = BackgroundTaskDescriptor(taskHandle, request)
        val taskId = findTask(descriptor).id.toString()

        val orphanedResponse = backgroundTaskEndpoint.listTasks(status = null, stalled = true, limit = null)
        val orphaned = (orphanedResponse.body as BackgroundTaskEndpoint.TaskList).tasks
        val info = orphaned.single { it.id == taskId }
        assertTrue(info.leaseExpired)
        assertTrue(info.stalled)
        assertTrue(info.resumable)
        assertEquals("crashed-worker", info.leaseWorkerName)

        // Running tasks with a valid lease aren't listed as orphaned
        assertTrue(
            backgroundTaskManagement.listTasks(setOf(BackgroundTaskStatus.Running), false, 100).any { it.id == taskId },
        )

        backgroundTaskManagement.resumeTask(taskId)
        val started = backgroundTaskMessaging.awaitMessage<StatusChanged.Started>(defaultTimeout, taskHandle).get()
        assertEquals(2, started.attempt)
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)
        assertNotEquals("crashed-worker", findTask(descriptor).leaseWorkerName)
    }

    @Test
    fun supersededExecutionDoesNotChangeTask() {
        val manager = applicationContext.getBean<BackgroundTaskLifecycleManager>()
        val request = ExampleTaskRequest(message = "Superseded", delayMs = 0)
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
        val taskId = transactionService.write {
            val task = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            backgroundTaskRepository.save(task).id
        }

        // Attempt 1 starts, its lease expires, and the task is resumed and started as attempt 2
        val first = manager.startTask(taskId, "worker-1", Duration.ZERO, taskUser)
        assertEquals(BackgroundTaskStatus.Running to 1, first.status to first.attempt)
        manager.putScratchpadValue(taskId, attempt = 1, "checkpoint", "1")
        transactionService.write {
            backgroundTaskRepository.findWriteLockedById(taskId)!!.resumed(Clock.systemUTC())
        }

        // Before attempt 2 starts, attempt 1 is superseded already: It can't close the resumed task
        val completedEarly = manager.completeTask(taskId, 1, IntNode(1), emptyList(), null, taskUser)
        assertTrue(completedEarly.isSuperseded)
        assertEquals(BackgroundTaskStatus.Created, completedEarly.status)
        val failedEarly = manager.failTask(taskId, 1, IllegalStateException("late"), emptyList(), null, taskUser)
        assertTrue(failedEarly.isSuperseded)
        val canceledEarly = manager.cancelTask(taskId, 1, emptyList(), null, taskUser)
        assertTrue(canceledEarly.isSuperseded)
        assertFalse(manager.syncTask(taskId, 1, null, Duration.ofMinutes(1), taskUser).isOwned)

        val second = manager.startTask(taskId, "worker-2", Duration.ofMinutes(1), taskUser)
        assertEquals(BackgroundTaskStatus.Running to 2, second.status to second.attempt)

        // Attempt 1 is superseded: It can't sync, write the scratchpad, or close the task
        val sync = manager.syncTask(taskId, attempt = 1, BackgroundTaskProgress(50), Duration.ofMinutes(1), taskUser)
        assertFalse(sync.isOwned)
        assertEquals(2, sync.attempt)
        val notOwned = assertThrows<BackgroundTaskLifecycleManager.TaskNotOwned> {
            manager.putScratchpadValue(taskId, attempt = 1, "checkpoint", "2")
        }
        assertEquals(2, notOwned.attempt)
        val superseded = manager.completeTask(taskId, 1, IntNode(1), emptyList(), null, taskUser)
        assertTrue(superseded.isSuperseded)
        assertEquals(BackgroundTaskStatus.Running, superseded.status)

        // Attempt 2 owns the task, and sees the scratchpad of attempt 1
        assertEquals(mapOf("checkpoint" to "1"), manager.loadScratchpad(taskId))
        val completed = manager.completeTask(
            taskId = taskId,
            attempt = 2,
            resultValue = IntNode(2),
            events = emptyList(),
            progress = BackgroundTaskProgress(90),
            user = taskUser,
        )
        assertTrue(completed.isClosedByCall)
        assertEquals(BackgroundTaskStatus.Completed, completed.status)
        assertTrue(manager.loadScratchpad(taskId).isEmpty())
    }

    @Test
    fun handlerObservesCancellationInStatus() {
        val descriptor = backgroundTaskService.launch(CooperativeTaskRequest(maxDurationMs = 30_000), testUser)
        awaitMessageForTasks<StatusChanged.Started>(descriptor)
        assertTrue(backgroundTaskService.cancel(descriptor.taskHandle, testUser))

        awaitMessageForTasks<StatusChanged.Canceled>(descriptor)
        val notification = backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<BackgroundTaskMessage.Notification>()
            .single()
        assertEquals("stopping", notification.message)
        assertEquals("CancelRequested", notification.arguments["status"])
        assertEquals(listOf("handle", "onCancel"), taskInvocations.methodsFor(descriptor.taskHandle))
    }

    @Test
    fun staleLaunchOfCanceledTaskIsSkipped() {
        val descriptor = backgroundTaskService.launch(CooperativeTaskRequest(maxDurationMs = 30_000), testUser)
        awaitMessageForTasks<StatusChanged.Started>(descriptor)
        assertTrue(backgroundTaskService.cancel(descriptor.taskHandle, testUser))
        awaitMessageForTasks<StatusChanged.Canceled>(descriptor)

        // A stale launch of the task (e.g. a requeued message) doesn't invoke the cancellation callback again
        val worker = applicationContext.getBeansOfType(BackgroundTaskWorkerBean::class.java).values
            .first { it.acceptsCategory(descriptor.request.category) }
        worker.submit(descriptor, BackgroundTaskUserDescriptor.delegateOf(testUser)).join()

        assertEquals(listOf("handle", "onCancel"), taskInvocations.methodsFor(descriptor.taskHandle))
        val canceledMessages = backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<StatusChanged.Canceled>()
        assertEquals(1, canceledMessages.size)
    }

    @Test
    fun launchInReadOnlyTransactionFails() {
        val request = ExampleTaskRequest(message = "Read-only", delayMs = 0)
        assertThrows<IllegalStateException> {
            transactionService.read { backgroundTaskService.launch(request, testUser) }
        }
    }

    @Test
    fun scratchpadKeysAreCaseSensitive() {
        val manager = applicationContext.getBean<BackgroundTaskLifecycleManager>()
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
        val taskId = transactionService.write {
            val request = ExampleTaskRequest(message = "Scratchpad", delayMs = 0)
            val task = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            backgroundTaskRepository.save(task).id
        }
        manager.startTask(taskId, "test-worker", Duration.ofMinutes(1), taskUser)

        // Keys differing in case are distinct (also with the case-insensitive default collations of MySQL/MariaDB),
        // keys may be as long as the maximum key length
        val longKey = "k".repeat(BackgroundTaskScratchpad.MAX_KEY_LENGTH)
        manager.putScratchpadValue(taskId, attempt = 1, "Readme", "1")
        manager.putScratchpadValue(taskId, attempt = 1, "README", "2")
        manager.putScratchpadValue(taskId, attempt = 1, longKey, "3")
        manager.putScratchpadValue(taskId, attempt = 1, "Readme", "4")
        assertEquals(mapOf("Readme" to "4", "README" to "2", longKey to "3"), manager.loadScratchpad(taskId))

        manager.removeScratchpadValue(taskId, attempt = 1, "README")
        assertEquals(setOf("Readme", longKey), manager.loadScratchpad(taskId).keys)
    }

    @Test
    fun lostLaunchIsListedAsStalledAndResumed() {
        // Task persisted two hours ago, but never enqueued (e.g. the instance crashed after the commit)
        val request = ExampleTaskRequest(message = "Lost", delayMs = 0)
        val twoHoursAgo = Clock.offset(Clock.systemUTC(), Duration.ofHours(-2))
        val taskHandle = transactionService.write {
            val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
            val task = BackgroundTask.create(request, taskUser, jsonMapper, twoHoursAgo).value
            backgroundTaskRepository.save(task).taskHandle
        }
        val descriptor = BackgroundTaskDescriptor(taskHandle, request)
        val taskId = findTask(descriptor).id.toString()

        // Listed as stalled, and resumable
        val stalledResponse = backgroundTaskEndpoint.listTasks(status = null, stalled = true, limit = 1000)
        val info = (stalledResponse.body as BackgroundTaskEndpoint.TaskList).tasks.single { it.id == taskId }
        assertEquals(BackgroundTaskStatus.Created, info.status)
        assertTrue(info.stalled)
        assertTrue(info.resumable)
        assertFalse(info.leaseExpired)

        // Resuming enqueues it: It is executed, and no longer stalled
        val resumed = backgroundTaskEndpoint.operation(taskId, "resume")
        assertEquals(200, resumed.status)
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)
        val stalledAfterwards = backgroundTaskManagement.listTasks(statuses = null, stalled = true, limit = 1000)
        assertTrue(stalledAfterwards.none { it.id == taskId })

        // A pending task that has just been launched isn't stalled
        val fresh = transactionService.write {
            val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
            val task = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            backgroundTaskRepository.save(task)
        }
        assertFalse(fresh.isStalled(Clock.systemUTC(), BackgroundTaskManagement.DEFAULT_STALLED_AFTER))
        backgroundTaskService.cancel(fresh.taskHandle, testUser)
    }

    @Test
    fun rejectedTaskFailsUnlessStarted() {
        val manager = applicationContext.getBean<BackgroundTaskLifecycleManager>()
        val taskUser = BackgroundTaskUserDescriptor.delegateOf(testUser)
        val request = ExampleTaskRequest(message = "Rejected", delayMs = 0)
        val (unstarted, started) = transactionService.write {
            val unstarted = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            val started = BackgroundTask.create(request, taskUser, jsonMapper, Clock.systemUTC()).value
            started.started("other-worker", Duration.ofMinutes(1), Clock.systemUTC())
            backgroundTaskRepository.save(unstarted).taskHandle to backgroundTaskRepository.save(started).taskHandle
        }
        val rejection = BackgroundTaskQueue.TaskLaunchRejected("unreadable-message")

        // A task that hasn't been started fails, so it can be resumed
        val rejected = manager.failUnstartedTask(unstarted, rejection)
        assertEquals(true, rejected?.isClosedByCall)
        val details = backgroundTaskService.lookupDetails(unstarted)!!
        assertEquals(BackgroundTaskStatus.Failed, details.status)
        assertEquals("task-launch-rejected", details.error?.message)
        assertTrue(findTask(BackgroundTaskDescriptor(unstarted, request)).isResumable(Clock.systemUTC()))

        // A task started by another worker is not changed, an unknown task is ignored
        val notRejected = manager.failUnstartedTask(started, rejection)
        assertEquals(BackgroundTaskStatus.Running to false, notRejected?.status to notRejected?.isClosedByCall)
        assertNull(manager.failUnstartedTask(UUID.randomUUID(), rejection))
    }

    @Test
    fun invalidManagementRequests() {
        assertEquals(400, backgroundTaskEndpoint.listTasks(status = null, stalled = null, limit = 0).status)
        assertEquals(400, backgroundTaskEndpoint.operation("not-an-id", "resume").status)
        assertEquals(400, backgroundTaskEndpoint.showTask("not-an-id").status)
        assertEquals(404, backgroundTaskEndpoint.showTask(BackgroundTaskId(42).toString()).status)
        assertEquals(404, backgroundTaskEndpoint.operation(BackgroundTaskId(42).toString(), "resume").status)
        assertEquals(404, backgroundTaskEndpoint.operation(BackgroundTaskId(42).toString(), "restart").status)

        val descriptor = backgroundTaskService.launch(ExampleTaskRequest(message = "Done", delayMs = 0), testUser)
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)
        val taskId = findTask(descriptor).id.toString()
        assertEquals(400, backgroundTaskEndpoint.operation(taskId, "explode").status)
        val conflict = backgroundTaskEndpoint.operation(taskId, "resume")
        assertEquals(409, conflict.status)
        // Only the error code is disclosed, not the exception's arguments
        assertEquals(BackgroundTaskEndpoint.ErrorResponse("invalid-background-task-status", null), conflict.body)
    }

    @Test
    fun launchJoinsCallerTransaction() {
        var descriptor: BackgroundTaskDescriptor? = null
        assertThrows<IllegalStateException> {
            transactionService.write {
                val request = ExampleTaskRequest("Rolled back", delayMs = 0)
                descriptor = backgroundTaskService.launch(request, testUser)
                throw IllegalStateException("Rollback the caller's transaction")
            }
        }

        // Task has been rolled back with the caller's transaction, and never enqueued
        val taskHandle = descriptor!!.taskHandle
        assertNull(backgroundTaskService.lookup(taskHandle))
        Thread.sleep(500)

        assertTrue(backgroundTaskMessaging.messagesFor(taskHandle).isEmpty())
        assertTrue(taskInvocations.methodsFor(taskHandle).isEmpty())
    }

    @Test
    fun unknownCategoryFailsLaunch() {
        val countBefore = transactionService.read { backgroundTaskRepository.count() }
        assertThrows<BackgroundTaskQueue.UnknownTaskCategory> {
            backgroundTaskService.launch(UnknownCategoryTaskRequest(value = 1), testUser)
        }
        val countAfter = transactionService.read { backgroundTaskRepository.count() }
        assertEquals(countBefore, countAfter)
    }

    @Test
    fun cancelTasks() {
        val laterRequest = ExampleTaskRequest(message = "Cancel me later", delayMs = 60_000)
        val laterDescriptor = backgroundTaskService.launch(laterRequest, testUser)

        val earlierRequest = ExampleTaskRequest(message = "Cancel me early", delayMs = 60_000)
        val earlyDescriptor = backgroundTaskService.launch(earlierRequest, testUser)
        backgroundTaskService.cancel(taskHandle = earlyDescriptor.taskHandle, user = testUser)
        log.info("Canceled early task *immediately* after launch")

        awaitMessageForTasks<StatusChanged.Started>(laterDescriptor)
        assertTrue(backgroundTaskService.cancel(taskHandle = laterDescriptor.taskHandle, user = testUser))

        val descriptors = arrayOf(earlyDescriptor, laterDescriptor)
        awaitMessageForTasks<StatusChanged.Canceled>(*descriptors)
        assertStatusForTaskEntities(BackgroundTaskStatus.Canceled, *descriptors)
        assertEquals(listOf("handle", "onCancel"), taskInvocations.methodsFor(laterDescriptor.taskHandle))

        // Canceling again has no effect
        assertFalse(backgroundTaskService.cancel(taskHandle = laterDescriptor.taskHandle, user = testUser))
    }

    @Test
    fun cancelQueuedTask() {
        // The single special worker is blocked, so the queued task stays launched until the blocking task is canceled
        val blockingDescriptor =
            backgroundTaskService.launch(SpecialTaskRequest(message = "Blocking", delayMs = 60_000), testUser)
        awaitMessageForTasks<StatusChanged.Started>(blockingDescriptor)
        val queuedDescriptor =
            backgroundTaskService.launch(SpecialTaskRequest(message = "Queued", delayMs = 60_000), testUser)
        busyLoopAwaitStatus(queuedDescriptor, BackgroundTaskStatus.Launched)

        // Queued task is not running, so its worker isn't signaled, and the task is skipped when it is picked up
        assertFalse(backgroundTaskService.cancel(taskHandle = queuedDescriptor.taskHandle, user = testUser))
        assertTrue(backgroundTaskService.cancel(taskHandle = blockingDescriptor.taskHandle, user = testUser))

        awaitMessageForTasks<StatusChanged.Canceled>(blockingDescriptor, queuedDescriptor)
        assertStatusForTaskEntities(BackgroundTaskStatus.Canceled, blockingDescriptor, queuedDescriptor)
        assertEquals(listOf("onCancel"), taskInvocations.methodsFor(queuedDescriptor.taskHandle))
        assertTrue(
            backgroundTaskMessaging.messagesFor(queuedDescriptor.taskHandle).none { it is StatusChanged.Started },
        )
    }

    @Test
    fun failingTasks() {
        val successDescriptors = arrayOf(
            backgroundTaskService.launch(ExampleTaskRequest(message = "Successful 1", delayMs = 200), testUser),
            backgroundTaskService.launch(ExampleTaskRequest(message = "Successful 2", delayMs = 300), testUser),
            backgroundTaskService.launch(ExampleTaskRequest(message = "Successful 3", delayMs = 400), testUser),
        )
        val failingDescriptor =
            backgroundTaskService.launch(FailingTaskRequest(failureMessage = "I am a failure", delayMs = 250), testUser)

        CompletableFuture.allOf(
            awaitMessageForTasksAsync<StatusChanged.Completed>(*successDescriptors),
            awaitMessageForTasksAsync<StatusChanged.Failed>(failingDescriptor),
        ).join()

        assertStatusForTaskEntities(BackgroundTaskStatus.Completed, *successDescriptors)
        assertStatusForTaskEntities(BackgroundTaskStatus.Failed, failingDescriptor)

        // Well-known exceptions (the handler throws an IllegalStateException) are sent with their message
        val failed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Failed>(defaultTimeout, failingDescriptor.taskHandle)
            .get()

        assertEquals("illegal-state", failed.error.message)
        assertEquals("I am a failure", failed.error.arguments["reason"])
        assertEquals(IllegalStateException::class.java.name, failed.error.errorType)
        assertEquals(failed.error, backgroundTaskService.lookupDetails(failingDescriptor.taskHandle)?.error)

        // The task entity records the same error
        val failedTaskError = findTask(failingDescriptor).getError(jsonMapper)
        assertEquals(failed.error, failedTaskError)
        assertEquals(listOf("handle", "onFailure"), taskInvocations.methodsFor(failingDescriptor.taskHandle))
    }

    @Test
    fun taskWithoutHandlerFails() {
        val descriptor = backgroundTaskService.launch(UnhandledTaskRequest(value = 1), testUser)

        val failed = backgroundTaskMessaging
            .awaitMessage<StatusChanged.Failed>(defaultTimeout, descriptor.taskHandle)
            .get()

        assertEquals(MissingBackgroundTaskHandler::class.java.name, failed.error.errorType)
        assertEquals("missing-background-task-handler", failed.error.message)
        assertEquals(UnhandledTaskRequest::class.java.name, failed.error.arguments["requestClassName"])
        assertStatusForTaskEntities(BackgroundTaskStatus.Failed, descriptor)
    }

    @Test
    fun lookupTask() {
        val descriptor = backgroundTaskService.launch(ExampleTaskRequest(message = "Lookup", delayMs = 0), testUser)

        assertEquals(descriptor, backgroundTaskService.lookup(descriptor.taskHandle))
        assertNull(backgroundTaskService.lookup(UUID.randomUUID()))
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)
    }

    @Test
    fun cancelInvalidTask() {
        assertThrows<BackgroundTaskQueue.UnknownTaskHandle> {
            backgroundTaskService.cancel(
                UUID.randomUUID(),
                testUser,
            )
        }

        val descriptor = backgroundTaskService.launch(ExampleTaskRequest(message = "Completed", delayMs = 0), testUser)
        awaitMessageForTasks<StatusChanged.Completed>(descriptor)
        assertThrows<InvalidBackgroundTaskStatus> { backgroundTaskService.cancel(descriptor.taskHandle, testUser) }
        assertStatusForTaskEntities(BackgroundTaskStatus.Completed, descriptor)
    }

    // --- Internal utilities ---

    private fun generateRecipients(prefix: String, count: Int): List<String> {
        val id = UUID.randomUUID()
        return (1..count).map { "$prefix-$it-$id@example.org" }
    }

    private fun scratchpadKeys(descriptor: BackgroundTaskDescriptor): List<String> {
        val taskId = findTask(descriptor).id
        return transactionService.read {
            backgroundTaskDataRepository.findKeysByTaskId(taskId)
        }
    }

    private inline fun <reified M : BackgroundTaskMessage> awaitMessageForTasksAsync(
        vararg descriptors: BackgroundTaskDescriptor,
    ): CompletableFuture<Void> {
        val awaitMessages = descriptors.map { backgroundTaskMessaging.awaitMessage<M>(defaultTimeout, it.taskHandle) }
        return CompletableFuture.allOf(*awaitMessages.toTypedArray())
    }

    private inline fun <reified M : BackgroundTaskMessage> awaitMessageForTasks(
        vararg descriptors: BackgroundTaskDescriptor,
    ) {
        awaitMessageForTasksAsync<M>(*descriptors).join()
    }

    /** Assert that the broadcast progress is a subsequence of the emitted progress, ending with the latest progress */
    private fun assertProgressMessages(emitted: List<Int>, broadcast: List<Int>) {
        assertEquals(emitted.last(), broadcast.lastOrNull()) { "Latest progress not broadcast: $broadcast" }
        val remaining = emitted.iterator()
        assertTrue(broadcast.all { percent -> remaining.asSequence().any { it == percent } }) {
            "Progress $broadcast not broadcast in the order emitted: $emitted"
        }
    }

    private fun statusMessagesFor(descriptor: BackgroundTaskDescriptor): List<String> {
        return backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<StatusChanged>()
            .map { it.messageType }
    }

    private fun progressMessagesFor(descriptor: BackgroundTaskDescriptor): List<Int> {
        return backgroundTaskMessaging.messagesFor(descriptor.taskHandle)
            .filterIsInstance<BackgroundTaskMessage.Progress>()
            .map { it.progress.percent }
    }

    private fun findTask(descriptor: BackgroundTaskDescriptor): BackgroundTask {
        return transactionService.read {
            backgroundTaskRepository.findByHandle(descriptor.taskHandle)
                ?: fail("Missing task entity for handle ${descriptor.taskHandle}")
        }
    }

    private fun busyLoopAwaitStatus(
        descriptor: BackgroundTaskDescriptor,
        status: BackgroundTaskStatus,
        timeout: Duration = this.defaultTimeout,
    ) {
        val deadline = System.currentTimeMillis() + timeout.toMillis()
        while (findTask(descriptor).status != status) {
            if (System.currentTimeMillis() > deadline) {
                fail<Unit>("Task ${descriptor.taskHandle} did not reach status $status")
            }
            Thread.sleep(50)
        }
    }

    private fun assertStatusForTaskEntities(
        expectedStatus: BackgroundTaskStatus,
        vararg descriptors: BackgroundTaskDescriptor,
    ) {
        assertAll(
            heading = "Task entities expected status = ${expectedStatus.name}",
            executables = descriptors.map { descriptor ->
                {
                    val actualStatus = findTask(descriptor).status
                    assertEquals(expectedStatus, actualStatus) { "Task ${descriptor.taskHandle}" }
                }
            },
        )
    }
}
