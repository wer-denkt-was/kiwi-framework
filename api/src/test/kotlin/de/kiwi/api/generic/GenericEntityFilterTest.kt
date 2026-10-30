package de.kiwi.api.generic

import de.kiwi.api.application.service.MetamodelEntityTypeProvider
import de.kiwi.api.domain.EntityId_
import de.kiwi.api.schema.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Predicate
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hibernate.query.sqm.tree.SqmVisitableNode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.core.convert.ConversionService
import kotlin.reflect.KClass

class MyEntityFilter : GenericEntityFilter<MyEntity>() {
    var id by filter(entityId())
    var foo by filter(equalTo(attribute(MyEntity_.foo)))
    var fooInList by filter(inList(attribute(MyEntity_.foo)))
    var minBar by filter(greaterOrEqual(attribute(MyEntity_.bar)))
    var maxBar by filter(lessOrEqual(attribute(MyEntity_.bar)))
    var hasChild by filter(hasChild(MyEntity_.children, childEntityId()))
    var childExists by filter<ChildId>(existsSubquery { _, root, value ->
        val child = root.join(MyEntity_.children)
        equal(child[MyChild_.id][EntityId_.idValue], value.value)
    })
}

class MyChildFilter : GenericEntityFilter<MyChild>() {
    var id by filter(entityId())
}

