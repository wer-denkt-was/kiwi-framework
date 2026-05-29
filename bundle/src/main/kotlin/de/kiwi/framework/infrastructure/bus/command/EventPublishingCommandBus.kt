package de.kiwi.framework.infrastructure.bus.command

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.infrastructure.bus.event.RecursiveEventBus

class EventPublishingCommandBus(
    private val eventBus: EventBus,
    private val delegate: CommandBus.Configurable,
) : CommandBus.Configurable by delegate {

    override fun <C : Command<R>, R> submit(command: C, user: UserDescriptor): DomainResult<R> {
        try {
            val result = delegate.submit(command, user)
            return result + eventBus.publish(result, user)
        } catch (ex: RecursiveEventBus.EventRecursionDepthExceeded) {
            val max = ex.maxRecursionDepth
            val name = command.javaClass.name
            throw IllegalStateException("Exceeded recursion depth [$max] while handling Command: $name")
        }
    }
}
