package de.kiwi.demo.access

import de.kiwi.demo.access.application.ElectricityMonitorRepository
import de.kiwi.demo.access.application.SolarSystemRepository
import de.kiwi.demo.access.domain.*
import de.kiwi.demo.access.domain.accessControl.readElectricityMonitor
import de.kiwi.demo.access.domain.accessControl.readSolarSystem
import de.kiwi.demo.access.instrument.PerformanceInstrument
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired

@AccessControlTest
class SolarSystemAccessTest {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var userAccountHolder: TestUserAccountHolder

    @Autowired
    lateinit var solarSystemRepository: SolarSystemRepository

    @Autowired
    lateinit var electricityMonitorRepository: ElectricityMonitorRepository

    @Autowired
    lateinit var performanceInstrument: PerformanceInstrument

    // ---

    lateinit var account1: UserAccount
    lateinit var account2: UserAccount
    lateinit var account3: UserAccount
    lateinit var account4: UserAccount
    lateinit var account5: UserAccount

    private lateinit var system1: SolarSystem
    private lateinit var system1Operator: SolarOperator
    private lateinit var system1Unit1: LocationUnit
    private lateinit var system1Unit1Occupant1: LocationOccupant
    private lateinit var system1Unit1Occupant2: LocationOccupant
    private lateinit var system1Unit1Occupant2Contract: ElectricityContract
    private lateinit var system1Unit2: LocationUnit
    private lateinit var system1Unit2Occupant1: LocationOccupant
    private lateinit var system1Unit2Occupant1Contract: ElectricityContract
    private lateinit var system2: SolarSystem
    private lateinit var system2Operator: SolarOperator
    private lateinit var system2Unit1: LocationUnit
    private lateinit var system2Unit1Occupant1: LocationOccupant
    private lateinit var system1Monitor1: ElectricityMonitor
    private lateinit var system1Monitor2: ElectricityMonitor
    private lateinit var system1Monitor3: ElectricityMonitor
    private lateinit var system2Monitor1: ElectricityMonitor
    private lateinit var system2Monitor2: ElectricityMonitor

    @BeforeEach
    fun setUp() {
        account1 = entityManager.randomAccount()
        account2 = entityManager.randomAccount()
        account3 = entityManager.randomAccount()
        account4 = entityManager.randomAccount()
        account5 = entityManager.randomAccount()

        system1Operator = entityManager.randomSolarOperator()
        system1 = entityManager.randomSolarSystem(operator = system1Operator)
        system1Unit1 = entityManager.randomLocationUnit(solarSystem = system1)
        system1Unit1Occupant1 = entityManager.randomLocationOccupant(system1Unit1)
        system1Unit1Occupant2 = entityManager.randomLocationOccupant(system1Unit1, randomDetails = true)
        system1Unit1Occupant2Contract = entityManager.randomElectricityContract(system1Unit1Occupant2)
        system1Unit2 = entityManager.randomLocationUnit(solarSystem = system1)
        system1Unit2Occupant1 = entityManager.randomLocationOccupant(system1Unit2, randomDetails = true)
        system1Unit2Occupant1Contract = entityManager.randomElectricityContract(system1Unit2Occupant1)

        system2Operator = entityManager.randomSolarOperator()
        system2 = entityManager.randomSolarSystem(operator = system2Operator)
        system2Unit1 = entityManager.randomLocationUnit(system2)
        system2Unit1Occupant1 = entityManager.randomLocationOccupant(system2Unit1, randomDetails = true)

        system1Monitor1 = entityManager.randomFeedInMonitor(system1)
        system1Monitor2 = system1Unit1.monitor
        system1Monitor3 = system1Unit2.monitor
        system2Monitor1 = entityManager.randomFeedInMonitor(system2)
        system2Monitor2 = system2Unit1.monitor

        entityManager.flush()
    }

    @AfterEach
    fun tearDown() {
        entityManager.clear()
    }

    @Test
    fun testSolarSystemAccess(info: TestInfo) {
        account1.modifyAccessToSolarOperator(system1Operator, granted = true)
        account2.modifyAccessToSolarOperator(system2Operator, granted = true)
        account3.modifyAccessToElectricityContract(system1Unit2Occupant1Contract, granted = true)
        account4.modifyAccessToElectricityContract(system1Unit1Occupant2Contract, granted = true)

        val result1 = performanceInstrument.analyze(info) {
            userAccountHolder.withUserAccount(account1) { user ->
                solarSystemRepository.findAll(readSolarSystem.checkAuthorized(user))
            }
        }

        Assertions.assertAll(
            {
                assertThat(result1, containsInAnyOrder(system1))
            }
        )
    }

    @Test
    fun testElectricityMonitorAccess(info: TestInfo) {
        account1.modifyAccessToSolarOperator(system1Operator, granted = true)
        account2.modifyAccessToSolarOperator(system2Operator, granted = true)
        account3.modifyAccessToElectricityContract(system1Unit2Occupant1Contract, granted = true)
        account4.modifyAccessToElectricityContract(system1Unit1Occupant2Contract, granted = true)

        val result1 = performanceInstrument.analyze(info) {
            userAccountHolder.withUserAccount(account1) { user ->
                electricityMonitorRepository.findAll(readElectricityMonitor.checkAuthorized(user))
            }
        }

        Assertions.assertAll(
            {
                assertThat(result1, containsInAnyOrder(system1Monitor1, system1Monitor2, system1Monitor3))
            }
        )
    }
}
