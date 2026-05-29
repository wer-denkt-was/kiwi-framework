package de.kiwi.auditLog.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.schema.ColumnType
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.schema.IsoLocalDateTimeType
import org.springframework.data.domain.Sort

private val json = jacksonObjectMapper()

object AuditLogSchema : EntitySchema<AuditLogEntry>() {
    val id by entityId()
    val userId by column(AuditLogEntry::userId)
    val userName by column(AuditLogEntry::userName)
    val eventName by column(AuditLogEntry::eventName)
    val severity by column(AuditLogEntry::severity)
    val occurredOn by column(AuditLogEntry::occurredOn, IsoLocalDateTimeType)
    val eventDetails by column(AuditLogEntry::eventDetails, ColumnType { json.readTree(it) })

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
