package de.kiwi.framework.infrastructure.bus.event

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.event.EventSubscriber
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.infrastructure.bus.HandlerCache

class BasicEventBus(
    private val enableCaching: Boolean,
) : EventBus.Configurable {
    private val subscribers = ArrayList<EventSubscriber<*>>()
    private val cache = HandlerCache(::findSubscribers)

    val cacheHitRate: Double get() = cache.hitRate()

    override fun subscribe(subscriber: EventSubscriber<*>) {
        synchronized(subscribers) {
            if (subscribers.none { it === subscriber }) {
                subscribers.add(subscriber)
            }
        }
    }

    override fun publish(events: Iterable<DomainEvent>, user: UserDescriptor): List<DomainEvent> {
        return buildList {
            events.forEach { event ->
                val subscribers = if (enableCaching) cache.compute(event) else findSubscribers(event)
                @Suppress("UNCHECKED_CAST") val typedSubscribers = subscribers as List<EventSubscriber<DomainEvent>>
                typedSubscribers.forEach { subscriber ->
                    val subsequentEvents = subscriber.handle(event, user)
                    addAll(subsequentEvents)
                }
            }
        }
    }

    private fun findSubscribers(event: DomainEvent): List<EventSubscriber<*>> =
        subscribers
            .filter { it.isSubscribed(event) }
            .sortedBy { it.order }
}
