package de.kiwi.api.schema

import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.time.LocalDate

object MyEntityFieldsets : FieldsetsAvailable<MyEntity>(MyEntitySchema) {
    override val default by defineFieldset {
        includeAllColumns()
        includeToMany(MyEntitySchema.children, MyChildFieldsets.short)
    }

    val short by defineFieldset {
        includeAllColumns()
    }

    val veryShort by defineFieldset {
        include(MyEntitySchema.id, MyEntitySchema.foo)
    }
}

object MyChildFieldsets : FieldsetsAvailable<MyChild>(MyChildSchema) {
    override val default by defineFieldset {
        includeAllColumns()
        includeToOne(MyChildSchema.parent, MyEntityFieldsets.short)
    }

    val short by defineFieldset {
        include(MyChildSchema.id, MyChildSchema.message, MyChildSchema.loudMessage)
    }

    val idOnly by singleColumnFieldset(MyChildSchema.id)
}

/**
 * This test needs to run within an active JPA Hibernate context
 * in order to initialize the JPA metamodel attributes
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class QueryFieldsetTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("de.kiwi.api")
    class TestConfiguration

    @Test
    fun myEntityFieldsets() {
        println("--- MyEntity ---")
        println(MyEntityFieldsets)
        MyEntityFieldsets.allFieldsets.forEach { println(it) }

        assertThat(
            MyEntityFieldsets.allFieldsets,
            containsInAnyOrder(
                sameInstance(MyEntityFieldsets.default),
                sameInstance(MyEntityFieldsets.short),
                sameInstance(MyEntityFieldsets.veryShort),
            )
        )

        assertThat(
            MyEntityFieldsets.default,
            matchEntityFieldset(
                hasColumns = listOf("id", "type", "discriminator", "foo", "bar", "baz", "qux", "qux1", "qux2", "date"),
                hasToOne = emptyMap(),
                hasToMany = mapOf("children" to MyChildFieldsets.short),
            )
        )
        assertThat(
            MyEntityFieldsets.short,
            matchEntityFieldset(
                hasColumns = listOf("id", "type", "discriminator", "foo", "bar", "baz", "qux", "qux1", "qux2", "date"),
                hasToOne = emptyMap(),
                hasToMany = emptyMap(),
            )
        )
        assertThat(
            MyEntityFieldsets.veryShort,
            matchEntityFieldset(
                hasColumns = listOf("id", "foo"),
                hasToOne = emptyMap(),
                hasToMany = emptyMap(),
            )
        )
    }

    @Test
    fun myChildFieldsets() {
        println("--- MyChild ---")
        println(MyChildFieldsets)
        MyChildFieldsets.allFieldsets.forEach { println(it) }

        assertThat(
            MyChildFieldsets.allFieldsets,
            containsInAnyOrder(
                sameInstance(MyChildFieldsets.default),
                sameInstance(MyChildFieldsets.short),
                sameInstance(MyChildFieldsets.idOnly),
            )
        )

        assertThat(
            MyChildFieldsets.default,
            matchEntityFieldset(
                hasColumns = listOf("id", "parentId", "message", "loudMessage", "numericMessage", "searchableMessage"),
                hasToOne = mapOf("parent" to MyEntityFieldsets.short),
                hasToMany = emptyMap(),
                hasToManyMap = emptyMap(),
            )
        )
        assertThat(
            MyChildFieldsets.short,
            matchEntityFieldset(
                hasColumns = listOf("id", "message", "loudMessage"),
                hasToOne = emptyMap(),
                hasToMany = emptyMap(),
                hasToManyMap = emptyMap(),
            )
        )
        assertThat(
            MyChildFieldsets.idOnly,
            matchSingleColumnFieldset("id")
        )
    }

    @Test
    fun extractMyEntity() {
        val instance = MyEntity(
            id = MyId(123L),
            foo = "This is MyEntity",
            bar = 999,
            baz = true,
            date = LocalDate.parse("1996-03-28"),
            qux = NestedQux(
                first = "Hello World",
                second = "Goodbye Moon",
            )
        ).apply {
            children.add(
                MyChild(
                    id = ChildId(9001L),
                    parent = this,
                    message = "I am the first child"
                )
            )
            children.add(
                MyChild(
                    id = ChildId(9002L),
                    parent = this,
                    message = "I am the second child",
                )
            )
        }

        val result = MyEntityFieldsets.default.extract(instance)
        println("MyEntity::default -> $result")
        //TODO: Assert that extraction data is correct
    }
}

private fun matchEntityFieldset(
    hasColumns: Collection<String> = emptyList(),
    hasToOne: Map<String, Fieldset<*>> = emptyMap(),
    hasToMany: Map<String, Fieldset<*>> = emptyMap(),
    hasToManyMap: Map<String, FieldsetPair<*, *>> = emptyMap(),
): Matcher<Fieldset<*>> = allOf(
    instanceOf(EntityFieldset::class.java),
    hasProperty("columns", containsInAnyOrder<Any>(hasColumns.map {
        hasProperty("fieldName", equalTo(it))
    })),
    hasProperty("toOne", containsEntriesInAnyOrder(hasToOne.map { (name, fieldset) ->
        hasEntry(hasProperty("fieldName", equalTo(name)), sameInstance(fieldset))
    })),
    hasProperty("toMany", containsEntriesInAnyOrder(hasToMany.map { (name, fieldset) ->
        hasEntry(hasProperty("fieldName", equalTo(name)), sameInstance(fieldset))
    })),
    hasProperty("toManyMap", containsEntriesInAnyOrder(hasToManyMap.map { (name, fieldsets) ->
        hasEntry(
            hasProperty("fieldsetName", equalTo(name)),
            allOf(
                hasProperty("first", sameInstance(fieldsets.first)),
                hasProperty("second", sameInstance(fieldsets.second)),
            )
        )
    }))
)

private fun matchSingleColumnFieldset(
    hasFieldName: String,
): Matcher<Fieldset<*>> = allOf(
    instanceOf(SingleColumnFieldset::class.java),
    hasProperty("singleColumn", hasProperty<ColumnField<*, *, *>>("fieldName", equalTo(hasFieldName)))
)

private fun containsEntriesInAnyOrder(
    hasEntries: Collection<Matcher<Map<*, *>>>,
): Matcher<Map<*, *>> = allOf(
    aMapWithSize(hasEntries.size),
    allOf(hasEntries),
)
