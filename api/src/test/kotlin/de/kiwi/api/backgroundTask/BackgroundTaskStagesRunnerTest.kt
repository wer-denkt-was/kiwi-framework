package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests of the [BackgroundTaskStagesRunner], via the [InMemoryBackgroundTaskContext]
 * (recording the reported progress and the completed stages, with an in-memory scratchpad shared between attempts)
 */
class BackgroundTaskStagesRunnerTest {

    private data class TestRequest(val name: String = "stages") : BackgroundTaskRequest()

    private fun newContext() = InMemoryBackgroundTaskContext(TestRequest())

    private class TestEvent(val name: String) : DomainEvent() {
        override val details = mapOf("name" to name)
    }

    private data class MailReceipt(
        val recipient: String,
        val messageId: Int,
    )

    @Test
    fun stagesAreExecutedInOrderWithResults() {
        val context = newContext()
        val executed = mutableListOf<String>()

        val result = context.stages {
            val numbers = stage("generate", skippable = true) {
                executed.add("generate")
                listOf(1, 2, 3)
            }
            val sum = stage("sum", skippable = false, quota = 2) {
                executed.add("sum")
                numbers.value.sum()
            }
            stage("format", skippable = true) {
                executed.add("format")
                "sum=${sum.value}"
            }
        }

        // Stage blocks run once (execution pass), the result of the last stage is the task result
        assertEquals(listOf("generate", "sum", "format"), executed)
        assertEquals("sum=6", result.value)
    }

    @Test
    fun earlierStageResultIsTheTaskResult() {
        val result = newContext().stages {
            val first = stage("first", skippable = false) { "first" }
            stage("second", skippable = false) { "second" }
            first
        }
        assertEquals("first", result.value)
    }

    @Test
    fun progressIsWeightedByQuotas() {
        val context = newContext()

        context.stages {
            stage("first", skippable = false, quota = 1) { }
            stage("second", skippable = false, quota = 3) { stage ->
                stage.totalItems(3)
                repeat(3) { stage.itemsCompleted() }
            }
        }

        // first: 0 -> 25, second: 25 + 75 * (0, 1/3, 2/3, 1) -> 100 when completed
        assertEquals(listOf(0, 25, 25, 25, 50, 75, 100, 100), context.progressPercents)
        val lastStage = context.progress.last().stage!!
        val expectedLastStage =
            BackgroundTaskProgress.Stage("second", index = 1, count = 2, completedItems = 3, totalItems = 3)
        assertEquals(expectedLastStage, lastStage)
    }

    @Test
    fun completedStageReportsAllItemsCompleted() {
        val context = newContext()

        context.stages {
            stage("partial", skippable = false) { stage ->
                stage.totalItems(4)
                stage.itemsCompleted(1) // stage completes without reporting all items
            }
        }

        assertEquals(listOf(0, 0, 25, 100), context.progressPercents)
        assertEquals(4, context.progress.last().stage?.completedItems)
    }

    @Test
    fun indeterminateStageReportsFraction() {
        val context = newContext()

        context.stages {
            stage("only", skippable = false) { stage ->
                stage.fractionCompleted(0.5)
            }
        }

        assertEquals(listOf(0, 50, 100), context.progressPercents)
        assertNull(context.progress.first().stage?.totalItems)
    }

    @Test
    fun invalidProgressFails() {
        val context = newContext()
        context.stages {
            stage("only", skippable = false) { stage ->
                assertThrows<IllegalArgumentException> { stage.fractionCompleted(1.5) }
                assertThrows<IllegalArgumentException> { stage.totalItems(-1) }
                assertThrows<IllegalArgumentException> { stage.itemsCompleted(-1) }
            }
        }
    }

    @Test
    fun stageCannotReportProgressAfterCompletion() {
        val context = newContext()
        var leaked: BackgroundTaskStage? = null

        context.stages {
            stage("first", skippable = false) { stage -> leaked = stage }
            stage("second", skippable = false) {
                assertThrows<IllegalStateException> { leaked!!.itemsCompleted() }
            }
        }
    }

