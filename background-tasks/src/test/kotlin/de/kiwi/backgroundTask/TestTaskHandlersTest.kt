package de.kiwi.backgroundTask

import de.kiwi.api.backgroundTask.BackgroundTaskStages
import de.kiwi.api.backgroundTask.InMemoryBackgroundTaskContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests of the task handlers of the integration tests, using the [InMemoryBackgroundTaskContext] of the API
 * (without workers, queue, messaging, or database)
 */
class TestTaskHandlersTest {
    private val invocations = TaskInvocations()
    private val sentMails = SentMails()

    @Test
    fun mailingTaskSendsMailsInStages() {
        val handler = MailingTaskHandler(invocations, sentMails)
        val request = MailingTaskRequest(recipients = listOf("a@test", "b@test", "a@test"))
        val context = InMemoryBackgroundTaskContext(request)

        val result = handler.handle(request, context)

        assertEquals(mapOf("sent" to 2, "attempt" to 1), result.value)
        assertEquals(listOf("a@test", "b@test"), sentMails.mailsTo(request.recipients))
        assertEquals(listOf("prepare", "send", "summarize"), context.completedStages)
        val events = context.publishedEvents.map { it as MailingEvent }
        assertEquals(listOf("prepared" to 2, "sent" to 2), events.map { it.name to it.count })
        assertEquals(100, context.lastProgress?.percent)
        assertEquals(listOf("handle"), invocations.methodsFor(context.taskHandle))
    }

    @Test
    fun resumedMailingTaskDoesNotSendMailsTwice() {
        val handler = MailingTaskHandler(invocations, sentMails)
        val request = MailingTaskRequest(recipients = listOf("1@test", "2@test", "3@test", "4@test"), failAfter = 2)
        val firstAttempt = InMemoryBackgroundTaskContext(request)

        val failure = assertThrows<IllegalStateException> { handler.handle(request, firstAttempt) }
        assertEquals("mail-server-unavailable", failure.message)
        assertEquals(listOf("prepare"), firstAttempt.completedStages)

        val secondAttempt = firstAttempt.resumed()
        val result = handler.handle(request, secondAttempt)

        // The prepared recipients are restored, mails sent by the first attempt aren't sent again
        assertEquals(mapOf("sent" to 4, "attempt" to 2), result.value)
        assertEquals(request.recipients, sentMails.mailsTo(request.recipients))
        assertEquals(listOf("prepare", "send", "send", "summarize"), sentMails.stagesExecuted(request))
        assertEquals(listOf("send", "summarize"), secondAttempt.completedStages)
        assertTrue(BackgroundTaskStages.stageResultKey("prepare") in secondAttempt.scratchpad)
    }

    @Test
    fun canceledMailingTaskStopsBetweenMails() {
        val request = MailingTaskRequest(recipients = listOf("x@test", "y@test", "z@test"))
        val context = InMemoryBackgroundTaskContext(request)
        val cancelingMails = object : SentMails() {
            override fun send(recipient: String) = super.send(recipient).also { context.cancel() }
        }

        val handler = MailingTaskHandler(invocations, cancelingMails)
        assertThrows<InterruptedException> { handler.handle(request, context) }
        assertEquals(listOf("x@test"), cancelingMails.mailsTo(request.recipients))
    }

    @Test
    fun progressTaskReportsStepsAndMessages() {
        val handler = ProgressTaskHandler(invocations)
        val steps = listOf(30, 60, 100).map { ProgressTaskRequest.ProgressStep(it, delayMs = 0) }
        val request = ProgressTaskRequest(steps)
        val context = InMemoryBackgroundTaskContext(request)

        val result = handler.handle(request, context)

        assertEquals(3, result.value)
        assertEquals(listOf(30, 60, 100), context.progressPercents)
        val notification = InMemoryBackgroundTaskContext.Notification("progress-started", mapOf("steps" to 3))
        assertEquals(listOf(notification), context.notifications)
        assertEquals(listOf(InMemoryBackgroundTaskContext.Message("stepsDone", mapOf("count" to 3))), context.messages)
    }
}
