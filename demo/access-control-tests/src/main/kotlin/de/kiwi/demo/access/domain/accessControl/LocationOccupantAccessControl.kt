package de.kiwi.demo.access.domain.accessControl

import de.kiwi.api.schema.FieldAccessControl
import de.kiwi.demo.access.TestUserAccountHolder
import de.kiwi.demo.access.domain.*
import de.kiwi.demo.access.domain.schema.*

val locationOccupantOperatorAccess = accessControl<LocationOccupant> { occupant, account ->
    val occupantUnit = occupant.crossCorrelatedJoin(LocationOccupant_.locationUnit)

    val authorizedOperator = account.join(UserAccount_.accessToSolarOperators)
    val authorizedSolarSystem = authorizedOperator.join(SolarOperator_.operatedSystems)
    val authorizedLocationUnit = authorizedSolarSystem.join(SolarSystem_.units)
    and(equal(occupantUnit, authorizedLocationUnit))
}

val locationOccupantContractAccess = accessControl<LocationOccupant> { occupant, account ->
    val authorizedContract = account.join(UserAccount_.accessToElectricityContracts)
    val authorizedOccupant = authorizedContract.join(ElectricityContract_.occupant)
    equal(authorizedOccupant, occupant)
}

val readLocationOccupant = locationOccupantOperatorAccess or locationOccupantContractAccess

val LocationOccupantSchema.allowCustomerFieldAccess: FieldAccessControl<LocationOccupant>
    get() = TestUserAccountHolder.fieldAccess(
        require = {
            add(electricityContract) {
                add(ElectricityContractSchema.id)
            }
        },
        checkAccess = { context, account ->
            val contractId = context[electricityContract][ElectricityContractSchema.id]
            (contractId != null) && account.hasAccessToElectricityContract(contractId)
        }
    )

val LocationOccupantSchema.allowOperatorFieldAccess: FieldAccessControl<LocationOccupant>
    get() = TestUserAccountHolder.fieldAccess(
        require = {
            add(locationUnit) {
                add(LocationUnitSchema.solarSystem) {
                    add(SolarSystemSchema.operator) {
                        add(SolarOperatorSchema.id)
                    }
                }
            }
        },
        checkAccess = { context, account ->
            val solarSystem = context[locationUnit][LocationUnitSchema.solarSystem]
            val operatorId = solarSystem[SolarSystemSchema.operator][SolarOperatorSchema.id]
            (operatorId != null) && account.hasAccessToSolarOperator(operatorId)
        }
    )