    @Test
    fun forEachItemStoresCheckpoints() {
        val context = newContext()
        val processed = mutableListOf<String>()

        context.stages {
            stage("process", skippable = true) { stage ->
                stage.forEachItem(listOf("a", "b"), itemKey = { it }) {
                    processed.add(it)
                }
            }
        }

        assertEquals(listOf("a", "b"), processed)
        val keys = context.scratchpad.keys
        assertTrue(BackgroundTaskStages.stageItemKey("process", "a") in keys) { "$keys" }
        assertTrue(BackgroundTaskStages.stageItemKey("process", "b") in keys) { "$keys" }
        assertTrue(BackgroundTaskStages.stageResultKey("process") in keys) { "$keys" }
        assertTrue(keys.all { it.startsWith("kiwi:stage:process:") }) { "$keys" }
    }

    @Test
    fun forEachItemAddsToTotalItems() {
        val context = newContext()

        context.stages {
            stage("process", skippable = false) { stage ->
                stage.forEachItem(listOf(1, 2), itemKey = { "first-$it" }) { }
                stage.forEachItem(listOf(1, 2), itemKey = { "second-$it" }) { }
            }
        }

        val itemProgress = context.progress
            .mapNotNull { it.stage }
            .map { it.completedItems to it.totalItems }

        assertEquals(
            listOf(
                null to null, // initial
                0 to 2,
                1 to 2,
                2 to 2,
                2 to 4,
                3 to 4,
                4 to 4,
                4 to 4, // final
            ),
            itemProgress,
        )
    }

    @Test
    fun mapEachItemReturnsMappedValues() {
        val context = newContext()

        val result = context.stages {
            stage("send", skippable = true) { stage ->
                stage.mapEachItem(listOf("a@test", "b@test"), itemKey = { it }) { recipient ->
                    MailReceipt(recipient, recipient.length)
                }
            }
        }

        assertEquals(
            listOf(
                MailReceipt("a@test", 6),
                MailReceipt("b@test", 6),
            ),
            result.value,
        )
    }

    @Test
    fun resumedTaskSkipsCompletedStagesAndItems() {
        val firstAttempt = newContext()
        val generated = mutableListOf<Int>()
        val processed = mutableListOf<String>()

        fun BackgroundTaskContext.runStages(failAt: String?) = stages {
            val items = stage("generate", skippable = true) {
                generated.add(attempt)
                listOf("a", "b", "c", "d")
            }
            stage("process", skippable = true) { stage ->
                stage.forEachItem(items.value, itemKey = { it }) { item ->
                    if (item == failAt) throw IllegalStateException("failed at $item")
                    processed.add(item)
                }
            }
        }

        assertThrows<IllegalStateException> { firstAttempt.runStages(failAt = "c") }
        assertEquals(listOf("a", "b"), processed)

        // The resumed attempt restores the generated items, and skips the processed items
        val secondAttempt = firstAttempt.resumed()
        secondAttempt.runStages(failAt = null)
        assertEquals(listOf("a", "b", "c", "d"), processed)
        assertEquals(listOf(1), generated)

        // Progress: The skipped stage isn't reported, the resumed stage starts with the completed items, and the
        // progress doesn't drop below the progress of the first attempt (generate: 50 + process: 50 * 2/4)
        assertEquals(75, firstAttempt.progressPercents.last())
        assertEquals(listOf(75, 75, 87, 100, 100), secondAttempt.progressPercents)
        assertTrue(secondAttempt.progress.all { it.stage?.name == "process" }) { "${secondAttempt.progress}" }
        val resumedStage = secondAttempt.progress.mapNotNull { it.stage }.first { it.totalItems != null }
        assertEquals(2 to 4, resumedStage.completedItems to resumedStage.totalItems)
    }

    @Test
    fun mappedItemsAreRestoredOnResume() {
        val firstAttempt = newContext()
        val sent = mutableListOf<String>()

        fun BackgroundTaskContext.runStages(failAt: String?) = stages {
            stage("send", skippable = true) { stage ->
                stage.mapEachItem(listOf("a", "b", "c"), itemKey = { it }) { recipient ->
                    if (recipient == failAt) throw IllegalStateException("failed at $recipient")
                    sent.add(recipient)
                    MailReceipt(recipient, messageId = attempt)
                }
            }
        }

        assertThrows<IllegalStateException> { firstAttempt.runStages(failAt = "b") }
        val result = firstAttempt.resumed().runStages(failAt = null)

        // Receipts of the first attempt are restored from the item checkpoints
        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(listOf(MailReceipt("a", 1), MailReceipt("b", 2), MailReceipt("c", 2)), result.value)
    }

