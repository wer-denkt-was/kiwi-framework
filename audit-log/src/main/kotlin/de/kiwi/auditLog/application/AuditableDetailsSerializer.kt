package de.kiwi.auditLog.application

fun interface AuditableDetailsSerializer {
    fun serialize(details: Any): ByteArray
}
