package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.infrastructure.bus.event.BasicEventBus
import de.kiwi.framework.infrastructure.bus.event.RecursiveEventBus
import de.kiwi.framework.testUserDescriptor
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CommandBusTest {
    private lateinit var eventBus: TestEventBus
    private lateinit var commandBus: CommandBus.Configurable
    private lateinit var basicCommandBus: BasicCommandBus

    private val maxRecursionDepth = 10

    @BeforeEach
    fun setUp() {
        eventBus = TestEventBus(BasicEventBus(enableCaching = true))
        basicCommandBus = BasicCommandBus(enableCaching = true)
        commandBus = TransactionalCommandBus(
            transactionService = TransactionService.DoNothing,
            delegate = AuthorizedCommandBus(
                delegate = EventPublishingCommandBus(
                    eventBus = RecursiveEventBus(
                        maxRecursionDepth = maxRecursionDepth,
                        delegate = eventBus,
                    ),
                    delegate = basicCommandBus,
                )
            ),
        )
    }

    @Test
    fun submit() {
        val firstHandler = FirstCommandHandler()
        val secondHandler = SecondCommandHandler()
        commandBus.register(firstHandler)
        commandBus.register(secondHandler)

        val user = testUserDescriptor(username = "test")
        commandBus.submit(FirstCommand(), user)
        commandBus.submit(SecondCommand(value = 1), user)
        commandBus.submit(SecondCommand(value = 2), user)

        assertThat(
            firstHandler.handledCommands,
            contains(
                instanceOf(FirstCommand::class.java)
            )
        )
        assertThat(
            secondHandler.handledCommands,
            contains(
                allOf(instanceOf(SecondCommand::class.java)),
                allOf(instanceOf(SecondCommand::class.java)),
            )
        )
        assertThat(
            eventBus.publishedEvents,
            contains(
                instanceOf(FirstEvent::class.java),
                allOf(instanceOf(SecondEvent::class.java), hasProperty("details", equalTo(1))),
                allOf(instanceOf(SecondEvent::class.java), hasProperty("details", equalTo(2))),
            )
        )
    }

    @Test
    fun infiniteRecursivePairEvents() {
        commandBus.register(TriggerBrokenEvents1CommandHandler())
        eventBus.subscribe(BrokenEventATriggerB())
        eventBus.subscribe(BrokenEventBTriggerA())

        val user = testUserDescriptor(username = "test")
        val command = TriggerBrokenEventsPairCommand()
        val illegalState = assertThrows<IllegalStateException> {
            commandBus.submit(command, user)
        }

        assertEquals(
            "Exceeded recursion depth [$maxRecursionDepth] while handling Command: ${command.javaClass.name}",
            illegalState.message,
        )
    }

    @Test
    fun infiniteSelfRecursiveEvent() {
        commandBus.register(TriggerBrokenEvents2CommandHandler())
        eventBus.subscribe(BrokenEventCTriggerC())

        val user = testUserDescriptor(username = "test")
        val command = TriggerBrokenEventsSelfCommand()
        val illegalState = assertThrows<IllegalStateException> {
            commandBus.submit(command, user)
        }

        assertEquals(
            "Exceeded recursion depth [$maxRecursionDepth] while handling Command: ${command.javaClass.name}",
            illegalState.message,
        )
    }

    @Test
    fun cacheHitRate() {
        commandBus.register(FirstCommandHandler())
        commandBus.register(SecondCommandHandler())

        val user = testUserDescriptor(username = "test")
        commandBus.submit(FirstCommand(), user)
        commandBus.submit(FirstCommand(), user)
        commandBus.submit(FirstCommand(), user)
        commandBus.submit(FirstCommand(), user)
        commandBus.submit(SecondCommand(value = 1), user)
        commandBus.submit(SecondCommand(value = 2), user)
        commandBus.submit(SecondCommand(value = 3), user)
        commandBus.submit(SecondCommand(value = 4), user)

        // 6/8 submit lookups are cached
        println("Command cache hit rate = ${basicCommandBus.cacheHitRate}")
        assertEquals(0.75, basicCommandBus.cacheHitRate, 0.001)
    }
}

private class TestEventBus(private val delegate: EventBus.Configurable) : EventBus.Configurable by delegate {
    val publishedEvents = ArrayList<DomainEvent>()

    override fun publish(events: Iterable<DomainEvent>, user: UserDescriptor): Collection<DomainEvent> {
        assertThat(user.username, equalToIgnoringCase("test"))
        events.forEach { publishedEvents.add(it) }
        return delegate.publish(events, user)
    }
}

// --- Basic commands

private class FirstCommand : Command<Unit>()
private class FirstEvent : DomainEvent() {
    override val details = Unit
}

private data class SecondCommand(val value: Int) : Command<Unit>()
private data class SecondEvent(override val details: Int) : DomainEvent()

private class FirstCommandHandler : CommandHandler<FirstCommand, Unit> {
    val handledCommands = ArrayList<FirstCommand>()

    override fun handle(command: FirstCommand, user: UserDescriptor): DomainResult<Unit> {
        handledCommands.add(command)
        assertThat(user.username, equalToIgnoringCase("test"))
        return DomainResult.events(FirstEvent())
    }
}

private class SecondCommandHandler : CommandHandler<SecondCommand, Unit> {
    val handledCommands = ArrayList<SecondCommand>()

    override fun handle(command: SecondCommand, user: UserDescriptor): DomainResult<Unit> {
        handledCommands.add(command)
        assertThat(user.username, equalToIgnoringCase("test"))
        return DomainResult.events(SecondEvent(details = command.value))
    }
}

// --- Broken events with infinite recursion (A -> B -> A -> B -> ...), or (C -> C -> ...)

private class TriggerBrokenEventsPairCommand : Command<Unit>()

private class TriggerBrokenEventsSelfCommand : Command<Unit>()

private class TriggerBrokenEvents1CommandHandler : CommandHandler<TriggerBrokenEventsPairCommand, Unit> {
    override fun handle(command: TriggerBrokenEventsPairCommand, user: UserDescriptor) = DomainResult { record ->
        record(BrokenEventA())
    }
}

private class TriggerBrokenEvents2CommandHandler : CommandHandler<TriggerBrokenEventsSelfCommand, Unit> {
    override fun handle(command: TriggerBrokenEventsSelfCommand, user: UserDescriptor) = DomainResult { record ->
        record(BrokenEventC())
    }
}

private class BrokenEventA : DomainEvent() {
    override val details = "A"
}

private class BrokenEventB : DomainEvent() {
    override val details = "B"
}

private class BrokenEventATriggerB : EventSubscriber<BrokenEventA> {
    override fun handle(event: BrokenEventA, user: UserDescriptor) = DomainResult { record ->
        record(BrokenEventB())
    }
}

private class BrokenEventBTriggerA : EventSubscriber<BrokenEventB> {
    override fun handle(event: BrokenEventB, user: UserDescriptor) = DomainResult { record ->
        record(BrokenEventA())
    }
}

private class BrokenEventC : DomainEvent() {
    override val details = "C"
}

private class BrokenEventCTriggerC : EventSubscriber<BrokenEventC> {
    override fun handle(event: BrokenEventC, user: UserDescriptor) = DomainResult<Unit> { record ->
        record(BrokenEventC())
    }
}