    @Test
    fun unitMappedItemsAreRestoredOnResume() {
        val firstAttempt = newContext()
        assertThrows<IllegalStateException> {
            firstAttempt.stages {
                stage("process", skippable = true) { stage ->
                    stage.mapEachItem(listOf(1, 2), itemKey = { "$it" }) { if (it == 2) throw IllegalStateException() }
                }
            }
        }

        val result = firstAttempt.resumed().stages {
            stage("process", skippable = true) { stage ->
                stage.mapEachItem(listOf(1, 2), itemKey = { "$it" }) { }
            }
        }
        assertEquals(listOf(Unit, Unit), result.value)
    }

    @Test
    fun nonSkippableStageIsExecutedAgainOnResume() {
        val firstAttempt = newContext()
        val executed = mutableListOf<Pair<String, Int>>()
        val processed = mutableListOf<Int>()

        fun BackgroundTaskContext.runStages(fail: Boolean) = stages {
            val connection = stage("connect", skippable = false) {
                executed.add("connect" to attempt)
                "connection-$attempt"
            }
            stage("process", skippable = false) { stage ->
                executed.add("process" to attempt)
                stage.forEachItem(listOf(1, 2), itemKey = { "$it" }) { item ->
                    if (fail && item == 2) throw IllegalStateException("failed")
                    processed.add(item)
                }
                connection.value
            }
        }

        assertThrows<IllegalStateException> { firstAttempt.runStages(fail = true) }
        val result = firstAttempt.resumed().runStages(fail = false)

        // Stages are executed again, but completed items are still skipped; no stage results are stored
        assertEquals(listOf("connect" to 1, "process" to 1, "connect" to 2, "process" to 2), executed)
        assertEquals(listOf(1, 2), processed)
        assertEquals("connection-2", result.value)
        assertFalse(BackgroundTaskStages.stageResultKey("connect") in firstAttempt.scratchpad)
    }

    @Test
    fun unitAndNullResultsAreRestored() {
        val firstAttempt = newContext()
        assertThrows<IllegalStateException> {
            firstAttempt.stages {
                stage("unit", skippable = true) { }
                stage("null", skippable = true) { null as String? }
                stage<Unit>("fail", skippable = true) { throw IllegalStateException("fail") }
            }
        }

        var nullResult: String? = "not restored"
        firstAttempt.resumed().stages {
            val unit = stage<Unit>("unit", skippable = true) { fail("Completed stage executed again") }
            val nullable = stage<String?>("null", skippable = true) { fail("Completed stage executed again") }
            stage("fail", skippable = true) {
                assertEquals(Unit, unit.value)
                nullResult = nullable.value
            }
        }
        assertNull(nullResult)
    }

    @Test
    fun genericResultsAreRestored() {
        val firstAttempt = newContext()
        assertThrows<IllegalStateException> {
            firstAttempt.stages {
                stage("receipts", skippable = true) { mapOf("a" to listOf(MailReceipt("a", 1))) }
                stage<Unit>("fail", skippable = true) { throw IllegalStateException("fail") }
            }
        }

        val result = firstAttempt.resumed().stages {
            val receipts = stage("receipts", skippable = true) {
                fail<Map<String, List<MailReceipt>>>("Completed stage executed again")
            }
            stage("fail", skippable = true) { receipts.value.getValue("a").single() }
        }
        assertEquals(MailReceipt("a", 1), result.value)
    }

    @Test
    fun accessingResultOutsideStagesFails() {
        val exception = assertThrows<IllegalStateException> {
            newContext().stages {
                val first = stage("first", skippable = false) { 1 }
                first.value + 1 // accessing the value outside a stage must fail
                first
            }
        }
        assertTrue(exception.message!!.contains("[first]")) { exception.message }
    }

    @Test
    fun stagesMustMatchPlan() {
        // Stages declared conditionally on a mutable state differ between planning and execution
        var flip = true
        assertThrows<IllegalStateException> {
            newContext().stages {
                val first = stage("first", skippable = false) { }
                if (!flip) stage("additional", skippable = false) { }
                flip = false
                first
            }
        }

        flip = true
        assertThrows<IllegalStateException> {
            newContext().stages {
                val first = stage("first", skippable = false) { }
                if (flip) stage("missing", skippable = false) { }
                flip = false
                first
            }
        }

        flip = true
        assertThrows<IllegalStateException> {
            newContext().stages {
                stage("first", skippable = false, quota = if (flip) 1 else 2) { }
                    .also { flip = false }
            }
        }
    }

