package de.kiwi.framework.infrastructure.bus.event

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.domain.UserDescriptor

class AuditingEventBus(
    private val auditLoggerService: AuditLoggerService,
    private val delegate: EventBus.Configurable,
) : EventBus.Configurable by delegate {

    override fun publish(events: Iterable<DomainEvent>, user: UserDescriptor): Collection<DomainEvent> {
        events.forEach { auditLoggerService.auditLog(it, user) }
        return delegate.publish(events, user)
    }
}
