package de.kiwi.demo.access

import de.kiwi.demo.access.domain.*
import jakarta.persistence.EntityManager
import java.time.LocalDate
import kotlin.random.Random
import kotlin.random.nextInt

private val numeric = ('0'..'9')
private val alphabetic = ('a'..'z') + ('A'..'Z')
private val alphanumeric = alphabetic + numeric

internal fun randomNumeric(length: Int): String =
    (1..length).map { numeric.random() }.joinToString(separator = "")

internal fun randomName(length: Int): String =
    (1..length).map { alphabetic.random() }.joinToString(separator = "")

internal fun randomString(length: Int): String =
    (1..length).map { alphanumeric.random() }.joinToString(separator = "")

internal fun randomText(words: Int, wordLength: IntRange = 1..10) =
    (1..words).joinToString(separator = " ") { randomString(wordLength.random()) }

fun EntityManager.randomAccount(username: String = randomName(16)) = merge(
    UserAccount.create(
        email = "$username@127.0.0.1",
        username = username,
        fullName = "$username ${randomName(8)}",
        externalIdentity = "ident::${username}_${randomString(10)}",
    )
)

fun EntityManager.randomSolarOperator(name: String = randomName(12), address: String = randomText(8)): SolarOperator =
    merge(SolarOperator.create(name, address))

fun EntityManager.randomSolarInstaller(name: String = randomName(12), address: String = randomText(8)): SolarInstaller =
    merge(SolarInstaller.create(name, address))

fun EntityManager.randomSolarSystem(
    name: String = randomName(12),
    model: String = randomString(8),
    electricityPrice: Long = Random.nextInt(10..120).toLong(),
    invoiceAddress: String = randomText(10),
    installer: SolarInstaller? = null,
    operator: SolarOperator? = null,
): SolarSystem = merge(
    SolarSystem.create(
        name = name,
        model = model,
        electricityPrice = electricityPrice,
        invoiceAddress = invoiceAddress,
        installer = installer,
        operator = operator,
    ),
).also { solarSystem ->
    operator?.operatedSystems?.add(solarSystem)
    installer?.installedSystems?.add(solarSystem)
}

fun EntityManager.randomMonitor(
    solarSystem: SolarSystem,
    monitorType: MonitorType,
    monitorNumber: String = randomNumeric(12),
): ElectricityMonitor = merge(
    ElectricityMonitor.create(monitorNumber = monitorNumber, solarSystem = solarSystem, monitorType = monitorType)
).also { monitor ->
    solarSystem.monitors.add(monitor)
}

fun EntityManager.randomFeedInMonitor(
    solarSystem: SolarSystem,
): ElectricityMonitor = randomMonitor(solarSystem, MonitorType.SolarSystem).also { monitor ->
    solarSystem.assignFeedInMonitor(monitor)
}

fun EntityManager.randomLocationUnit(
    solarSystem: SolarSystem,
    unitNumber: String = randomNumeric(12),
    electricityMonitor: ElectricityMonitor = randomMonitor(solarSystem, MonitorType.LocationUnit),
): LocationUnit = merge(
    LocationUnit.create(
        unitNumber = unitNumber,
        solarSystem = solarSystem,
        monitor = electricityMonitor,
    )
).also { unit ->
    solarSystem.units.add(unit)
}

fun EntityManager.randomLocationOccupant(
    locationUnit: LocationUnit,
    moveInDate: LocalDate = LocalDate.now(),
    randomDetails: Boolean = false,
    name: String? = null,
    address: String? = null,
    email: String? = null,
    phoneNumber: String? = null,
): LocationOccupant = merge(
    LocationOccupant.create(
        locationUnit = locationUnit,
        moveInDate = moveInDate,
        name = name,
        address = address,
        email = email,
        phoneNumber = phoneNumber,
    ).also { occupant ->
        if (randomDetails) {
            val random = randomName(10)
            occupant.name = random
            occupant.email = "${random}@127.0.0.1"
            occupant.phoneNumber = randomNumeric(10)
            occupant.address = randomText(5)
        }
    }
).also { occupant ->
    locationUnit.occupants.add(occupant)
}

fun EntityManager.randomElectricityContract(
    occupant: LocationOccupant,
    contractStart: LocalDate = LocalDate.now(),
    contractSigned: LocalDate = contractStart,
    termsAccepted: LocalDate = contractStart,
): ElectricityContract = merge(
    ElectricityContract.create(
        occupant = occupant,
        contractStart = contractStart,
        contractSigned = contractSigned,
        termsAccepted = termsAccepted,
    )
).also { contract ->
    occupant.electricityContract = contract
    occupant.locationUnit.solarSystem.electricityContracts.add(contract)
}
