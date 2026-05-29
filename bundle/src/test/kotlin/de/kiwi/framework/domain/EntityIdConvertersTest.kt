package de.kiwi.framework.domain

import de.kiwi.api.domain.*
import de.kiwi.api.domain.*
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.springframework.core.convert.ConversionService
import org.springframework.core.convert.support.DefaultConversionService

class EntityIdConvertersTest {
    private lateinit var conversionService: ConversionService

    @BeforeEach
    fun setUp() {
        val instance = DefaultConversionService()
        instance.addConverter(EntityIdToStringConverter)
        instance.addConverter(EntityIdToNumberConverter)
        instance.addConverterFactory(NumberToEntityIdConverterFactory)
        instance.addConverterFactory(StringToEntityIdConverterFactory)
        conversionService = instance
    }

    class FirstId(id: Long) : EntityId(id)
    class SecondId(id: Long) : EntityId(id)

    @Test
    fun convertStringToAnonymous() = withEntityId<FirstId> {
        val string = it.toString()
        val anonymous = EntityId.fromValueAnonymous(it.value)
        assertConversionToId<EntityId>(string, anonymous)
    }

    @Test
    fun convertNumberToAnonymous() = withEntityId<SecondId> {
        val number = it.value
        val anonymous = EntityId.fromValueAnonymous(it.value)
        assertConversionToId<EntityId>(number, anonymous)
    }

    @Test
    fun convertStringToEntityId() = withEntityId<FirstId> {
        val string = it.toString()
        assertConversionToId<FirstId>(string, it)
    }

    @Test
    fun convertNumberToEntityId() = withEntityId<SecondId> {
        val number = it.value
        assertConversionToId<SecondId>(number, it)
    }

    @Test
    fun convertEntityIdToString() = withEntityId<FirstId> {
        val expected = it.toString()
        val actual = conversionService.convert(it, String::class.java)
        assertEquals(expected, actual)
    }

    @Test
    fun convertEntityIdToNumber() = withEntityId<SecondId> {
        val expected = it.value
        val actual = conversionService.convert(it, Number::class.java)
        assertEquals(expected, actual)
    }

    private inline fun <reified ID : EntityId> withEntityId(block: (ID) -> Unit) = block(EntityId.generate<ID>())

    private inline fun <reified ID : EntityId> assertConversionToId(from: Any, expected: EntityId) =
        assertConversionToId(from, ID::class.java, expected)

    private fun <ID : EntityId> assertConversionToId(from: Any, toType: Class<ID>, expected: EntityId) {
        val fromType = from.javaClass
        assertTrue(conversionService.canConvert(fromType, toType)) {
            "Could not convert from [${fromType.name}] to ${toType.name}"
        }

        val actual = conversionService.convert(from, toType)
        assertThat(actual, instanceOf(expected.javaClass))
        assertThat(actual?.value, allOf(notNullValue(), equalTo(expected.value)))
    }
}
