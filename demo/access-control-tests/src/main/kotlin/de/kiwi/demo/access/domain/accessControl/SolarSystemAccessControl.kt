package de.kiwi.demo.access.domain.accessControl

import de.kiwi.api.domain.childAccess
import de.kiwi.demo.access.domain.*

// SolarSystem = SolarSystem >- SolarOperator >- UserAccount
val operatorSolarSystemAccess = accessControl<SolarSystem> { solarSystem, account ->
    val accountOperator = account.join(UserAccount_.accessToSolarOperators)
    val operatorSolarSystem = accountOperator.join(SolarOperator_.operatedSystems)
    equal(operatorSolarSystem, solarSystem)
}

// SolarSystem = SolarSystem >- SolarInstaller >- UserAccount
val installerSolarSystemAccess = accessControl<SolarSystem> { solarSystem, account ->
    val accountInstaller = account.join(UserAccount_.accessToSolarInstallers)
    val installerSolarSystem = accountInstaller.join(SolarInstaller_.installedSystems)
    equal(installerSolarSystem, solarSystem)
}

// SolarSystem = SolarSystem <- ElectricityContract >- UserAccount
val contractSolarSystemAccess = accessControl<SolarSystem> { solarSystem, account ->
    val accountContract = account.join(UserAccount_.accessToElectricityContracts)
    val contractSolarSystem = accountContract.join(ElectricityContract_.solarSystem)
    equal(contractSolarSystem, solarSystem)
}

// Access to SolarSystem by Operator, Installer, or Contract
val readSolarSystem = operatorSolarSystemAccess or installerSolarSystemAccess or contractSolarSystemAccess

// Access to ElectricityMonitor is derived from access to parent SolarSystem
val readElectricityMonitor = readSolarSystem.childAccess { monitor -> monitor[ElectricityMonitor_.solarSystem] }
