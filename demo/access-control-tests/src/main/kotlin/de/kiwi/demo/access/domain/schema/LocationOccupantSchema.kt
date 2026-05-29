package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.*
import de.kiwi.demo.access.domain.LocationOccupant
import de.kiwi.demo.access.domain.accessControl.allowCustomerFieldAccess
import de.kiwi.demo.access.domain.accessControl.allowOperatorFieldAccess
import org.springframework.data.domain.Sort

object LocationOccupantSchema : EntitySchema<LocationOccupant>() {
    val id by entityId()
    val moveInDate by column(LocationOccupant::moveInDate, IsoLocalDateType)
    val moveOutDate by column(LocationOccupant::moveOutDate, IsoLocalDateType)
    val name by column<String?>(LocationOccupant::name)
    val email by column<String?>(LocationOccupant::email)
    val address by column<String?>(LocationOccupant::address)
    val phoneNumber by column<String?>(LocationOccupant::phoneNumber)

    val locationUnit by toOne(LocationOccupant::locationUnit, LocationUnitSchema)
    val locationUnitId by parentEntityId(LocationOccupant::locationUnit)

    val electricityContract by toOne(LocationOccupant::electricityContract, ElectricityContractSchema)
    val electricityContractId by parentEntityId(LocationOccupant::electricityContract, nullable = true)

    override val defaultSort = Sort.by(Sort.Direction.DESC, LocationOccupant::moveInDate.name)

    override fun fieldAccessControl(field: EntitySchemaField<LocationOccupant>) = when (field) {
        electricityContract -> allowCustomerFieldAccess or allowOperatorFieldAccess
        else -> null
    }
}

object LocationOccupantFieldsets : FieldsetsAvailable<LocationOccupant>(LocationOccupantSchema) {

    override val default by defineFieldset {
        includeAllColumns()
        exclude(LocationOccupantSchema.locationUnitId)
        exclude(LocationOccupantSchema.electricityContractId)
        includeToOne(LocationOccupantSchema.locationUnit, LocationUnitFieldsets.default)
        includeToOne(LocationOccupantSchema.electricityContract, ElectricityContractFieldsets.short.extend {
            exclude(ElectricityContractSchema.occupant)
        })
    }

    val short by defineFieldset {
        include(LocationOccupantSchema.id)
        include(LocationOccupantSchema.moveInDate)
        include(LocationOccupantSchema.moveOutDate)
        include(LocationOccupantSchema.name)
        includeToOne(LocationOccupantSchema.locationUnit, LocationUnitFieldsets.short)
    }

    val listing by defineFieldset {
        include(LocationOccupantSchema.id)
        include(LocationOccupantSchema.moveInDate)
        include(LocationOccupantSchema.moveOutDate)
        include(LocationOccupantSchema.name)
        include(LocationOccupantSchema.address)
        include(LocationOccupantSchema.phoneNumber)
        include(LocationOccupantSchema.email)
        includeToOne(LocationOccupantSchema.locationUnit, LocationUnitSchema.buildFieldset {
            include(LocationUnitSchema.id)
            include(LocationUnitSchema.unitNumber)
            includeToOne(LocationUnitSchema.monitor, ElectricityMonitorSchema.buildFieldset {
                include(ElectricityMonitorSchema.id)
                include(ElectricityMonitorSchema.monitorNumber)
            })
        })
        includeToOne(LocationOccupantSchema.electricityContract, ElectricityContractSchema.buildFieldset {
            include(ElectricityContractSchema.id)
            include(ElectricityContractSchema.contractStart)
            include(ElectricityContractSchema.contractEnd)
        })
    }
}
