package de.kiwi.api.domain

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.*
import java.time.*

class DomainResultTest {

    @Test
    fun testRun() {
        val result = DomainResult { record ->
            record(FirstEvent())
            record(SecondEvent())
            "Success"
        }

        assertThat(
            result.value,
            equalTo("Success")
        )
        assertThat(
            result.events,
            containsInAnyOrder(
                instanceOf(FirstEvent::class.java),
                instanceOf(SecondEvent::class.java),
            )
        )
    }

    @Test
    fun testMerge() {
        val first = DomainResult(value = Unit, events = listOf(FirstEvent()))
        val secondAndThird = DomainResult(value = Unit, events = listOf(SecondEvent(), ThirdEvent()))
        val merged = DomainResult.merge(first, secondAndThird)

        assertThat(
            merged.events,
            containsInAnyOrder(
                instanceOf(FirstEvent::class.java),
                instanceOf(SecondEvent::class.java),
                instanceOf(ThirdEvent::class.java),
            )
        )
    }
}

private val testClock = Clock.fixed(Instant.now(), ZoneOffset.UTC)

private class FirstEvent : DomainEvent(testClock) {
    override val details = "First"
}

private class SecondEvent : DomainEvent(testClock) {
    override val details = "Second"
}

private class ThirdEvent : DomainEvent(testClock) {
    override val details = "Third"
}
