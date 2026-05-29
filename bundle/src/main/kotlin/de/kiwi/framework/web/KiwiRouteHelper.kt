package de.kiwi.framework.web

import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.framework.KiwiFrameworkProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component

@Component
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
class KiwiRouteHelper(
    private val props: KiwiFrameworkProperties,
    private val entityTypeProvider: EntityTypeProvider,
) {
    companion object {
        const val ID_VARIABLE = "id"

        const val FIELDS_PARAM = "_fields"
        const val FIELDSET_PARAM = "_fieldset"
        const val ACCELERATE_PARAM = "_accelerate"
        const val FIELDSET_DEFAULT_VALUE = "default"

        const val SCHEMA_KEYWORD = "_schema"
        const val REPORT_KEYWORD = "_report"
    }

    fun basePath(base: String) = "/$base"
    fun byIdPath(base: String) = "/$base/{$ID_VARIABLE}"
    fun schemaPath(base: String) = "/$base/$SCHEMA_KEYWORD"
    fun reportPath(base: String) = "/$base/$REPORT_KEYWORD"
    fun reportSchemaPath(base: String) = "/$base/$REPORT_KEYWORD/$SCHEMA_KEYWORD"

    fun forEachEntityPath(
        entityClasses: Iterable<Class<*>>,
        onlyCanonical: Boolean = false,
        block: (Class<*>, String) -> Unit,
    ) {
        entityClasses.forEach { entityClass ->
            entityPathNames(entityClass, onlyCanonical).forEach { pathName ->
                block(entityClass, pathName)
            }
        }
    }

    fun <V> forEachEntityPath(
        entityClassMap: Map<Class<*>, V>,
        onlyCanonical: Boolean = false,
        block: (Class<*>, String, V) -> Unit,
    ) {
        entityClassMap.forEach { (entityClass, associatedValue) ->
            entityPathNames(entityClass, onlyCanonical).forEach { pathName ->
                block(entityClass, pathName, associatedValue)
            }
        }
    }

    private fun entityPathNames(entityClass: Class<*>, onlyCanonical: Boolean): Set<String> {
        val entityType = entityTypeProvider.findEntityType(entityClass) ?: return emptySet()
        return if (onlyCanonical) setOf(entityType.name)
        else props.web.entityTypeNameVariants(entityType.name)
    }
}
