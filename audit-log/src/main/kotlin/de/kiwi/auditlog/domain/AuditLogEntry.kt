package de.kiwi.auditlog.domain

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.PersistableBaseEntity
import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
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

    @Column(name = "occurred_on", nullable = false)
    val occurredOn: LocalDateTime,

    @Basic
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_details", nullable = false)
    val detailsJson: String,
) : PersistableBaseEntity<AuditLogEntryId>(id) {

    companion object {
        fun create(
            event: Auditable,
            user: UserDescriptor,
            detailsJson: String,
        ) = AuditLogEntry(
            id = EntityId.generate<AuditLogEntryId>(),
            userId = user.userId,
            userName = user.username,
            eventName = event.eventName,
            severity = event.severity,
            occurredOn = event.occurredOn,
            detailsJson = detailsJson,
        ).apply {
            markAsNew()
        }
    }

    override fun getId() = id

    override fun toString() =
        "AuditLogEntry(id=$id, user=$userId [$userName], event=$eventName [$severity] @ $occurredOn, details=$detailsJson)"
}
