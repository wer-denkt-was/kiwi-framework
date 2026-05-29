package de.kiwi.framework.infrastructure.bus.event

import de.kiwi.api.application.event.EventBus
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.UserDescriptor

class RecursiveEventBus(
    private val maxRecursionDepth: Int = 100,
    private val delegate: EventBus.Configurable,
) : EventBus.Configurable by delegate {

    override fun publish(events: Iterable<DomainEvent>, user: UserDescriptor): Collection<DomainEvent> {
        return publishRecursively(events, user, recursionDepth = 0)
    }

    // Publishing may happen recursively if EventSubscribers themselves return follow-up events
    private fun publishRecursively(
        events: Iterable<DomainEvent>,
        user: UserDescriptor,
        recursionDepth: Int,
    ): Collection<DomainEvent> {
        // Throw when recursion depth is exceeded
        if (recursionDepth > maxRecursionDepth) {
            throw EventRecursionDepthExceeded(maxRecursionDepth)
        }

        // Publish to delegate, which may raise follow-up events from event-subscribers
        val outputEvents = delegate.publish(events, user)

        // Stop when no more events remain to be published, otherwise enter recursion
        return if (outputEvents.isEmpty()) {
            outputEvents
        } else {
            outputEvents + publishRecursively(outputEvents, user, recursionDepth = recursionDepth + 1)
        }
    }

    class EventRecursionDepthExceeded(val maxRecursionDepth: Int) : Exception()
}
