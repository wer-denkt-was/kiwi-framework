package de.kiwi.framework.infrastructure.olap

import de.kiwi.api.domain.EntityMethodUnavailable
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.olap.AnalyticalReportSchema
import de.kiwi.api.util.lookupSupertypeArgument
import de.kiwi.api.util.slf4j
import de.kiwi.framework.application.olap.AnalyticalReportProvider
import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.InitializingBean

private val log = slf4j<SpringAnalyticalReportProvider>()

class SpringAnalyticalReportProvider(
    private val classPathScanner: ClassPathScannerUtil,
) : AnalyticalReportProvider, InitializingBean {

    private lateinit var reportTypes: Map<Class<*>, Class<out AnalyticalReportQuery<*>>>

    override val reportEntityClasses: List<Class<*>> get() = reportTypes.keys.toList()

    override fun afterPropertiesSet() {
        // Search for query classes in @EntityScan and @ComponentScan package locations
        val basePackages = classPathScanner.findEntityAndComponentBasePackages()
        val queryClasses = classPathScanner.findClassesOfTypeInPackages<AnalyticalReportQuery<*>>(basePackages)

        reportTypes = queryClasses.associateBy { it.lookupSupertypeArgument<AnalyticalReportQuery<*>>(0) }

        // Log available report queries
        reportTypes.forEach { (entityType, reportType) ->
            log.info("Entity [${entityType.name}]: Analytical Report Query = ${reportType.name}")
        }
    }

    override fun <E : Any> reportQuery(entityType: Class<E>): AnalyticalReportQuery<E> {
        val reportType = reportTypes[entityType]
            ?: throw EntityMethodUnavailable(entityType, EntityMethodUnavailable.EntityMethod.Report)

        @Suppress("UNCHECKED_CAST")
        return BeanUtils.instantiateClass(reportType) as AnalyticalReportQuery<E>
    }

    override fun <E : Any> reportSchema(entityType: Class<E>): AnalyticalReportSchema {
        val query = reportQuery(entityType)
        val dimensions = query.fields.dimensions
        val metrics = query.fields.metrics

        return AnalyticalReportSchema(
            dimensions = dimensions.entries.map { (name, selector) ->
                AnalyticalReportSchema.AnalyticalReportField(
                    name = name,
                    valueType = selector.valueType.name,
                )
            },
            metrics = metrics.entries.map { (name, selector) ->
                AnalyticalReportSchema.AnalyticalReportField(
                    name = name,
                    valueType = selector.valueType.name
                )
            }
        )
    }
}
