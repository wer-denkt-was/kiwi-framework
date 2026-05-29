package de.kiwi.framework.application.authz

import de.kiwi.api.domain.AccessControl
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.hamcrest.Matchers.*
import org.hamcrest.MatcherAssert.assertThat

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest

/**
 * This test needs to run within an active JPA Hibernate context
 * in order to execute JPA Criteria queries
 */
@DataJpaTest(
    properties = [
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.datasource.url=jdbc:h2:mem://access-control-test",
    ]
)
class CriteriaAccessControlTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("de.kiwi.framework.application.authz")
    class TestConfiguration

    @PersistenceContext
    lateinit var entityManager: EntityManager

    lateinit var testUser: TestUser
    lateinit var testDescriptor: TestUserDescriptor

    lateinit var testParent1: TestParent
    lateinit var testParent2: TestParent

    lateinit var testChild1a: TestChild
    lateinit var testChild1b: TestChild
    lateinit var testChild2a: TestChild

    @BeforeEach
    fun setUp() {
        testUser = entityManager.merge(TestUser())
        testDescriptor = TestUserDescriptor(testUser.id!!)

        testParent1 = entityManager.merge(TestParent())
        testParent2 = entityManager.merge(TestParent())
        testChild1a = entityManager.merge(TestChild(parent = testParent1))
        testChild1b = entityManager.merge(TestChild(parent = testParent1))
        testChild2a = entityManager.merge(TestChild(parent = testParent2))

        entityManager.flush()
    }

    @AfterEach
    fun tearDown() {
        deleteAll<TestAccess>()
        deleteAll<TestChild>()
        deleteAll<TestParent>()
        deleteAll<TestUser>()
    }

    @Test
    fun `no access granted`() {
        // By default, all access should be denied
        assertThat(findAllParents(), empty())
        assertThat(findAllChildren(), empty())
    }

    @Test
    fun `specific parent access granted`() {
        // Grant access to one parent entity
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = false)
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1))
        assertThat(findAllChildren(), empty())
    }

    @Test
    fun `specific parent access with children granted`() {
        // Grant access to one parent entity
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = true)
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1))
        assertThat(findAllChildren(), containsInAnyOrder(testChild1a, testChild1b))
    }

    @Test
    fun `all parents access granted`() {
        // Grant access to multiple parent entities
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = false),
            TestAccess(user = testUser, granted = testParent2, allowChildren = false),
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1, testParent2))
        assertThat(findAllChildren(), empty())
    }

    @Test
    fun `specific child access granted`() {
        // Grant access to multiple parent entities, children of a specific parent
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = true), // granted
            TestAccess(user = testUser, granted = testParent2, allowChildren = false),
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1, testParent2))
        assertThat(findAllChildren(), containsInAnyOrder(testChild1a, testChild1b))
    }

    @Test
    fun `other specific child access granted`() {
        // Grant access to multiple parent entities, children of the other specific parent
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = false),
            TestAccess(user = testUser, granted = testParent2, allowChildren = true), // granted
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1, testParent2))
        assertThat(findAllChildren(), containsInAnyOrder(testChild2a))
    }

    @Test
    fun `full access to everything`() {
        grantAccess(
            TestAccess(user = testUser, granted = testParent1, allowChildren = true),
            TestAccess(user = testUser, granted = testParent2, allowChildren = true),
        )

        assertThat(findAllParents(), containsInAnyOrder(testParent1, testParent2))
        assertThat(findAllChildren(), containsInAnyOrder(testChild1a, testChild1b, testChild2a))
    }

    // --- Internal ---

    private fun grantAccess(vararg access: TestAccess) {
        access.forEach { entityManager.persist(it) }
        entityManager.flush()
    }

    private fun findAllParents() = findAll<TestParent>(testParentAccess)

    private fun findAllChildren() = findAll<TestChild>(testChildAccess)

    private inline fun <reified E : Any> findAll(accessControl: AccessControl<E>) =
        findAll(E::class.java, accessControl)

    private fun <E : Any> findAll(entityClass: Class<E>, accessControl: AccessControl<E>): List<E> {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(entityClass)

        val root = cq.from(entityClass)
        val checkAuthorized = accessControl.checkAuthorized(testDescriptor)
        val authorizedPredicate = checkAuthorized.toPredicate(root, cq, cb)

        cq.select(root)
        cq.where(authorizedPredicate)

        return entityManager.createQuery(cq).resultList
    }

    private inline fun <reified E : Any> deleteAll() {
        with(entityManager.criteriaBuilder) {
            val cq = createCriteriaDelete(E::class.java)
            cq.from(E::class.java)
            cq.where(conjunction())
            entityManager.createQuery(cq).executeUpdate()
        }
    }
}
