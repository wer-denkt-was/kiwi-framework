package de.kiwi.framework.web

import tools.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.domain.EntityId
import de.kiwi.api.generic.GenericEntityFilter
import de.kiwi.api.schema.StandardColumnFilterConversion
import de.kiwi.framework.SpringBootApplicationWithoutKiwi
import de.kiwi.framework.infrastructure.generic.*
import de.kiwi.framework.infrastructure.jpa.JpaEntityTypeProvider
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hibernate.query.sqm.tree.SqmVisitableNode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.core.convert.ConversionService
import org.springframework.util.LinkedMultiValueMap

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class DynamicColumnFilterTest {

    @SpringBootApplicationWithoutKiwi
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    class TestApplicationConfig {
        @Bean
        fun objectMapper() = jacksonObjectMapper()

        @Bean
        fun conversionService() = ConversionServiceFactoryBean()
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var conversionService: ConversionService

    private lateinit var dynamicColumnFilter: DynamicColumnFilter

    @BeforeEach
    fun setUp() {
        val entityTypeProvider = JpaEntityTypeProvider()
        entityTypeProvider.entityManager = entityManager
        entityTypeProvider.afterPropertiesSet()

        val columnFilterConversion = StandardColumnFilterConversion(conversionService, entityTypeProvider)
        dynamicColumnFilter = DynamicColumnFilter(columnFilterConversion)
    }

    @Test
    fun addColumnFilters() {
        val schema = AuthorSchema
        val filter = AuthorFilter()
        assertThat(filter.filterValues(), anEmptyMap())

        val params: Map<String, List<String>> = mapOf(
            "email" to listOf("first@localhost", "second@localhost"),
            "fullName" to listOf("First Name", "Second Name", "Third Name"),
            "thisColumnDoesNotExist" to listOf("so it will not appear", "in the filter"),
        )

        dynamicColumnFilter.addColumnFilters(schema, filter, LinkedMultiValueMap(params))

        println(filter.filterValues())
        filter.assertFilterValues(
            hasEntry("email:in", listOf("first@localhost", "second@localhost")),
            hasEntry("fullName:in", listOf("First Name", "Second Name", "Third Name")),
        )

        val hql = filterToHql(filter)
        println("HQL> $hql")
        hql.assertHqlPredicates(
            stringContainsInOrder("email", "in", "(", "first@localhost", "second@localhost", ")"),
            stringContainsInOrder("fullName", "in", "(", "First Name", "Second Name", "Third Name", ")"),
        )
    }

    @Test
    fun addColumnFiltersIgnoringCase() {
        val filter = AuthorFilter()
        val params = mapOf(
            "EMAIL" to listOf("first@localhost"),
            "fullname" to listOf("Name"),
        )

        dynamicColumnFilter.addColumnFilters(AuthorSchema, filter, LinkedMultiValueMap(params))

        filter.assertFilterValues(
            hasEntry("email:in", listOf("first@localhost")),
            hasEntry("fullName:in", listOf("Name")),
        )
    }

    @Test
    fun addColumnFilterWithConversion() {
        val schema = PublicationSchema
        val filter = PublicationFilter()
        assertThat(filter.filterValues(), anEmptyMap())

        val testPublisherId = EntityId.generate<PublisherId>()
        val params: Map<String, List<String>> = mapOf(
            "type" to listOf("Book"),
            "firstPublishedOn" to listOf("2024-03-28"),
            "publisherId" to listOf(testPublisherId.toString())
        )

        dynamicColumnFilter.addColumnFilters(schema, filter, LinkedMultiValueMap(params))

        println(filter.filterValues())
        filter.assertFilterValues(
            hasEntry("type:in", listOf("Book")),
            hasEntry("publisherId:in", listOf(testPublisherId.toString())),
            hasEntry("firstPublishedOn:in", listOf("2024-03-28")),
        )

        val hql = filterToHql(filter)
        println("HQL> $hql")
        hql.assertHqlPredicates(
            stringContainsInOrder("type(", ")", "in", "(", Book::class.qualifiedName, ")"),
            // Hibernate 7 renders embeddable EntityId literals in serialized form,
            // database filtering by EntityId is covered by AnalyticalReportQueryHandlerTest
            stringContainsInOrder("publisher.id", "in", "("),
            stringContainsInOrder("firstPublishedOn", "in", "(", "2024-03-28", ")"),
        )
    }

    @Test
    fun addColumnFilterWithOperators() {
        val schema = PublicationSchema
        val filter = PublicationFilter()

        val params = mapOf(
            "title:contains" to listOf("for Dummies"),
            "description:null" to listOf("false"),
            "lastPublishedOn:ge" to listOf("1996-03-28"),
            "lastPublishedOn:le" to listOf("2024-03-28"),
        )

        dynamicColumnFilter.addColumnFilters(schema, filter, LinkedMultiValueMap(params))

        println(filter.filterValues())
        filter.assertFilterValues(
            hasEntry("title:contains", listOf("for Dummies")),
            hasEntry("description:null", listOf("false")),
            hasEntry("lastPublishedOn:ge", listOf("1996-03-28")),
            hasEntry("lastPublishedOn:le", listOf("2024-03-28")),
        )

        val hql = filterToHql(filter)
        println("HQL> $hql")
        hql.assertHqlPredicates(
            stringContainsInOrder("title", "like", "%for Dummies%", "escape", "\\"),
            stringContainsInOrder("description", "is", "not", "null"),
            stringContainsInOrder("lastPublishedOn", ">=", "1996-03-28"),
            stringContainsInOrder("lastPublishedOn", "<=", "2024-03-28"),
        )
    }

    // ---

    private inline fun <reified E : Any> filterToHql(filter: GenericEntityFilter<E>): String =
        filterToHql(filter, E::class.java)

    private fun <E : Any> filterToHql(filter: GenericEntityFilter<E>, entityClass: Class<E>): String {
        with(entityManager.criteriaBuilder) {
            val query = createQuery(entityClass)
            val root = query.from(entityClass)
            query.select(root)

            val predicate = filter.toPredicate(root, query, this)
            predicate as? SqmVisitableNode ?: fail("expected SqmVisitableNode")
            return predicate.toHqlString()
        }
    }

    private fun GenericEntityFilter<*>.assertFilterValues(vararg entries: Matcher<Map<out String, *>>) {
        assertThat(filterValues(), allOf(aMapWithSize(entries.size), *entries))
    }

    private fun String.assertHqlPredicates(vararg matchPredicates: Matcher<String>) {
        // Assert that HQL exclusively contains the given predicates
        val conjunction = Regex("and", RegexOption.IGNORE_CASE)
        val predicates = split(conjunction).map { it.trim() }
        assertThat(predicates, containsInAnyOrder(*matchPredicates))
    }
}
