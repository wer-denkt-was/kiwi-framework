package de.kiwi.api.olap

import de.kiwi.api.domain.DomainException

/**
 * Exception thrown when an [AnalyticalReportQuery] requests an unknown dimension- or metric-field.
 */
class UnknownReportField(val fieldName: String, val type: FieldType) : DomainException(
    status = Status.BadRequest,
    code = "unknown-report-field",
    args = mapOf("fieldName" to fieldName, "fieldType" to type.name)
) {
    enum class FieldType { Dimension, Metric }
}
