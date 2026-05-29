package de.kiwi.framework.application.generic

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import de.kiwi.api.application.generic.GenericEntityQueryHandler
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.PageResult
import de.kiwi.framework.example.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hamcrest.TypeSafeMatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort

@ExampleDataJpaTest
class GenericEntityQueryHandlerTest {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var handler: de.kiwi.api.application.generic.GenericEntityQueryHandler

    private lateinit var ex1: ExampleEntity
    private lateinit var ex2: ExampleEntity
    private lateinit var ex3: ExampleEntity

    @BeforeEach
    fun setUp() {
        ex1 = entityManager.merge(ExampleEntity.create("First").value)
        ex2 = entityManager.merge(ExampleEntity.create("Second", optional = "Hello").value)
        ex3 = entityManager.merge(ExampleEntity.create("Third").value)
        entityManager.flush()
        entityManager.clear()
    }

    @AfterEach
    fun tearDown() {
        entityManager.remove(entityManager.merge(ex1))
        entityManager.remove(entityManager.merge(ex2))
        entityManager.remove(entityManager.merge(ex3))
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    fun handleFindAll() {
        val query = ExampleQuery()
        query.fieldset = ExampleFieldsets.default
        query.pagination = Pageable.unpaged(Sort.by(ExampleEntity_.ID))

        val result = handler.handle(query, UserDescriptor.SYSTEM)
        println(result)

        assertThat(
            result,
            matchPage(
                pageSize = 3,
                pageNumber = 0,
                totalPages = 1,
                totalItems = 3,
                matchExampleDefaultItem(ex1.id, ex1.name, ex1.optional),
                matchExampleDefaultItem(ex2.id, ex2.name, ex2.optional),
                matchExampleDefaultItem(ex3.id, ex3.name, ex3.optional),
            )
        )
    }

    @Test
    fun handleFindFirstPage() {
        val query = ExampleQuery()
        query.fieldset = ExampleFieldsets.default
        query.pagination = PageRequest.ofSize(2).withSort(Sort.by(ExampleEntity_.ID))

        val result = handler.handle(query, UserDescriptor.SYSTEM)
        println(result)

        assertThat(
            result,
            matchPage(
                pageSize = 2,
                pageNumber = 0,
                totalPages = 2,
                totalItems = 3,
                matchExampleDefaultItem(ex1.id, ex1.name, ex1.optional),
                matchExampleDefaultItem(ex2.id, ex2.name, ex2.optional),
            )
        )
    }

    @Test
    fun handleFindSecondPage() {
        val query = ExampleQuery()
        query.fieldset = ExampleFieldsets.default
        query.pagination = PageRequest.ofSize(2).withPage(1).withSort(Sort.by(ExampleEntity_.ID))

        val result = handler.handle(query, UserDescriptor.SYSTEM)
        println(result)

        assertThat(
            result,
            matchPage(
                pageSize = 2,
                pageNumber = 1,
                totalPages = 2,
                totalItems = 3,
                matchExampleDefaultItem(ex3.id, ex3.name, ex3.optional),
            )
        )
    }

    @Test
    fun handleFindOne() {
        val query = ExampleQuery()
        query.id = ex2.id
        query.fieldset = ExampleFieldsets.default

        val result = handler.handle(query, UserDescriptor.SYSTEM)
        println(result)

        assertThat(
            result,
            matchPage(
                pageSize = 1,
                pageNumber = 0,
                totalPages = 1,
                totalItems = 1,
                matchExampleDefaultItem(ex2.id, ex2.name, ex2.optional),
            )
        )
    }
}

private fun matchPage(
    pageSize: Int,
    pageNumber: Int,
    totalPages: Int,
    totalItems: Long,
    vararg items: Matcher<*>,
): Matcher<PageResult<*>> = allOf(
    hasProperty("pageSize", equalTo(pageSize)),
    hasProperty("pageNumber", equalTo(pageNumber)),
    hasProperty("totalPages", equalTo(totalPages)),
    hasProperty("totalItems", equalTo(totalItems)),
    hasProperty(
        "items",
        contains(items.toList())
    )
)

private fun matchItem(
    vararg fields: Pair<String, Matcher<JsonNode>>,
): Matcher<ObjectNode> = allOf(
    instanceOf(ObjectNode::class.java),
    iterableWithSize(fields.size),
    allOf(fields.map { (name, matchValue) -> ObjectNodeMatcher(name, matchValue) })
)

private fun matchExampleDefaultItem(id: ExampleId, name: String, optional: String?) =
    matchItem(
        "id" to equalTo(id.toString().asJsonNode()),
        "name" to equalTo(name.asJsonNode()),
        "optional" to equalTo(optional.asJsonNode()),
    )

private fun String?.asJsonNode() = if (this == null) NullNode.instance else TextNode.valueOf(this)

private class ObjectNodeMatcher(
    private val matchKey: Matcher<String>,
    private val matchValue: Matcher<JsonNode>,
) : TypeSafeMatcher<ObjectNode>() {
    constructor(key: String, matchValue: Matcher<JsonNode>) : this(equalTo(key), matchValue)

    override fun matchesSafely(item: ObjectNode): Boolean {
        return item.properties().any { (fieldName, value) ->
            matchKey.matches(fieldName) && matchValue.matches(value)
        }
    }

    override fun describeTo(description: Description) {
        description
            .appendText("ObjectNode containing key ")
            .appendDescriptionOf(matchKey)
            .appendText(" and value ")
            .appendDescriptionOf(matchValue)
    }
}
