package de.kiwi.framework.application.olap

import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.olap.AnalyticalReportSchema

/**
 * Internal service interface for providing [AnalyticalReportQueries][AnalyticalReportQuery],
 * as well as corresponding [AnalyticalReportSchema].
 */
interface AnalyticalReportProvider {
    val reportEntityClasses: List<Class<*>>

    fun <E : Any> reportQuery(entityType: Class<E>): AnalyticalReportQuery<E>

    fun <E : Any> reportSchema(entityType: Class<E>): AnalyticalReportSchema
}
