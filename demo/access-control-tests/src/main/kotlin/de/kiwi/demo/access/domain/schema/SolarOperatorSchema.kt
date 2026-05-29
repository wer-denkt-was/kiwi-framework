package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.demo.access.domain.SolarOperator
import de.kiwi.demo.access.domain.SolarOperator_

object SolarOperatorSchema : EntitySchema<SolarOperator>() {
    val id by entityId()
    val companyName by column<String>(SolarOperator::companyName)
    val address by column(SolarOperator::address)
    val operatedSystems by toMany(SolarOperator::operatedSystems, SolarSystemSchema)
}

object SolarOperatorFieldsets : FieldsetsAvailable<SolarOperator>(SolarOperatorSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        include(SolarOperatorSchema.id)
        include(SolarOperatorSchema.companyName)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToMany(SolarOperatorSchema.operatedSystems, SolarSystemFieldsets.short)
    }
}
