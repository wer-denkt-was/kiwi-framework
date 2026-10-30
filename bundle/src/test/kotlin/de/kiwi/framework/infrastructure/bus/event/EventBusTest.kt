package de.kiwi.framework.infrastructure.bus.event

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

class EventBusTest {
    private lateinit var eventBus: EventBus.Configurable
    private lateinit var basicBus: BasicEventBus
    private lateinit var auditService: TestAuditLoggerService

    @BeforeEach
    fun setUp() {
        auditService = TestAuditLoggerService()
        basicBus = BasicEventBus(enableCaching = true)
        eventBus = RecursiveEventBus(
            maxRecursionDepth = 10,
            delegate = AuditingEventBus(
                auditLoggerService = AuditLoggerService.Delegate(buildList {
                    add(AuditLoggerService.Console())
                    add(auditService)
                }),
                delegate = basicBus,
            )
        )
    }

    @Test
    fun publish() {
        val firstSubscriber = FirstEventSubscriber()
        val secondSubscriber = SecondEventSubscriber()
        val anotherSecondSubscriber = SecondEventSubscriber()
        eventBus.subscribe(firstSubscriber)
        eventBus.subscribe(secondSubscriber)
        eventBus.subscribe(anotherSecondSubscriber)

        eventBus.publish(listOf(FirstEvent("Alpha"), SecondEvent("Beta")), TestUser)
        eventBus.publish(listOf(FirstEvent("Gamma")), TestUser)

        assertThat(
            firstSubscriber.events,
            containsInAnyOrder(
                matchEvent<FirstEvent>(details = equalTo("Alpha")),
                matchEvent<FirstEvent>(details = equalTo("Gamma")),
            )
        )
        assertThat(
            secondSubscriber.events,
            containsInAnyOrder(
                matchEvent<SecondEvent>(details = equalTo("Beta"))
            )
        )
        assertThat(
            anotherSecondSubscriber.events,
            containsInAnyOrder(
                matchEvent<SecondEvent>(details = equalTo("Beta"))
            )
        )

        assertThat(
            auditService.logs,
            contains(
                matchEventAndUser<FirstEvent>(details = equalTo("Alpha"), user = TestUser),
                matchEventAndUser<SecondEvent>(details = equalTo("Beta"), user = TestUser),
                matchEventAndUser<FirstEvent>(details = equalTo("Gamma"), user = TestUser),
            )
        )
    }

    @Test
    fun subscribeAfterPublishInvalidatesCache() {
        val firstSubscriber = FirstEventSubscriber()
        val lateSubscriber = FirstEventSubscriber()
        eventBus.subscribe(firstSubscriber)

        eventBus.publish(listOf(FirstEvent("Alpha")), TestUser)
        eventBus.subscribe(lateSubscriber)
        eventBus.publish(listOf(FirstEvent("Beta")), TestUser)

        assertThat(
            firstSubscriber.events,
            contains(
                matchEvent<FirstEvent>(details = equalTo("Alpha")),
                matchEvent<FirstEvent>(details = equalTo("Beta")),
            )
        )
        assertThat(
            lateSubscriber.events,
            contains(matchEvent<FirstEvent>(details = equalTo("Beta")))
        )
    }

