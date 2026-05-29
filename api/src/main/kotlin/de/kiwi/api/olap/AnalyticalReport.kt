package de.kiwi.api.olap

/**
 * Data class representing an analytical report table with header column and body rows.
 * Instances of this class are created by handling an [AnalyticalReportQuery].
 */
data class AnalyticalReport(
    val headers: List<String>,
    val body: List<List<*>>,
)

/**
 * Data class representing the available dimension- and metric-fields for an analytical report.
 */
data class AnalyticalReportSchema(
    val dimensions: List<AnalyticalReportField>,
    val metrics: List<AnalyticalReportField>,
) {
    data class AnalyticalReportField(
        val name: String,
        val valueType: String,
    )
}
