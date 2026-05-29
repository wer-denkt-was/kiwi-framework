package de.kiwi.api.application.service

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.slf4j
import java.time.format.DateTimeFormatter

private val log = slf4j<AuditLoggerService>()

/**
 * Service logging [auditable][Auditable] application events like [DomainEvent][de.kiwi.api.domain.DomainEvent]s
 * or [DomainException][de.kiwi.api.domain.DomainException]s.
 */
interface AuditLoggerService {

    /**
     * Audit log an [Auditable] event instance, caused by the given [UserDescriptor]
     */
    fun auditLog(event: Auditable, user: UserDescriptor)

    /**
     * No-op [AuditLoggerService] implementation, ignoring all [Auditable] events
     */
    object DoNothing : AuditLoggerService {
        override fun auditLog(event: Auditable, user: UserDescriptor) {}
    }

    /**
     * Implementation of [AuditLoggerService] printing [Auditable] events to the console log
     */
    class Console(private val serializeDetails: (Any) -> String = Any::toString) : AuditLoggerService {
        override fun auditLog(event: Auditable, user: UserDescriptor) {
            val timestamp = event.occurredOn.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            val details = event.details?.let { serializeDetails(it) }
            log.info("AuditLog [$timestamp ${user.username}] ${event.eventName}: $details")
        }
    }

    /**
     * Implementation of [AuditLoggerService] delegating [Auditable] events to multiple delegate instances
     */
    class Delegate(private val delegates: List<AuditLoggerService>) : AuditLoggerService {
        override fun auditLog(event: Auditable, user: UserDescriptor) {
            delegates.forEach { it.auditLog(event, user) }
        }
    }
}
