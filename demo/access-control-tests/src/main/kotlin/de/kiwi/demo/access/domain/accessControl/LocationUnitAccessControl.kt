package de.kiwi.demo.access.domain.accessControl

import de.kiwi.demo.access.TestUserAccountHolder
import de.kiwi.demo.access.domain.LocationUnit
import de.kiwi.demo.access.domain.schema.LocationUnitSchema
import de.kiwi.demo.access.domain.schema.SolarInstallerSchema
import de.kiwi.demo.access.domain.schema.SolarOperatorSchema
import de.kiwi.demo.access.domain.schema.SolarSystemSchema

val LocationUnitSchema.solarOperatorFieldAccess
    get() = TestUserAccountHolder.fieldAccess<LocationUnit>(
        require = {
            add(solarSystem) {
                add(SolarSystemSchema.operator) { add(SolarOperatorSchema.id) }
            }
        },
        checkAccess = { context, account ->
            // Allow access if user is solar-operator of system of unit
            val operatorId = context[solarSystem][SolarSystemSchema.operator][SolarOperatorSchema.id]
            return@fieldAccess operatorId != null && account.hasAccessToSolarOperator(operatorId)
        },
    )

val LocationUnitSchema.solarInstallerFieldAccess
    get() = TestUserAccountHolder.fieldAccess<LocationUnit>(
        require = {
            add(solarSystem) {
                add(SolarSystemSchema.installer) { add(SolarInstallerSchema.id) }
            }
        },
        checkAccess = { context, account ->
            val installerId = context[solarSystem][SolarSystemSchema.installer][SolarInstallerSchema.id]
            return@fieldAccess installerId != null && account.hasAccessToSolarInstaller(installerId)
        }
    )
