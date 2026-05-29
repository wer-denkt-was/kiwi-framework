package de.kiwi.api.schema

import de.kiwi.api.schema.NullEmbeddableHandler
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.lang.reflect.Method
import kotlin.reflect.KProperty1
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.typeOf

object MyEntitySchema : EntitySchema<MyEntity>() {
    val id by entityId()
    val type by entityType()
    val discriminator by entityDiscriminator()
    val foo by column(MyEntity::foo)
    val bar by column(MyEntity::bar)
    val baz by column(MyEntity::baz)
    val qux by column(MyEntity::qux)
    val qux1 by column(attribute(MyEntity::qux, NestedQux::first))
    val qux2 by column(attribute(MyEntity::qux, NestedQux::second))
    val date by column(MyEntity::date, type = IsoLocalDateType)
    val children by toMany(MyEntity::children, MyChildSchema)
}

val asLongType = ColumnType<String, Long>(
    format = { it.toLongOrNull() ?: Long.MIN_VALUE },
    parse = { it.toString() },
)

val uppercaseType = ColumnType.map<String> { it.uppercase() }

object MyChildSchema : EntitySchema<MyChild>() {
    val id by entityId()
    val parent by toOne(MyChild::parent, MyEntitySchema)
    val parentId by parentEntityId(MyChild::parent)
    val message by column(MyChild::message)
    val loudMessage by column(MyChild::message, type = uppercaseType)
    val numericMessage by column(MyChild::message, type = asLongType)
    val searchableMessage by column(MyChild::message, type = LikeStringColumnType)
}

/**
 * This test needs to run within an active JPA Hibernate context
 * in order to initialize the JPA metamodel attributes
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
class QuerySchemaTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan("de.kiwi.api")
    class TestConfiguration

    @Test
    fun myEntitySchema() {
        println(MyEntitySchema)

        MyEntitySchema.id.assertThat(fieldName = "id", primaryKey = true, MyEntity::id)
        MyEntitySchema.type.assertThat(fieldName = "type", primaryKey = false, isType = true)
        MyEntitySchema.discriminator.assertThat(fieldName = "discriminator", primaryKey = false, isType = true)
        MyEntitySchema.foo.assertThat(fieldName = "foo", primaryKey = false, MyEntity::foo)
        MyEntitySchema.bar.assertThat(fieldName = "bar", primaryKey = false, MyEntity::bar)
        MyEntitySchema.baz.assertThat(fieldName = "baz", primaryKey = false, MyEntity::baz)
        MyEntitySchema.qux.assertThat(fieldName = "qux", primaryKey = false, MyEntity::qux)
        MyEntitySchema.qux1.assertThat(fieldName = "qux1", primaryKey = false, MyEntity::qux, NestedQux::first)
        MyEntitySchema.qux2.assertThat(fieldName = "qux2", primaryKey = false, MyEntity::qux, NestedQux::second)
        MyEntitySchema.date.assertThat(fieldName = "date", primaryKey = false, MyEntity::date)
        MyEntitySchema.children.assertThat(fieldName = "children", target = MyChildSchema, MyEntity::children)

        assertThat(
            MyEntitySchema.columns,
            containsInAnyOrder(
                MyEntitySchema.id,
                MyEntitySchema.type,
                MyEntitySchema.discriminator,
                MyEntitySchema.foo,
                MyEntitySchema.bar,
                MyEntitySchema.baz,
                MyEntitySchema.qux,
                MyEntitySchema.qux1,
                MyEntitySchema.qux2,
                MyEntitySchema.date,
            )
        )
        assertThat(MyEntitySchema.primaryKeys, containsInAnyOrder(MyEntitySchema.id))
        assertThat(MyEntitySchema.singlePrimaryKey(), sameInstance(MyEntitySchema.id))
        assertThat(MyEntitySchema.toOneReferences, empty())
        assertThat(MyEntitySchema.toManyReferences, containsInAnyOrder(MyEntitySchema.children))
        assertThat(MyEntitySchema.toManyMapReferences, empty())
    }

    @Test
    fun myChildSchema() {
        println(MyChildSchema)

        MyChildSchema.id.assertThat(fieldName = "id", primaryKey = true, MyChild::id)
        MyChildSchema.parent.assertThat(fieldName = "parent", target = MyEntitySchema, MyChild::parent)
        MyChildSchema.parentId.assertThat(fieldName = "parentId", primaryKey = false, MyChild::parent, MyChild::id)
        MyChildSchema.message.assertThat(fieldName = "message", primaryKey = false, MyChild::message)
        MyChildSchema.loudMessage.assertThat(fieldName = "loudMessage", primaryKey = false, MyChild::message)
        MyChildSchema.numericMessage.assertThat(fieldName = "numericMessage", primaryKey = false, MyChild::message)
        MyChildSchema.searchableMessage
            .assertThat(fieldName = "searchableMessage", primaryKey = false, MyChild::message)

        assertThat(
            MyChildSchema.columns,
            containsInAnyOrder(
                MyChildSchema.id,
                MyChildSchema.parentId,
                MyChildSchema.message,
                MyChildSchema.loudMessage,
                MyChildSchema.numericMessage,
                MyChildSchema.searchableMessage,
            )
        )
        assertThat(MyChildSchema.primaryKeys, containsInAnyOrder(MyChildSchema.id))
        assertThat(MyChildSchema.singlePrimaryKey(), sameInstance(MyChildSchema.id))
        assertThat(MyChildSchema.toOneReferences, containsInAnyOrder(MyChildSchema.parent))
        assertThat(MyChildSchema.toManyReferences, empty())
        assertThat(MyChildSchema.toManyMapReferences, empty())
    }
}

private fun ColumnField<*, *, *>.assertThat(
    fieldName: String,
    primaryKey: Boolean,
    vararg path: KProperty1<*, *>,
    isType: Boolean = false,
) {
    assertEquals(fieldName, this.fieldName)
    assertEquals(primaryKey, this.primaryKey)

    if (isType) {
        assertInstanceOf(de.kiwi.api.schema.ColumnSelector.EntityTypeSelector::class.java, this.selector)
    } else {
        val selector = assertInstanceOf(de.kiwi.api.schema.ColumnSelector.AttributeSelector::class.java, this.selector)
        val expectedPath = path.map { it.name to it.javaMember }
        val actualPath = selector.path.attributes.map { it.name to it.javaMember }
        assertEquals(expectedPath, actualPath)
    }
}

private fun ToManyField<*, *, *, *>.assertThat(
    fieldName: String,
    target: AbstractSchema<*, *>,
    attribute: KProperty1<*, *>,
) {
    assertEquals(fieldName, this.fieldName)
    assertEquals(target, this.toMany)
    assertEquals(attribute.name to attribute.javaMember, this.attribute.name to this.attribute.javaMember)
}

private fun ToOneField<*, *>.assertThat(
    fieldName: String,
    target: AbstractSchema<*, *>,
    attribute: KProperty1<*, *>,
) {
    assertEquals(fieldName, this.fieldName)
    assertEquals(target, this.toOne)
    assertEquals(attribute.name to attribute.javaMember, this.attribute.name to this.attribute.javaMember)
}

private val KProperty1<*, *>.javaMember get() = javaGetter ?: javaField
