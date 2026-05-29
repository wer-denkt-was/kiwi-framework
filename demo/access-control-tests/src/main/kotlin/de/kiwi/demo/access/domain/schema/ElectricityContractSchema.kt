package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.api.schema.IsoLocalDateType
import de.kiwi.api.schema.buildFieldset
import de.kiwi.demo.access.domain.ElectricityContract
import de.kiwi.demo.access.domain.ElectricityContract_

object ElectricityContractSchema : EntitySchema<ElectricityContract>() {
    val id by entityId()

    val occupant by toOne(ElectricityContract::occupant, LocationOccupantSchema)
    val occupantId by parentEntityId(ElectricityContract::occupant)

    val contractStart by column(ElectricityContract::contractStart, IsoLocalDateType)
    val contractEnd by column(ElectricityContract::contractEnd, IsoLocalDateType)

    val contractSigned by column(ElectricityContract::contractSigned, IsoLocalDateType)
    val termsAccepted by column(ElectricityContract::termsAccepted, IsoLocalDateType)
}

object ElectricityContractFieldsets : FieldsetsAvailable<ElectricityContract>(ElectricityContractSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        exclude(ElectricityContractSchema.occupantId)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToOne(ElectricityContractSchema.occupant, LocationOccupantSchema.buildFieldset {
            includeAllColumns()
            exclude(LocationOccupantSchema.locationUnitId)
            exclude(LocationOccupantSchema.electricityContractId)
            includeToOne(LocationOccupantSchema.locationUnit, LocationUnitFieldsets.default)
        })
    }

    val short by defineFieldset {
        include(ElectricityContractSchema.id)
        include(ElectricityContractSchema.contractStart)
        include(ElectricityContractSchema.contractEnd)
        includeToOne(ElectricityContractSchema.occupant, LocationOccupantFieldsets.short)
    }

    val listing by defineFieldset {
        include(ElectricityContractSchema.id)
        include(ElectricityContractSchema.contractStart)
        include(ElectricityContractSchema.contractEnd)
        includeToOne(ElectricityContractSchema.occupant, LocationOccupantSchema.buildFieldset {
            include(LocationOccupantSchema.id)
            include(LocationOccupantSchema.name)
            include(LocationOccupantSchema.moveInDate)
            include(LocationOccupantSchema.moveOutDate)
            includeToOne(LocationOccupantSchema.locationUnit, LocationUnitSchema.buildFieldset {
                include(LocationUnitSchema.id)
                include(LocationUnitSchema.unitNumber)
                includeToOne(LocationUnitSchema.solarSystem, SolarSystemFieldsets.veryShort)
            })
        })
    }
}
