package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.EntitySchemaField
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.demo.access.domain.LocationUnit
import de.kiwi.demo.access.domain.accessControl.solarOperatorFieldAccess

object LocationUnitSchema : EntitySchema<LocationUnit>() {
    val id by entityId()
    val unitNumber by column<String>(LocationUnit::unitNumber)

    val solarSystem by toOne(LocationUnit::solarSystem, SolarSystemSchema)
    val solarSystemId by parentEntityId(LocationUnit::solarSystem)

    val monitor by toOne(LocationUnit::monitor, ElectricityMonitorSchema)

    val occupants by toMany(LocationUnit::occupants, LocationOccupantSchema)

    override fun fieldAccessControl(field: EntitySchemaField<LocationUnit>) = when (field) {
        occupants -> solarOperatorFieldAccess
        else -> null
    }
}

object LocationUnitFieldsets : FieldsetsAvailable<LocationUnit>(LocationUnitSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        exclude(LocationUnitSchema.solarSystemId)
        includeToOne(LocationUnitSchema.monitor, ElectricityMonitorFieldsets.default)
        includeToOne(LocationUnitSchema.solarSystem, SolarSystemFieldsets.short)
    }

    val short by defineFieldset {
        include(LocationUnitSchema.id)
        include(LocationUnitSchema.unitNumber)
        includeToOne(LocationUnitSchema.solarSystem, SolarSystemFieldsets.short)
    }

    val veryShort by defineFieldset {
        include(LocationUnitSchema.id)
        include(LocationUnitSchema.unitNumber)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToOne(LocationUnitSchema.solarSystem, SolarSystemFieldsets.short)
        includeToOne(LocationUnitSchema.monitor, ElectricityMonitorFieldsets.default)
        includeToMany(LocationUnitSchema.occupants, LocationOccupantFieldsets.listing)
    }
}
