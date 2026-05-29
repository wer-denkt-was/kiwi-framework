package de.kiwi.auditLog.domain

import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.generic.GenericEntityFilter
import java.time.LocalDateTime

abstract class AbstractAuditLogQuery : GenericEntityQuery<AuditLogEntry>() {
    override fun filterById(id: EntityId) = baseEntityFilter<AuditLogEntry>(id)
}

abstract class AbstractAuditLogFilter : GenericEntityFilter<AuditLogEntry>() {
    var after: LocalDateTime? by filter(greaterOrEqual(attribute(AuditLogEntry_.occurredOn)))
    var before: LocalDateTime? by filter(lessOrEqual(attribute(AuditLogEntry_.occurredOn)))
    var userNameLike: String? by filter(
        onlyWhen(
            { it.isNotEmpty() }, // Don't filter on empty string
            nothingUnless(
                { it.length >= 3 }, // Don't show anything unless 3 or more characters are given
                likeIgnoreCase(attribute(AuditLogEntry_.userName)) { "%$it%" }
            )
        )
    )
}

abstract class AbstractAuditLogReport : AnalyticalReportQuery<AuditLogEntry>() {
    override val root = AuditLogEntry_.class_!!

    override val fields by defineFields {
        dimension("occurredOn", date(attribute(AuditLogEntry_.occurredOn)))
        dimension("occurredYear", year(attribute(AuditLogEntry_.occurredOn)))
        dimension("occurredMonth", month(attribute(AuditLogEntry_.occurredOn)))
        dimension("occurredYearMonth", yearMonth(attribute(AuditLogEntry_.occurredOn)))
        dimension("userId", attribute(AuditLogEntry_.userId))
        dimension("userName", attribute(AuditLogEntry_.userName))
        dimension("eventName", attribute(AuditLogEntry_.eventName))
        dimension("severity", attribute(AuditLogEntry_.severity))

        metric("total", count())
        metric("errors", countWhere(Auditable.Severity.Error, attribute(AuditLogEntry_.severity)))
        metric("events", countWhere(Auditable.Severity.Event, attribute(AuditLogEntry_.severity)))
    }

    override fun preAuthorize(user: UserDescriptor) {}
    override fun postAuthorize(result: Any, user: UserDescriptor) {}
}
