package de.kiwi.auditLog.domain

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.time.LocalDateTime

@Embeddable
class AuditLogEntryId(value: Long) : EntityId(value)

@Entity(name = "AuditLog")
@Table(name = "kiwi_audit_log")
class AuditLogEntry(
    id: AuditLogEntryId,

    @Column(name = "user_id", nullable = true)
    val userId: Long?,

    @Column(name = "user_name", nullable = false)
    val userName: String,

    @Column(name = "event_name", length = 255, nullable = false)
    val eventName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 32, nullable = false)
    val severity: Auditable.Severity,

    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "occurred_on", nullable = false)
    val occurredOn: LocalDateTime,

    @Column(name = "event_details", nullable = false, length = Int.MAX_VALUE)
    val eventDetails: ByteArray,
) : BaseEntity<AuditLogEntryId>(id), Persistable<AuditLogEntryId> {

    companion object {
        fun create(
            event: Auditable,
            user: UserDescriptor,
            serializedDetails: ByteArray,
        ) = AuditLogEntry(
            id = EntityId.generate<AuditLogEntryId>(),
            userId = user.userId,
            userName = user.username,
            eventName = event.eventName,
            severity = event.severity,
            occurredOn = event.occurredOn,
            eventDetails = serializedDetails,
        ).apply {
            // Mark created entity as "new" for persisting
            isNew = true
        }
    }

    /**
     * Optimization: This is set to `true` for new entities to prevent
     * a select-before-insert query from JPA.
     */
    @Transient
    private var isNew: Boolean = false

    override fun getId() = id
    override fun isNew() = isNew

    override fun toString() = buildString {
        append("AuditLogEntry")
        append("(id=$id")
        append(", user=$userId [$userName]")
        append(", event=$eventName [$severity] @ $occurredOn")
        append(", details=${eventDetails.toString(Charsets.UTF_8)}")
        append(")")
    }
}
