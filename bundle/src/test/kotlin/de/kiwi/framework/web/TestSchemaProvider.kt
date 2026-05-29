package de.kiwi.framework.web

import de.kiwi.api.application.service.QuerySchemaProvider
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.framework.infrastructure.generic.*

class TestSchemaProvider : QuerySchemaProvider {
    private val schemata = mapOf<Class<*>, EntitySchema<*>>(
        Author::class.java to AuthorSchema,
        Publisher::class.java to PublisherSchema,
        Publication::class.java to PublicationSchema,
    )

    private val fieldsets = mapOf<Class<*>, FieldsetsAvailable<*>>(
        Author::class.java to AuthorFieldsets,
        Publisher::class.java to PublisherFieldsets,
        Publication::class.java to PublicationFieldsets,
    )

    private val filterTypes = mapOf<Class<*>, Class<out GenericEntityFilter<*>>>(
        Author::class.java to AuthorFilter::class.java,
        Publisher::class.java to PublisherFilter::class.java,
        Publication::class.java to PublicationFilter::class.java,
    )

    override val schemas: List<EntitySchema<*>>
        get() = schemata.values.toList()

    @Suppress("UNCHECKED_CAST")
    override fun <E : Any> findEntitySchema(entityType: Class<E>): EntitySchema<E>? =
        schemata[entityType] as EntitySchema<E>?

    @Suppress("UNCHECKED_CAST")
    override fun <E : Any> findFilterType(entityType: Class<E>): Class<out GenericEntityFilter<E>>? =
        filterTypes[entityType] as Class<out GenericEntityFilter<E>>?

    @Suppress("UNCHECKED_CAST")
    override fun <E : Any> findAvailableFieldsets(entityType: Class<E>): FieldsetsAvailable<E>? =
        fieldsets[entityType] as FieldsetsAvailable<E>?
}