    @Test
    fun publishRecursive() {
        val userCreatedEventSubscriber = UserCreatedEventSubscriber()
        val userEmailSentEventSubscriber = UserEmailSentEventSubscriber()
        val whenUserCreatedThenSendEmail = WhenUserCreatedThenSendEmail()
        val whenUserEmailSentThenSetFlag = WhenUserEmailSentThenSetFlag()

        eventBus.subscribe(userCreatedEventSubscriber)
        eventBus.subscribe(userEmailSentEventSubscriber)
        eventBus.subscribe(whenUserCreatedThenSendEmail)
        eventBus.subscribe(whenUserEmailSentThenSetFlag)
        assertThat(whenUserEmailSentThenSetFlag.flag, equalTo(false))

        val recursiveEvents = eventBus.publish(listOf(UserCreatedEvent("TestUser")), TestUser)

        // Assertion: Recursive events have reached the deepest subscriber
        assertThat(whenUserEmailSentThenSetFlag.flag, equalTo(true))

        // Assertion: Top-level event has reached subscriber
        assertThat(
            userCreatedEventSubscriber.events,
            containsInAnyOrder(
                matchEvent<UserCreatedEvent>(details = equalTo("TestUser")),
            )
        )

        // Assertion: First-level recursive event has reached subscriber
        assertThat(
            userEmailSentEventSubscriber.events,
            containsInAnyOrder(
                matchEvent<UserEmailSentEvent>(details = equalTo("TestUser")),
            )
        )

        // Assertion: All events and recursive events have been recorded in audit-log
        assertThat(
            auditService.logs,
            contains(
                matchEventAndUser<UserCreatedEvent>(details = equalTo("TestUser"), user = TestUser),
                matchEventAndUser<UserEmailSentEvent>(details = equalTo("TestUser"), user = TestUser),
                matchEventAndUser<UserEmailFlagSetEvent>(details = equalTo("TestUser"), user = TestUser),
            )
        )

        // Assertion: Event-bus has returned all recursive events
        assertThat(
            recursiveEvents,
            contains(
                matchEvent<UserEmailSentEvent>(details = equalTo("TestUser")),
                matchEvent<UserEmailFlagSetEvent>(details = equalTo("TestUser")),
            ),
        )
    }
}

private inline fun <reified E : DomainEvent> matchEventAndUser(details: Matcher<*>, user: UserDescriptor) =
    matchPair(matchEvent<E>(details), sameInstance(user))

private inline fun <reified E : DomainEvent> matchEvent(details: Matcher<*>): Matcher<DomainEvent> =
    allOf(instanceOf(E::class.java), hasProperty("details", details))

private fun matchPair(first: Matcher<*>, second: Matcher<*>): Matcher<Pair<*, *>> =
    allOf(hasProperty("first", first), hasProperty("second", second))

private class TestAuditLoggerService : AuditLoggerService {
    val logs = ArrayList<Pair<Auditable, UserDescriptor>>()

    override fun auditLog(event: Auditable, user: UserDescriptor) {
        logs.add(event to user)
    }
}

// --- Shared setup

private val testTimestamp = OffsetDateTime.parse("2024-06-16T18:30:00Z")
private val testClock = Clock.fixed(testTimestamp.toInstant(), ZoneOffset.UTC)

private object TestUser : UserDescriptor {
    override val userId = null
    override val identity = this
    override val username = "TestUser"
    override val authenticated = true
    override val roles = emptySet<String>()
    override val principal = null
}

private abstract class RecordingEventSubscriber<E : DomainEvent> : EventSubscriber<E> {
    val events = ArrayList<E>()

    override fun handle(event: E, user: UserDescriptor): DomainResult<Unit> {
        events.add(event)
        return DomainResult.empty
    }
}

// --- Simple event publish

private class FirstEvent(override val details: String) : DomainEvent(testClock)
private class SecondEvent(override val details: String) : DomainEvent(testClock)

private class FirstEventSubscriber : RecordingEventSubscriber<FirstEvent>()
private class SecondEventSubscriber : RecordingEventSubscriber<SecondEvent>()

// --- Recursive event publish

private class UserCreatedEvent(override val details: String) : DomainEvent(testClock)
private class UserEmailSentEvent(override val details: String) : DomainEvent(testClock)
private class UserEmailFlagSetEvent(override val details: String) : DomainEvent(testClock)

private class UserCreatedEventSubscriber : RecordingEventSubscriber<UserCreatedEvent>()
private class UserEmailSentEventSubscriber : RecordingEventSubscriber<UserEmailSentEvent>()

private class WhenUserCreatedThenSendEmail : EventSubscriber<UserCreatedEvent> {
    override fun handle(event: UserCreatedEvent, user: UserDescriptor) = DomainResult<Unit> { record ->
        record(UserEmailSentEvent(event.details))
    }
}

private class WhenUserEmailSentThenSetFlag : EventSubscriber<UserEmailSentEvent> {
    var flag = false

    override fun handle(event: UserEmailSentEvent, user: UserDescriptor) = DomainResult<Unit> { record ->
        flag = true
        record(UserEmailFlagSetEvent(event.details))
    }
}
