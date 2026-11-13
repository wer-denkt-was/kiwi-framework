package de.kiwi.backgroundTask

import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.infrastructure.TestBackgroundTaskMessaging
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import java.time.Duration
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

private val log = slf4j<BackgroundTaskTestApplication>()

/**
 * Test application relying on the Kiwi framework and background task auto-configurations, declaring the
 * [TestBackgroundTaskMessaging] and the task handlers of the integration tests.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class BackgroundTaskTestApplication {
    @Bean
    fun testBackgroundTaskMessaging() = TestBackgroundTaskMessaging()

    @Bean
    fun taskInvocations() = TaskInvocations()

    @Bean
    fun exampleTaskHandler(invocations: TaskInvocations) = ExampleTaskHandler(invocations)

    @Bean
    fun specialTaskHandler(invocations: TaskInvocations) = SpecialTaskHandler(invocations)

    @Bean
    fun failingTaskHandler(invocations: TaskInvocations) = FailingTaskHandler(invocations)

    @Bean
    fun retryingTaskHandler(invocations: TaskInvocations) = RetryingTaskHandler(invocations)

    @Bean
    fun progressTaskHandler(invocations: TaskInvocations) = ProgressTaskHandler(invocations)

    @Bean
    fun sentMails() = SentMails()

    @Bean
    fun mailingEventSubscriber(sentMails: SentMails) = MailingEventSubscriber(sentMails)

    @Bean
    fun mailingTaskHandler(invocations: TaskInvocations, sentMails: SentMails) =
        MailingTaskHandler(invocations, sentMails)

    @Bean
    fun cooperativeTaskHandler(invocations: TaskInvocations) = CooperativeTaskHandler(invocations)
}

// --- Task Requests ---

data class ExampleTaskRequest(val message: String, val delayMs: Long) : BackgroundTaskRequest()

data class SpecialTaskRequest(val message: String, val delayMs: Long) : BackgroundTaskRequest() {
    override val category get() = "special"
}

data class FailingTaskRequest(val failureMessage: String, val delayMs: Long) : BackgroundTaskRequest()

/**
 * Request failing its first [failAttempts] attempts, retried with a constant delay of [retryDelayMs] up to
 * [maxAttempts] attempts (unlimited if `null`)
 */
data class RetryingTaskRequest(
    val failAttempts: Int,
    val retryDelayMs: Long,
    val maxAttempts: Int? = null,
) : BackgroundTaskRequest()

/** Request reporting progress in steps, sending a notification before and a custom message after the steps */
data class ProgressTaskRequest(val steps: List<ProgressStep>) : BackgroundTaskRequest() {
    data class ProgressStep(val percent: Int, val delayMs: Long)
}

/**
 * Request sending mails to the [recipients] in stages; the first attempt fails after sending [failAfter] mails (if set),
 * or waits [delayPerMailMs] per mail. The failed attempt is retried after [retryDelayMs] (if set).
 */
data class MailingTaskRequest(
    val recipients: List<String>,
    val failAfter: Int? = null,
    val delayPerMailMs: Long = 0,
    val retryDelayMs: Long? = null,
) : BackgroundTaskRequest()

/** Request running until it notices its cancellation via the context status, without being interrupted */
data class CooperativeTaskRequest(val maxDurationMs: Long) : BackgroundTaskRequest()

/** Request without a handler */
data class UnhandledTaskRequest(val value: Int) : BackgroundTaskRequest()

/** Request of a category without queue or worker */
data class UnknownCategoryTaskRequest(val value: Int) : BackgroundTaskRequest() {
    override val category get() = "unknown"
}

// --- Task Handlers ---

/** Records the handler methods invoked per task handle */
class TaskInvocations {
    data class Invocation(val taskHandle: UUID, val method: String, val user: String)

    private val invocations = CopyOnWriteArrayList<Invocation>()

    fun record(method: String, context: BackgroundTaskContext) {
        invocations.add(Invocation(context.taskHandle, method, context.user.username))
    }

    fun methodsFor(taskHandle: UUID) = invocations.filter { it.taskHandle == taskHandle }.map { it.method }

    fun usersFor(taskHandle: UUID) = invocations.filter { it.taskHandle == taskHandle }.map { it.user }.toSet()
}

abstract class RecordingTaskHandler<R : BackgroundTaskRequest>(
    private val invocations: TaskInvocations,
) : BackgroundTaskHandler<R> {

    protected fun recordHandle(context: BackgroundTaskContext) {
        invocations.record("handle", context)
    }

    override fun onFailure(request: R, exception: Exception, context: BackgroundTaskContext) = DomainResult {
        invocations.record("onFailure", context)
        BackgroundTaskHandler.noRetry()
    }

    override fun onCancel(request: R, context: BackgroundTaskContext) = DomainResult {
        invocations.record("onCancel", context)
    }
}

class ExampleTaskHandler(invocations: TaskInvocations) : RecordingTaskHandler<ExampleTaskRequest>(invocations) {

    override fun handle(request: ExampleTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        log.info("Running example task: ${request.message} (delay=${request.delayMs})")
        Thread.sleep(request.delayMs)
        "Done: ${request.message}"
    }
}

class SpecialTaskHandler(invocations: TaskInvocations) : RecordingTaskHandler<SpecialTaskRequest>(invocations) {

    override fun handle(request: SpecialTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        log.info("Running SPECIAL example task: ${request.message} (delay=${request.delayMs})")
        Thread.sleep(request.delayMs)
        mapOf("special" to request.message)
    }
}

