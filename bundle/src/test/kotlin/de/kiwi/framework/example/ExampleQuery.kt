package de.kiwi.framework.example

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.api.olap.isNotNull
import de.kiwi.api.olap.isNull
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import jakarta.persistence.metamodel.EntityType

object ExampleSchema : EntitySchema<ExampleEntity>() {
    val id by entityId()
    val name by column(ExampleEntity::name)
    val optional by column(ExampleEntity::optional)
}

object ExampleFieldsets : FieldsetsAvailable<ExampleEntity>(ExampleSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        extendsFieldset(default)
        exclude(ExampleSchema.optional)
    }

    val idOnly by singleColumnFieldset(ExampleSchema.id)

    val nameOnly by singleColumnFieldset(ExampleSchema.name)
}


class ExampleQuery : GenericEntityQuery<ExampleEntity>() {
    override fun filterById(id: EntityId) = baseEntityFilter<ExampleEntity>(id)

    override fun customFilter(user: UserDescriptor) = null

    override fun preAuthorize(user: UserDescriptor) {} //TODO
}

class ExampleFilter : GenericEntityFilter<ExampleEntity>() {
    var nameLike by filter(likeIgnoreCase(attribute(ExampleEntity_.name)))
    var hasOptional by filter(ifTrue { root -> isNotNull(root[ExampleEntity_.optional]) })
}

class ExampleReport : AnalyticalReportQuery<ExampleEntity>() {
    override val root: EntityType<ExampleEntity> = ExampleEntity_.class_

    override val fields by defineFields {
        dimension("id", attribute(ExampleEntity_.id))
        dimension("name", attribute(ExampleEntity_.name))
        dimension("hasOptional", select { ex -> isNotNull(ex[ExampleEntity_.optional]) })
        metric("total", count())
        metric("withOptional", countWhere(attribute(ExampleEntity_.optional).isNotNull()))
        metric("withoutOptional", countWhere(attribute(ExampleEntity_.optional).isNull()))
    }

    override fun preAuthorize(user: UserDescriptor) {} //TODO
    override fun postAuthorize(result: Any, user: UserDescriptor) {} //TODO
}