    @Test
    fun invalidStagesFail() {
        // Same stage name declared twice
        assertThrows<IllegalArgumentException> {
            newContext().stages {
                stage("same", skippable = false) { }
                stage("same", skippable = false) { }
            }
        }
        // Invalid quota (would lead to a division by zero)
        assertThrows<IllegalArgumentException> {
            newContext().stages {
                stage("zero", skippable = false, quota = 0) { }
            }
        }
        // Blank name
        assertThrows<IllegalArgumentException> {
            newContext().stages {
                stage(" ", skippable = false) { }
            }
        }
        // Name exceeding the maximum key length
        assertThrows<IllegalArgumentException> {
            newContext().stages {
                stage("x".repeat(BackgroundTaskScratchpad.MAX_KEY_LENGTH), skippable = false) { }
            }
        }
    }

    @Test
    fun duplicateItemKeysFail() {
        val processed = mutableListOf<Int>()
        assertThrows<IllegalArgumentException> {
            newContext().stages {
                stage("process", skippable = false) { stage ->
                    stage.forEachItem(listOf(1, 2, 3), itemKey = { if (it == 3) "1" else "$it" }) {
                        processed.add(it)
                    }
                }
            }
        }
        assertTrue(processed.isEmpty())
    }

    @Test
    fun canceledTaskStopsBetweenItems() {
        val context = newContext()
        val processed = mutableListOf<Int>()

        assertThrows<InterruptedException> {
            context.stages {
                stage("process", skippable = false) { stage ->
                    stage.forEachItem((1..5).toList(), itemKey = { "$it" }) { item ->
                        processed.add(item)
                        if (item == 2) context.cancel() // cancel midway through the items
                    }
                }
            }
        }
        assertEquals(listOf(1, 2), processed)
    }

    @Test
    fun canceledTaskStopsBetweenStages() {
        val context = newContext()
        val executed = mutableListOf<String>()

        assertThrows<InterruptedException> {
            context.stages {
                stage("first", skippable = false) {
                    executed.add("first")
                    context.cancel()
                }
                stage("second", skippable = false) {
                    executed.add("second")
                }
            }
        }
        assertEquals(listOf("first"), executed)
    }

    @Test
    fun stageEventsArePublishedWhenStageIsCompleted() {
        val context = newContext()

        val result = context.stages {
            stage("first", skippable = true) { stage ->
                stage.record(TestEvent("first"))
                // Not published before the stage is completed
                assertTrue(context.completedStages.isEmpty())
                assertTrue(context.publishedEvents.isEmpty())
            }
            stage("second", skippable = false) { stage ->
                assertEquals(listOf("first"), context.completedStages)
                stage.record(DomainResult("value", listOf(TestEvent("second-a"), TestEvent("second-b"))))
            }
        }

        // Events are published per stage, together with the result checkpoint of skippable stages
        assertEquals(listOf("first", "second"), context.completedStages)
        assertEquals(listOf("first", "second-a", "second-b"), context.publishedEvents.map { (it as TestEvent).name })
        assertTrue(BackgroundTaskStages.stageResultKey("first") in context.scratchpad)
        assertFalse(BackgroundTaskStages.stageResultKey("second") in context.scratchpad)
        assertEquals("value", result.value)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun stageEventsArePublishedOnceWhenResumed() {
        val firstAttempt = newContext()

        fun BackgroundTaskContext.runStages(fail: Boolean) = stages {
            stage("first", skippable = true) { stage ->
                stage.record(TestEvent("first-$attempt"))
            }
            stage("second", skippable = true) { stage ->
                stage.record(TestEvent("second-$attempt"))
                if (fail) throw IllegalStateException("failed")
            }
        }

        assertThrows<IllegalStateException> { firstAttempt.runStages(fail = true) }
        val secondAttempt = firstAttempt.resumed()
        secondAttempt.runStages(fail = false)

        // Events of the failed stage are discarded, events of the skipped stage aren't published again
        assertEquals(listOf("first-1"), firstAttempt.publishedEvents.map { (it as TestEvent).name })
        assertEquals(listOf("second-2"), secondAttempt.publishedEvents.map { (it as TestEvent).name })
        assertEquals(listOf("second"), secondAttempt.completedStages)
    }
}
