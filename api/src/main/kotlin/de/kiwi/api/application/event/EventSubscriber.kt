package de.kiwi.api.application.event

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.lookupSupertypeArgument

/**
 * Subscriber listening to events of a given subtype [E], usually [DomainEvent]s published onto an [EventBus].
 * Multiple subscribers can listen to the same event class.
 * Subscribers are executed in ascending order determined by the [order] attribute.
 * @param E Event type handled by the implementing subscriber instance
 */
interface EventSubscriber<E : Any> {
    /**
     * Order of this subscriber relative to other [EventSubscriber]s listening to the same event,
     * where lower values are handled before higher values.
     * @see org.springframework.core.Ordered
     */
    val order: Int get() = 0

    /**
     * Handle a [DomainEvent] this subscriber is subscribed to,
     * returning subsequent
     */
    fun handle(event: E, user: UserDescriptor): DomainResult<Unit>

    /**
     * Returns `true` if this EventSubscriber is subscribed to the given event instance,
     * by default if it is an instance of type [E] implemented by the subscriber class.
     */
    fun isSubscribed(event: DomainEvent): Boolean {
        val handledType = javaClass.lookupSupertypeArgument<EventSubscriber<*>>(0)
        return handledType.isInstance(event)
    }
}
