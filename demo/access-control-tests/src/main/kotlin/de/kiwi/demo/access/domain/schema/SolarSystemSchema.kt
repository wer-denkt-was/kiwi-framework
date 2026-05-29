package de.kiwi.demo.access.domain.schema

import de.kiwi.api.schema.*
import de.kiwi.api.schema.*
import de.kiwi.demo.access.TestUserAccountHolder
import de.kiwi.demo.access.domain.ImsStatus
import de.kiwi.demo.access.domain.InstallStatus
import de.kiwi.demo.access.domain.SolarSystem
import de.kiwi.demo.access.domain.SolarSystem_

private val installStatusType = ColumnType.enum<InstallStatus>()
private val imsStatusType = ColumnType.enum<ImsStatus>()

object SolarSystemSchema : EntitySchema<SolarSystem>() {
    val id by entityId()
    val name by column<String, String>(SolarSystem::name, type = LikeStringColumnType)
    val model by column<String, String>(SolarSystem::model, type = LikeStringColumnType)
    val invoiceAddress by column<String>(SolarSystem::invoiceAddress)
    val electricityPrice by column<Long>(SolarSystem::electricityPrice) //TODO() custom currency thing

    val installStatus by column<InstallStatus, _>(SolarSystem::installStatus, type = installStatusType)
    val imsStatus by column<ImsStatus, _>(SolarSystem::imsStatus, type = imsStatusType)

    val imsRequestedOn by column(SolarSystem::imsRequestedOn, type = IsoLocalDateType)
    val imsInstallDate by column(SolarSystem::imsInstallDate, type = IsoLocalDateType)

    val feedInMonitor by toOne(SolarSystem::feedInMonitor, ElectricityMonitorSchema)

    val installer by toOne(SolarSystem::installer, SolarInstallerSchema)
    val installerId by parentEntityId(SolarSystem::installer, nullable = true)

    val operator by toOne(SolarSystem::operator, SolarOperatorSchema)
    val operatorId by parentEntityId(SolarSystem::operator, nullable = true)

    val units by toMany(SolarSystem::units, LocationUnitSchema)
    val monitors by toMany(SolarSystem::monitors, ElectricityMonitorSchema)
    val contracts by toMany(SolarSystem::electricityContracts, ElectricityContractSchema)

    override fun fieldAccessControl(field: EntitySchemaField<SolarSystem>) = when (field) {
        installer, units, monitors -> allowedForInstaller or allowedForOperator
        operator -> allowedForInstaller or allowedForOperator or allowedForElectricityCustomer
        contracts -> allowedForOperator
        else -> null
    }

    private val allowedForOperator = TestUserAccountHolder.fieldAccess<SolarSystem>(
        require = { add(operator) { add(SolarOperatorSchema.id) } },
        checkAccess = { context, account ->
            val operatorId = context[operator][SolarOperatorSchema.id]
            operatorId != null && account.hasAccessToSolarOperator(operatorId)
        }
    )

    private val allowedForInstaller = TestUserAccountHolder.fieldAccess<SolarSystem>(
        require = { add(installer) { add(SolarInstallerSchema.id) } },
        checkAccess = { context, account ->
            val installerId = context[installer][SolarInstallerSchema.id]
            installerId != null && account.hasAccessToSolarInstaller(installerId)
        }
    )

    private val allowedForElectricityCustomer = TestUserAccountHolder.fieldAccess<SolarSystem>(
        require = { add(id) },
        checkAccess = { context, account ->
            val solarSystemId = context[id]!!
            account.isElectricityCustomerInSystem(solarSystemId)
        }
    )
}

object SolarSystemFieldsets : FieldsetsAvailable<SolarSystem>(SolarSystemSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        exclude(SolarSystemSchema.operatorId)
        exclude(SolarSystemSchema.installerId)
        includeToOne(SolarSystemSchema.feedInMonitor, ElectricityMonitorFieldsets.default)
    }

    val short by defineFieldset {
        include(SolarSystemSchema.id)
        include(SolarSystemSchema.name)
        include(SolarSystemSchema.model)
        include(SolarSystemSchema.invoiceAddress)
        include(SolarSystemSchema.installStatus)
        include(SolarSystemSchema.imsStatus)
        include(SolarSystemSchema.electricityPrice)
    }


    val veryShort by defineFieldset {
        include(SolarSystemSchema.id)
        include(SolarSystemSchema.name)
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToOne(SolarSystemSchema.installer, SolarInstallerFieldsets.short)
        includeToOne(SolarSystemSchema.operator, SolarOperatorFieldsets.short)
        includeToMany(SolarSystemSchema.units, LocationUnitFieldsets.default)
    }

    val status by defineFieldset {
        include(SolarSystemSchema.id)
        include(SolarSystemSchema.installStatus)
        include(SolarSystemSchema.imsStatus)
        include(SolarSystemSchema.imsRequestedOn)
        include(SolarSystemSchema.imsInstallDate)
    }
}