/**
 * This test needs to run within an active JPA Hibernate context
 * in order to initialize the JPA metamodel attributes
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class GenericEntityFilterTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("de.kiwi.api")
    class TestConfiguration {
        @Bean
        fun conversionService() = ConversionServiceFactoryBean()
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    lateinit var conversionService: ConversionService

    lateinit var columnFilterConversion: ColumnFilterConversion

    @BeforeEach
    fun setUp() {
        val entityTypeProvider = MetamodelEntityTypeProvider(entityManager.metamodel)
        columnFilterConversion = StandardColumnFilterConversion(conversionService, entityTypeProvider)
    }

    @Test
    fun testFilterEmpty() {
        val filter = MyEntityFilter()
        assertThat(filter.filterValues(), anEmptyMap())

        val query = generateQuery(MyEntity::class, filter)
        assertThat(query.restriction, nullValue())
    }

    @Test
    fun testFilterValues() {
        val filter = MyEntityFilter()
        filter.id = setOf(MyId(1), MyId(2), MyId(3))
        filter.foo = "Foo Value"
        filter.fooInList = listOf("Foo Value", "Second", "Third")
        filter.minBar = -100
        filter.maxBar = 100
        filter.hasChild = setOf(ChildId(9001), ChildId(9002))
        filter.childExists = ChildId(9003)

        println(filter.filterValues())
        filter.assertFilterValues(
            hasEntry("id", setOf(MyId(1), MyId(2), MyId(3))),
            hasEntry("foo", "Foo Value"),
            hasEntry("fooInList", listOf("Foo Value", "Second", "Third")),
            hasEntry("minBar", -100),
            hasEntry("maxBar", 100),
            hasEntry("hasChild", setOf(ChildId(9001), ChildId(9002))),
            hasEntry("childExists", ChildId(9003))
        )

        val query = generateQuery(MyEntity::class, filter)
        assertNotNull(query.restriction)
        query.restriction.assertHqlPredicates(
            stringContainsInOrder("id.idValue", "in", "(1, 2, 3)"),
            stringContainsInOrder("foo", "=", "Foo Value"),
            stringContainsInOrder("foo", "in", "Foo Value", "Second", "Third"),
            stringContainsInOrder("bar", ">=", "-100"),
            stringContainsInOrder("bar", "<=", "100"),
            stringContainsInOrder(
                "exists", "(", "select 1 from", "children", "where", "id.idValue", "in", "(9001, 9002)", ")",
            ),
            stringContainsInOrder(
                "exists", "(", "select 1 from", "children", "where", "id.idValue", "=", "9003", ")",
            )
        )
    }

    @Test
    fun setColumnFilter() {
        val filter = MyChildFilter()
        filter.setColumnFilter(MyChildSchema.message, InListOperator, "Hello", "World")

        println(filter.filterValues())
        filter.assertFilterValues(hasEntry("message:in", listOf("Hello", "World")))

        val query = generateQuery(MyChild::class, filter)
        assertNotNull(query.restriction)
        query.restriction.assertHqlPredicates(
            stringContainsInOrder("message", "in", "Hello", "World"),
        )
    }

    @Test
    fun testColumnFilterWithConversion() {
        val filter = MyEntityFilter()
        filter.setColumnFilter(MyEntitySchema.discriminator, EqualToOperator, "MyEntity")
        filter.setColumnFilter(MyEntitySchema.bar, LessThanOperator, "100")
        filter.setColumnFilter(MyEntitySchema.bar, GreaterOrEqualOperator, "0")
        filter.setColumnFilter(MyEntitySchema.date, InListOperator, "1996-03-28", "2024-03-28")

        println(filter.filterValues())
        filter.assertFilterValues(
            hasEntry("bar:ge", listOf("0")), // Parsed as Int
            hasEntry("bar:lt", listOf("100")), // Parsed as Int
            hasEntry("discriminator:eq", listOf("MyEntity")), // Parsed as Class<*> by entity name
            hasEntry("date:in", listOf("1996-03-28", "2024-03-28")), // Parsed as LocalDate by ISO format
        )

        val query = generateQuery(MyEntity::class, filter)
        assertNotNull(query.restriction)
        query.restriction.assertHqlPredicates(
            anyOf(
                stringContainsInOrder(MyEntity_.class_.name, "=", "class", MyEntity::class.qualifiedName),
                // Hibernate 7 renders the type of an entity without subtypes as a constant literal
                stringContainsInOrder("'${MyEntity::class.qualifiedName}'", "=", "'${MyEntity::class.qualifiedName}'"),
            ),
            stringContainsInOrder(MyEntity_.BAR, ">=", "0"),
            stringContainsInOrder(MyEntity_.BAR, "<", "100"),
            stringContainsInOrder(MyEntity_.DATE, "in", "(", "1996-03-28", "2024-03-28", ")"),
        )
    }

    @Test
    fun testColumnFilterWithPatternMatching() {
        val filter = MyChildFilter()
        filter.setColumnFilter(MyChildSchema.searchableMessage, StringContainsOperator, "Example Pattern")

        println(filter.filterValues())
        filter.assertFilterValues(hasEntry("searchableMessage:contains", listOf("Example Pattern")))

        val query = generateQuery(MyChild::class, filter)
        assertNotNull(query.restriction)
        query.restriction.assertHqlPredicates(
            stringContainsInOrder("message", "like", "%Example Pattern%", "escape", "\\"),
        )
    }

    // ---

    private fun <E : Any, T, M> GenericEntityFilter<E>.setColumnFilter(
        column: ColumnField<E, T, M>,
        operator: ColumnFilterOperator,
        vararg paramValues: String,
    ) = setColumnFilter(
        values = listOf(*paramValues),
        column = column,
        operator = operator,
        conversion = columnFilterConversion
    )

    private fun <E : Any> generateQuery(entityType: KClass<E>, filter: GenericEntityFilter<E>): CriteriaQuery<E> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(entityType.java)
        val root = query.from(entityType.java)
        query.select(root)
        filter.toPredicate(root, query, builder)?.let { query.where(it) }
        return query
    }

    private fun GenericEntityFilter<*>.assertFilterValues(vararg entries: Matcher<Map<out String, *>>) {
        assertThat(filterValues(), allOf(aMapWithSize(entries.size), *entries))
    }

    private fun Predicate.assertHqlPredicates(vararg matchPredicates: Matcher<String>) {
        // Assert that HQL predicate contains all given conjunctions
        val node = this as? SqmVisitableNode ?: fail("expected SqmVisitableNode")
        val hql = node.toHqlString()
        println("HQL> $hql")

        val conjunction = Regex("and", RegexOption.IGNORE_CASE)
        val predicates = hql.split(conjunction).map { it.trim() }
        assertThat(predicates, containsInAnyOrder(*matchPredicates))
    }
}
