package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InMemoryBackgroundTaskContextTest {

    private data class CountRequest(val count: Int) : BackgroundTaskRequest()

    private class CountedEvent(val count: Int) : DomainEvent() {
        override val details = mapOf("count" to count)
    }

    class TestItemId(value: Long) : EntityId(value)

    private data class Item(val id: TestItemId, val name: String)

    /** Counts to the requested number in stages, checkpointing each number */
    private class CountingHandler(private val failAt: Int? = null) : BackgroundTaskHandler<CountRequest> {
        val counted = mutableListOf<Int>()

        override fun handle(request: CountRequest, context: BackgroundTaskContext) = context.stages {
            val numbers = stage("prepare", skippable = true) { (1..request.count).toList() }
            stage("count", skippable = true, quota = 3) { stage ->
                stage.forEachItem(numbers.value, itemKey = { "$it" }) { number ->
                    if (context.attempt == 1 && number == failAt) throw IllegalStateException("failed at $number")
                    counted.add(number)
                    context.sendNotification("counted", mapOf("number" to number))
                }
                stage.record(CountedEvent(numbers.value.size))
                numbers.value.size
            }
        }

        override fun onFailure(request: CountRequest, exception: Exception, context: BackgroundTaskContext) =
            DomainResult { BackgroundTaskHandler.noRetry() }

        override fun onCancel(request: CountRequest, context: BackgroundTaskContext) = DomainResult { }
    }

    @Test
    fun contextRecordsProgressAndMessages() {
        val context = InMemoryBackgroundTaskContext(CountRequest(2))

        context.reportProgress(10)
        context.reportProgress(BackgroundTaskProgress(20, BackgroundTaskProgress.Stage("stage", 0, 1)))
        context.sendNotification("hello", mapOf("name" to "World"))
        context.sendMessage("custom", listOf(1, 2))

        assertEquals(listOf(10, 20), context.progressPercents)
        assertEquals("stage", context.lastProgress?.stage?.name)
        val notification = InMemoryBackgroundTaskContext.Notification("hello", mapOf("name" to "World"))
        assertEquals(listOf(notification), context.notifications)
        assertEquals(listOf(InMemoryBackgroundTaskContext.Message("custom", listOf(1, 2))), context.messages)
        assertEquals(1, context.attempt)
        assertFalse(context.isResumed)
        assertEquals(UserDescriptor.SYSTEM_USERNAME, context.user.username)
    }

    @Test
    fun handlerRunsInStages() {
        val handler = CountingHandler()
        val context = InMemoryBackgroundTaskContext(CountRequest(3))

        val result = handler.handle(CountRequest(3), context)

        assertEquals(3, result.value)
        assertEquals(listOf(1, 2, 3), handler.counted)
        assertEquals(listOf("prepare", "count"), context.completedStages)
        assertEquals(listOf(3), context.publishedEvents.map { (it as CountedEvent).count })
        assertEquals(listOf(1, 2, 3), context.notifications.map { it.arguments["number"] })
        assertEquals(100, context.lastProgress?.percent)
    }

    @Test
    fun resumedContextSkipsCompletedWork() {
        val handler = CountingHandler(failAt = 3)
        val firstAttempt = InMemoryBackgroundTaskContext(CountRequest(4))
        assertThrows<IllegalStateException> { handler.handle(CountRequest(4), firstAttempt) }
        assertEquals(listOf(1, 2), handler.counted)
        assertTrue(firstAttempt.publishedEvents.isEmpty())

        val secondAttempt = firstAttempt.resumed()
        val result = handler.handle(CountRequest(4), secondAttempt)

        // Completed numbers are skipped, the progress doesn't drop below the progress of the first attempt
        assertEquals(4, result.value)
        assertEquals(listOf(1, 2, 3, 4), handler.counted)
        assertEquals(2, secondAttempt.attempt)
        assertTrue(secondAttempt.isResumed)
        assertEquals(firstAttempt.taskHandle, secondAttempt.taskHandle)
        assertEquals(listOf("count"), secondAttempt.completedStages)
        assertTrue(secondAttempt.progressPercents.all { it >= firstAttempt.lastProgress!!.percent })
    }

    @Test
    fun canceledContextStopsHandler() {
        val context = InMemoryBackgroundTaskContext(CountRequest(1))
        context.checkActive()

        context.cancel()
        assertEquals(BackgroundTaskExecutionStatus.CancelRequested, context.status)
        assertThrows<InterruptedException> { context.checkActive() }

        val aborted = InMemoryBackgroundTaskContext(CountRequest(1)).apply { abort() }
        assertEquals(BackgroundTaskExecutionStatus.AbortRequested, aborted.status)
        assertThrows<InterruptedException> { aborted.checkActive() }

        // The context of the next attempt is running again
        assertEquals(BackgroundTaskExecutionStatus.Running, context.resumed().status)
    }

    @Test
    fun scratchpadStoresValuesAsJson() {
        val scratchpad = InMemoryBackgroundTaskScratchpad()
        val item = Item(TestItemId(42), "answer")

        scratchpad.put("item", item)
        scratchpad.put("items", listOf(item))
        scratchpad.put("nothing", null)

        // Values are deserialized as the requested type (also generic types and entity IDs), including null values
        assertEquals(item, scratchpad.get<Item>("item"))
        assertEquals(listOf(item), scratchpad.get<List<Item>>("items"))
        assertNull(scratchpad.get<Item>("nothing"))
        assertTrue("nothing" in scratchpad)
        assertEquals("null", scratchpad.getJson("nothing"))
        assertNull(scratchpad.getJson("absent"))
        assertEquals(setOf("item", "items", "nothing"), scratchpad.keys)

        scratchpad.remove("item")
        assertFalse("item" in scratchpad)
    }

    @Test
    fun scratchpadRejectsInvalidKeys() {
        val scratchpad = InMemoryBackgroundTaskScratchpad()

        assertThrows<IllegalArgumentException> { scratchpad.put(" ", 1) }
        val tooLongKey = "x".repeat(BackgroundTaskScratchpad.MAX_KEY_LENGTH + 1)
        assertThrows<IllegalArgumentException> { scratchpad.put(tooLongKey, 1) }
        assertThrows<IllegalArgumentException> { scratchpad.remove(" ") }
    }
}
