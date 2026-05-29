package de.kiwi.auditLog

import de.kiwi.api.domain.Auditable
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("kiwi.audit-log")
data class KiwiAuditLogProperties(
    var enabled: Boolean = true,
    var serializer: SerializerType = SerializerType.Jackson,
    var includeSeverity: Set<Auditable.Severity> = setOf(
        Auditable.Severity.Error,
        Auditable.Severity.Event,
        Auditable.Severity.Unknown,
    ),
) {
    enum class SerializerType { Jackson }
}
