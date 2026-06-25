package de.kiwi.demo.access

import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.api.util.flushAndClear
import de.kiwi.demo.access.application.UserAccountRepository
import de.kiwi.demo.access.domain.UserAccount
import de.kiwi.demo.access.domain.accessControl.readUserAccount
import de.kiwi.demo.access.domain.schema.UserAccountFieldsets
import de.kiwi.demo.access.instrument.PerformanceInstrument
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired

@AccessControlTest
class UserAccountAccessTest {

    @PersistenceContext
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var querySchemaService: QuerySchemaService

    @Autowired
    lateinit var userAccountHolder: TestUserAccountHolder

    @Autowired
    lateinit var userAccountRepository: UserAccountRepository

    @Autowired
    lateinit var performanceInstrument: PerformanceInstrument

    lateinit var account1: UserAccount
    lateinit var account2: UserAccount
    lateinit var account3: UserAccount
    lateinit var account4: UserAccount
    lateinit var account5: UserAccount

    @BeforeEach
    fun setUp() {
        account1 = entityManager.randomAccount()
        account2 = entityManager.randomAccount()
        account3 = entityManager.randomAccount()
        account4 = entityManager.randomAccount()
        account5 = entityManager.randomAccount()
        entityManager.flush()
    }

    @AfterEach
    fun tearDown() {
        entityManager.flushAndClear()
    }

    @Test
    fun testOnlySelfAccess(info: TestInfo) {
        val unrelatedAccount = entityManager.randomAccount()
        val result = userAccountHolder.withUserAccount(unrelatedAccount) { user ->
            userAccountRepository.findAll(
                readUserAccount.checkAuthorized(user)
            )
        }
        // Can only access own user-account
        assertThat(result, containsInAnyOrder(unrelatedAccount))
    }

    @Test
    fun testSameContractAccess(info: TestInfo) {
        val system = entityManager.randomSolarSystem()
        val unit = entityManager.randomLocationUnit(system)
        val occupant = entityManager.randomLocationOccupant(unit, randomDetails = true)
        val contract = entityManager.randomElectricityContract(occupant)
        account2.modifyAccessToElectricityContract(contract, granted = true)
        account3.modifyAccessToElectricityContract(contract, granted = true)
        entityManager.flush()

        val result = userAccountHolder.withUserAccount(account2) { user ->
            performanceInstrument.analyze(info) {
                userAccountRepository.findAll(
                    readUserAccount.checkAuthorized(user)
                )
            }
        }
        // Can access users in same contract
        assertThat(result, containsInAnyOrder(account2, account3))
    }

    @Test
    fun testOperatorAccess() {
        val operator = entityManager.randomSolarOperator()
        account1.modifyAccessToSolarOperator(operator, granted = true)

        val system = entityManager.randomSolarSystem(operator = operator)
        val unit1 = entityManager.randomLocationUnit(system)
        val occupant1 = entityManager.randomLocationOccupant(unit1, randomDetails = true)
        val contract1 = entityManager.randomElectricityContract(occupant1)
        account3.modifyAccessToElectricityContract(contract1, granted = true)

        val unit2 = entityManager.randomLocationUnit(system)
        val occupant2 = entityManager.randomLocationOccupant(unit2, randomDetails = true)
        val contract2 = entityManager.randomElectricityContract(occupant2)
        account4.modifyAccessToElectricityContract(contract2, granted = true)
        account5.modifyAccessToElectricityContract(contract2, granted = true)

        entityManager.flush()

        val result = userAccountHolder.withUserAccount(account1) { user ->
            userAccountRepository.findAll(
                readUserAccount.checkAuthorized(user)
            )
        }

        assertThat(result, containsInAnyOrder(account1, account3, account4, account5))
    }

    @Test
    fun testExternalIdentityField() {
        // Access only allowed to admin, so externalIdentity field should not be included
        val result = userAccountHolder.withUserAccount(account1) {
            querySchemaService
                .findOne(UserAccountFieldsets.details, GenericEntityQuery.baseEntityFilter(account1.id))
                ?.toPrettyString()
        }
        println(result)
        //TODO: Assert fields present/absent
    }

    @Test
    fun testAccessToContractsField(info: TestInfo) {
        val result = userAccountHolder.withUserAccount(account1) {
            querySchemaService
                .findOne(UserAccountFieldsets.details, GenericEntityQuery.baseEntityFilter(account2.id))
                ?.toPrettyString()
        }

        println(result)
        //TODO: Assert fields present/absent
    }
}