class FailingTaskHandler(invocations: TaskInvocations) :
    RecordingTaskHandler<FailingTaskRequest>(invocations) {

    override fun handle(request: FailingTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        log.info("Running FAILING task: ${request.failureMessage}")
        Thread.sleep(request.delayMs)
        throw IllegalStateException(request.failureMessage)
    }
}

class RetryingTaskHandler(
    private val invocations: TaskInvocations,
) : RecordingTaskHandler<RetryingTaskRequest>(invocations) {

    override fun handle(request: RetryingTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        if (context.attempt <= request.failAttempts) {
            throw IllegalStateException("Attempt ${context.attempt} failed")
        }
        "Done in attempt ${context.attempt}"
    }

    override fun onFailure(request: RetryingTaskRequest, exception: Exception, context: BackgroundTaskContext) =
        DomainResult {
            invocations.record("onFailure", context)
            context.limitRetries(maxAttempts = request.maxAttempts ?: Int.MAX_VALUE) {
                retryWithBackoff(baseDelay = Duration.ofMillis(request.retryDelayMs), exponent = 1.0)
            }
        }
}

class ProgressTaskHandler(invocations: TaskInvocations) : RecordingTaskHandler<ProgressTaskRequest>(invocations) {

    override fun handle(request: ProgressTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        context.sendNotification("progress-started", mapOf("steps" to request.steps.size))
        request.steps.forEach { step ->
            Thread.sleep(step.delayMs)
            context.reportProgress(step.percent)
        }
        context.sendMessage("stepsDone", mapOf("count" to request.steps.size))
        request.steps.size
    }
}

/** Records the mails sent by the [MailingTaskHandler], and the executions of its stages */
open class SentMails {
    private val mails = CopyOnWriteArrayList<String>()
    private val stages = CopyOnWriteArrayList<String>()
    private val events = CopyOnWriteArrayList<MailingEvent>()

    fun recordEvent(event: MailingEvent) = events.add(event)
    fun eventsPublished(request: MailingTaskRequest) = events
        .filter { it.firstRecipient == request.recipients.first() }
        .map { "${it.name}:${it.count}" }

    open fun send(recipient: String) = mails.add(recipient)
    fun recordStage(request: MailingTaskRequest, name: String) = stages.add("${request.recipients.first()}:$name")
    fun mailsTo(recipients: Collection<String>) = mails.filter { it in recipients }
    fun stagesExecuted(request: MailingTaskRequest) = stages
        .filter { it.startsWith("${request.recipients.first()}:") }
        .map { it.substringAfter(":") }
}

/** Sends mails in stages, resumable without sending a mail twice */
class MailingTaskHandler(
    private val invocations: TaskInvocations,
    private val sentMails: SentMails,
) : RecordingTaskHandler<MailingTaskRequest>(invocations) {

    override fun onFailure(request: MailingTaskRequest, exception: Exception, context: BackgroundTaskContext) =
        DomainResult {
            invocations.record("onFailure", context)
            request.retryDelayMs
                ?.let { BackgroundTaskHandler.retry(Duration.ofMillis(it)) }
                ?: BackgroundTaskHandler.noRetry()
        }

    override fun handle(request: MailingTaskRequest, context: BackgroundTaskContext): DomainResult<*> {
        // Outside the stages block, which is invoked twice (planning and execution)
        recordHandle(context)

        // Declare and execute stages
        return context.stages {
            val recipients = stage("prepare", skippable = true, quota = 1) { stage ->
                sentMails.recordStage(request, "prepare")
                request.recipients.distinct().also { stage.record(MailingEvent("prepared", request, it.size)) }
            }
            val sent = stage("send", skippable = true, quota = 8) { stage ->
                sentMails.recordStage(request, "send")

                var sentInAttempt = 0
                stage.forEachItem(recipients.value, itemKey = { it }) { recipient ->
                    if (context.attempt == 1 && sentInAttempt == request.failAfter) {
                        throw IllegalStateException("mail-server-unavailable")
                    }
                    Thread.sleep(request.delayPerMailMs)
                    sentMails.send(recipient)
                    sentInAttempt++
                }
                stage.record(MailingEvent("sent", request, recipients.value.size))
                recipients.value.size
            }
            stage("summarize", skippable = true, quota = 1) {
                sentMails.recordStage(request, "summarize")
                mapOf("sent" to sent.value, "attempt" to context.attempt)
            }
        }
    }
}

/** Polls its status instead of relying on interruption, and notifies the client before stopping */
class CooperativeTaskHandler(invocations: TaskInvocations) : RecordingTaskHandler<CooperativeTaskRequest>(invocations) {

    override fun handle(request: CooperativeTaskRequest, context: BackgroundTaskContext) = DomainResult {
        recordHandle(context)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(request.maxDurationMs)
        while (System.nanoTime() < deadline) {
            if (!context.status.isActive) {
                context.sendNotification("stopping", mapOf("status" to context.status.name))
                context.checkActive()
            }
            // Not interruptible by an exception, unlike Thread.sleep
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
        "finished"
    }
}

/** Event recorded by the stages of the [MailingTaskHandler], published when the stage is completed */
class MailingEvent(val name: String, request: MailingTaskRequest, val count: Int) : DomainEvent() {
    val firstRecipient = request.recipients.first()
    override val details = mapOf("name" to name, "count" to count)
}

/** Records the published [MailingEvent]s in the [SentMails] */
class MailingEventSubscriber(private val sentMails: SentMails) : EventSubscriber<MailingEvent> {
    override fun handle(event: MailingEvent, user: UserDescriptor): DomainResult<Unit> {
        sentMails.recordEvent(event)
        return DomainResult(Unit, emptyList())
    }
}
