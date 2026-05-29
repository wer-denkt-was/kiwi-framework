package de.kiwi.api.application.event

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.UserDescriptor

/**
 * Bus aggregating [EventSubscriber]s to listen for [DomainEvent] publications.
 * This is a central interface in the core CQRS ("Command-Query Responsibility Segregation") system.
 */
interface EventBus {
    /**
     * Publish a collection of [DomainEvent]s to the event bus.
     * Returns a subsequent [DomainEvent] collection with new events resulting from the event being handled.
     */
    fun publish(events: Iterable<DomainEvent>, user: UserDescriptor): Collection<DomainEvent>

    /**
     * Mutable [EventBus] that can be configured on startup
     */
    interface Configurable : EventBus {
        fun subscribe(subscriber: EventSubscriber<*>)
    }
}
