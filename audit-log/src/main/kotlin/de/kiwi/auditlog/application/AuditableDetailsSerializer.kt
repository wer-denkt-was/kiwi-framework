package de.kiwi.auditlog.application

fun interface AuditableDetailsSerializer {
    fun serializeJson(details: Any): String
}
