package de.kiwi.auditlog.domain

import de.kiwi.api.schema.ColumnType
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.schema.IsoLocalDateTimeType
import org.springframework.data.domain.Sort
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.POJONode
import tools.jackson.databind.util.RawValue

private val RawJsonColumnType = ColumnType<String, JsonNode> { POJONode(RawValue(it)) }

object AuditLogSchema : EntitySchema<AuditLogEntry>() {
    val id by entityId()
    val userId by column(AuditLogEntry::userId)
    val userName by column(AuditLogEntry::userName)
    val eventName by column(AuditLogEntry::eventName)
    val severity by column(AuditLogEntry::severity)
    val occurredOn by column(AuditLogEntry::occurredOn, IsoLocalDateTimeType)
    val eventDetails by column(AuditLogEntry::detailsJson, RawJsonColumnType)

    override val defaultSort: Sort = Sort.by(Sort.Direction.DESC, ::occurredOn.name)
}

object AuditLogFieldsets : FieldsetsAvailable<AuditLogEntry>(AuditLogSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        extendsFieldset(default)
        exclude(AuditLogSchema.eventDetails)
    }
}
