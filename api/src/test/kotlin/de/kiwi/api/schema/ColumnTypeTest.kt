package de.kiwi.api.schema

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.Validation
import de.kiwi.api.domain.ValidationFailed
import jakarta.persistence.DiscriminatorValue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.function.Executable
import org.springframework.util.unit.DataSize
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.random.Random

class ColumnTypeTest {

    @Test
    fun validate() {
        val stringType = ColumnType.identity<String>()
        val notBlankType = stringType validate Validation.notBlank

        val blankValue = "     "
        val notBlankValue = "bla bla bla"

        // Format is not validated
        assertEquals(notBlankValue, stringType.format(notBlankValue))
        assertEquals(blankValue, stringType.format(blankValue))
        assertEquals(notBlankValue, notBlankType.format(notBlankValue))
        assertEquals(blankValue, notBlankType.format(blankValue))

        // Parse is validated
        assertEquals(notBlankValue, stringType.parse(notBlankValue))
        assertEquals(blankValue, stringType.parse(blankValue))
        assertEquals(notBlankValue, notBlankType.parse(notBlankValue))
        assertThrows<ValidationFailed> { notBlankType.parse(blankValue) }
    }

    @Test
    fun map() {
        val toIntType = ColumnType<String, Int>(format = { it.toInt(10) }, parse = { it.toString(10) })
        val toIntTimesTwoType = toIntType map { it * 2 }

        // Format is mapped
        assertEquals(8, toIntType.format("8"))
        assertEquals(16, toIntTimesTwoType.format("8"))

        // Parse is not mapped
        assertEquals("8", toIntType.parse(8))
        assertEquals("8", toIntTimesTwoType.parse(8))
    }

    @Test
    fun pipe() {
        val prefixType = ColumnType<String, String>(
            format = { "prefix.$it" },
            parse = { it.removePrefix("prefix.") }
        )
        val toUpperType = ColumnType<String, String>(
            format = { it.uppercase() },
            parse = { it },
        )

        val prefixToUpperType = prefixType pipe toUpperType
        val upperToPrefixType = toUpperType pipe prefixType

        val value = "Hello World"

        val prefixToUpperValue = prefixToUpperType.format(value)
        val prefixToUpperParsed = prefixToUpperType.parse(prefixToUpperValue)

        val toUpperPrefixValue = upperToPrefixType.format(value)
        val toUpperPrefixParsed = prefixToUpperType.parse(toUpperPrefixValue)

        assertEquals("PREFIX.HELLO WORLD", prefixToUpperValue)
        assertEquals("prefix.HELLO WORLD", toUpperPrefixValue)

        assertEquals("PREFIX.HELLO WORLD", prefixToUpperParsed)
        assertEquals("HELLO WORLD", toUpperPrefixParsed)
    }

    enum class TestEnum { Foo, Bar }

    @Test
    fun enum() {
        val testEnumType = ColumnType.enum<TestEnum>()

        assertEquals("Foo", testEnumType.format(TestEnum.Foo))
        assertEquals("Bar", testEnumType.format(TestEnum.Bar))

        assertEquals(TestEnum.Foo, testEnumType.parse("Foo"))
        assertEquals(TestEnum.Bar, testEnumType.parse("Bar"))
        assertThrows<IllegalArgumentException> { testEnumType.parse("Invalid!!") }
    }

    class TestId(value: Long) : EntityId(value)

    @Test
    fun entityId() {
        val testEntityIdType = EntityIdColumnType(TestId::class.java)

        val testId = EntityId.generate<TestId>()
        assertEquals(testId.toString(), testEntityIdType.format(testId))
        assertEquals(testId, testEntityIdType.parse(testId.toString()))
        assertInstanceOf(TestId::class.java, testEntityIdType.parse(testId.toString()))
    }

    @Test
    fun stringify() {
        val stringifyIntType = ColumnType.stringify<Int>()
        val stringifyAndParseIntType = ColumnType.stringify(parseString = { it.toInt() })

        assertEquals("1234", stringifyIntType.format(1234))
        assertEquals("1234", stringifyAndParseIntType.format(1234))

        assertThrows<ColumnType.ParseNotSupported> { stringifyIntType.parse("1234") }
        assertEquals(1234, stringifyAndParseIntType.parse("1234"))
    }

    @DiscriminatorValue("testDiscriminator")
    class TestEntity

    @Test
    fun entityClassName() {
        val classNameType = EntityClassNameType
        assertEquals("TestEntity", classNameType.format(TestEntity::class.java))
        assertThrows<ColumnType.ParseNotSupported> { classNameType.parse("TestEntity") }
    }

    @Test
    fun discriminatorValueType() {
        val discriminatorValueType = EntityDiscriminatorValueType
        assertEquals("testDiscriminator", discriminatorValueType.format(TestEntity::class.java))
        assertThrows<ColumnType.ParseNotSupported> { discriminatorValueType.parse("testDiscriminator") }
    }

    private fun randomDate() = LocalDate.ofYearDay(Random.nextInt(1980, 2100), Random.nextInt(1, 366))
    private fun randomTime() = LocalTime.of(Random.nextInt(0, 24), Random.nextInt(0, 60), Random.nextInt(0, 60))
    private fun randomDateTime() = randomDate().atTime(randomTime())
    private fun randomDataSize() = DataSize.ofBytes(Random.nextLong(0L, 0x1_00_00L))
    private fun randomBigInteger() = BigInteger.valueOf(Random.nextLong())
    private fun randomBigDecimal() = BigDecimal.valueOf(Random.nextDouble())

    @Test
    fun localDateType() {
        testRepeatedly { assertReversibleFormat(IsoLocalDateType, randomDate()) }
    }

    @Test
    fun localTimeType() {
        testRepeatedly { assertReversibleFormat(IsoLocalTimeType, randomTime()) }
    }

    @Test
    fun localDateTimeType() {
        testRepeatedly { assertReversibleFormat(IsoLocalDateTimeType, randomDateTime()) }
    }

    @Test
    fun invalidDateTimeWithTimezone() {
        assertThrows<DateTimeParseException> {
            val randomZonedDateTime = randomDateTime().atZone(ZoneOffset.ofHours(+2))
            val isoZonedDateTime = randomZonedDateTime.format(DateTimeFormatter.ISO_ZONED_DATE_TIME)
            IsoLocalDateTimeType.parse(isoZonedDateTime)
        }
    }

    @Test
    fun dataSizeType() {
        testRepeatedly { assertReversibleFormat(DataSizeType, randomDataSize()) }
    }

    @Test
    fun uriType() {
        assertReversibleFormat(UriType, URI.create("https://test.com/foo?bar=baz#qux"))
        assertThrows<IllegalArgumentException> { UriType.parse("this is not a valid URI! >:[") }
    }

    @Test
    fun bigIntegerType() {
        testRepeatedly { assertReversibleFormat(BigIntegerType, randomBigInteger()) }
        assertThrows<NumberFormatException> { BigIntegerType.parse("this is not a valid integer! -_-") }
    }

    @Test
    fun bigDecimalType() {
        testRepeatedly { assertReversibleFormat(BigDecimalType, randomBigDecimal()) }
        assertThrows<NumberFormatException> { BigDecimalType.parse("this is not a valid decimal! ò_ó") }
    }

    private fun testRepeatedly(action: () -> Unit) {
        assertAll(List(1000) { Executable { action() } })
    }

    private fun <T : Any, M : Any> assertReversibleFormat(type: ColumnType<T, M>, value: T) {
        val formatted = type.format(value)
        val parsed = type.parse(formatted)
        assertEquals(value, parsed, formatted.toString())
    }
}
