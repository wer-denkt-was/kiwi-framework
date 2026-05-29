package de.kiwi.api.domain

import de.kiwi.api.domain.Auditable.Severity.Disabled
import java.io.Serializable
import java.time.LocalDateTime

/**
 * Interface for all objects that can be audited in an audit log. Classes implementing this interface
 * can be passed to an [AuditLoggerService][de.kiwi.api.application.service.AuditLoggerService].
 */
interface Auditable : Serializable {
    val eventName: String
    val occurredOn: LocalDateTime
    val severity: Severity
    val details: Any?

    /**
     * Represents the severity of an auditable event.
     * [Disabled] events are omitted from the audit log.
     */
    enum class Severity { Event, Error, Unknown, Disabled }
}

/**
 * Interface for objects that can provide [Auditable.details] for audit logging
 * based on a given input value.
 */
fun interface DetailsProvider<T : Any> {
    fun toDetails(value: T): Any?
}
