package de.kiwi.auditlog

import de.kiwi.api.domain.Auditable
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties (prefix `kiwi.audit-log`).
 * Properties are `var` to be included in the configuration metadata,
 * see `KiwiFrameworkProperties` of the kiwi-framework module.
 */
@ConfigurationProperties("kiwi.audit-log")
data class KiwiAuditLogProperties(
    // The "kiwi.audit-log.enabled" and "kiwi.audit-log.console" switches are declared by the Kiwi framework module
    var serializer: SerializerType = SerializerType.Jackson,
    var includeSeverity: Set<Auditable.Severity> = setOf(
        Auditable.Severity.Error,
        Auditable.Severity.Event,
        Auditable.Severity.Unknown,
    ),
) {
    enum class SerializerType { Jackson }
}
