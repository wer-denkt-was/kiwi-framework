package de.kiwi.demo.access

import de.kiwi.api.domain.filterByIdAuthorized
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.api.schema.buildFieldset
import de.kiwi.api.schema.columnFieldset
import de.kiwi.api.util.flushAndClear
import de.kiwi.auditlog.application.AuditableDetailsSerializer
import de.kiwi.demo.access.application.LocationOccupantRepository
import de.kiwi.demo.access.domain.*
import de.kiwi.demo.access.domain.accessControl.readLocationOccupant
import de.kiwi.demo.access.domain.schema.LocationOccupantSchema
import de.kiwi.demo.access.domain.schema.LocationUnitSchema
import de.kiwi.demo.access.instrument.PerformanceInstrument
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.empty
import org.junit.jupiter.api.*
import org.skyscreamer.jsonassert.JSONAssert
import org.springframework.beans.factory.annotation.Autowired

@AccessControlTest
class LocationOccupantAccessTest {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var querySchemaService: QuerySchemaService

    @Autowired
    private lateinit var userAccountHolder: TestUserAccountHolder

    @Autowired
    lateinit var locationOccupantRepository: LocationOccupantRepository

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

        entityManager.flush()
    }

    @AfterEach
    fun tearDown() {
        entityManager.flushAndClear()
    }

    @Test
    fun testLocationOccupantAccess(info: TestInfo) {
        account1.modifyAccessToSolarOperator(system1Operator, granted = true)
        account2.modifyAccessToSolarOperator(system2Operator, granted = true)
        account3.modifyAccessToElectricityContract(system1Unit2Occupant1Contract, granted = true)
        account4.modifyAccessToElectricityContract(system1Unit1Occupant2Contract, granted = true)
        entityManager.flush()

        val result1 = performanceInstrument.analyze(info) {
            userAccountHolder.withUserAccount(account1) { user ->
                locationOccupantRepository.findAll(readLocationOccupant.checkAuthorized(user))
            }
        }
        val result2 = userAccountHolder.withUserAccount(account2) { user ->
            locationOccupantRepository.findAll(readLocationOccupant.checkAuthorized(user))
        }
        val result3 = userAccountHolder.withUserAccount(account3) { user ->
            locationOccupantRepository.findAll(readLocationOccupant.checkAuthorized(user))
        }
        val result4 = userAccountHolder.withUserAccount(account4) { user ->
            locationOccupantRepository.findAll(readLocationOccupant.checkAuthorized(user))
        }
        val result5 = userAccountHolder.withUserAccount(account5) { user ->
            locationOccupantRepository.findAll(readLocationOccupant.checkAuthorized(user))
        }

        Assertions.assertAll(
            {
                // Operator of system1 -> Access to all occupants of units of system1
                assertThat(
                    result1,
                    containsInAnyOrder(system1Unit1Occupant1, system1Unit1Occupant2, system1Unit2Occupant1)
                )
            },
            {
                // Operator of system2 -> Access to all occupants of units of system2
                assertThat(result2, containsInAnyOrder(system2Unit1Occupant1))
            },
            {
                // Contract with system1.unit2.occupant1 -> Access to occupant of contract
                assertThat(result3, containsInAnyOrder(system1Unit2Occupant1))
            },
            {
                // Contract with system1.unit1.occupant2 -> Access to occupant of contract
                assertThat(result4, containsInAnyOrder(system1Unit1Occupant2))
            },
            {
                // Unrelated -> No access
                assertThat(result5, empty())
            }
        )
    }

    @Test
    fun testLocationOccupantFieldAccessWithAttemptedExploit(info: TestInfo) {
        // Grant operator access to account1, and contract access to account2
        account1.modifyAccessToSolarOperator(system1Operator, granted = true)
        account2.modifyAccessToElectricityContract(system1Unit1Occupant2Contract, granted = true)
        entityManager.flush()

        // This fieldset will attempt to exploit a security flaw:
        // By navigating (LocationOccupant) --[locationUnit]-> (LocationUnit) --[occupants]-> (LocationOccupant),
        // the query would attempt to access occupants the account may not have access to.
        // The [occupants] relation field is protected using FieldAccessControl (see LocationUnitSchema),
        // so this exploit must not work!
        // Only a solar-operator should be able to query this,not an electricity customer.
        val relationExploitFieldset = LocationOccupantSchema.buildFieldset {
            include(LocationOccupantSchema.id)
            includeToOne(LocationOccupantSchema.locationUnit, LocationUnitSchema.buildFieldset {
                include(LocationUnitSchema.id)
                includeToMany(LocationUnitSchema.occupants, LocationOccupantSchema.id.columnFieldset())
            })
        }

        val result1 = userAccountHolder.withUserAccount(account1) { user ->
            performanceInstrument.analyze(info) {
                querySchemaService.findOne(
                    fieldset = relationExploitFieldset,
                    specification = readLocationOccupant.filterByIdAuthorized(system1Unit1Occupant2.id, user),
                )
            }
        }
        val result2 = userAccountHolder.withUserAccount(account2) { user ->
            querySchemaService.findOne(
                fieldset = relationExploitFieldset,
                specification = readLocationOccupant.filterByIdAuthorized(system1Unit1Occupant2.id, user)
            )
        }

        println("Operator account >> ${result1?.toPrettyString()}")
        println("Contract account >> ${result2?.toPrettyString()}")

        Assertions.assertAll(
            {
                JSONAssert.assertEquals(
                    // Result1: Operator has access to all occupants of unit
                    """
                    {
                      "id": "${system1Unit1Occupant2.id}",
                      "locationUnit": {
                        "id": "${system1Unit1.id}",
                        "occupants": ["${system1Unit1Occupant1.id}", "${system1Unit1Occupant2.id}"]
                      }
                    }
                    """.trimIndent(),
                    result1.toString(),
                    false,
                )
            },
            {
                JSONAssert.assertEquals(
                    // Result2: Not an operator, thus no access to other occupants of unit
                    """
                    {
                      "id": "${system1Unit1Occupant2.id}",
                      "locationUnit": {
                        "id": "${system1Unit1.id}",
                        "occupants": [ ]
                      }
                    }
                    """.trimIndent(),
                    result2.toString(),
                    false,
                )
            }
        )
    }
}
