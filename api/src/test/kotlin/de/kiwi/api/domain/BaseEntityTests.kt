package de.kiwi.api.domain

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.Snowflake
import jakarta.persistence.Embeddable
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class BaseEntityTests {
    @Embeddable
    class TestId(value: Long) : EntityId(value)

    @Embeddable
    class DifferentId(value: Long) : EntityId(value)

    @Test
    fun generate() {
        assertRepeated(100) {
            assertDoesNotThrow {
                val id = EntityId.generate<TestId>()
                println("generated: $id, value: ${id.value}")
            }
        }
    }

    @Test
    fun fromString() {
        assertRepeated(100) {
            val id = EntityId.generate<TestId>()
            val string = id.toString()
            val decoded = EntityId.fromString<TestId>(string)
            assertEquals(id, decoded)
        }
    }

    @Test
    fun fromValue() {
        assertRepeated(100) {
            val id = EntityId.generate<TestId>()
            val value = id.value
            val decoded = EntityId.fromValue<TestId>(value)
            assertEquals(id, decoded)
        }
    }

    @Test
    fun equalsTrue() {
        val value = Snowflake.generate()
        val a = TestId(value)
        val b = TestId(value)
        assertTrue(a == b)
    }

    @Test
    fun hashCodeEqual() {
        val value = Snowflake.generate()
        val a = TestId(value)
        val b = TestId(value)
        assertTrue(a.hashCode() == b.hashCode())
    }

    @Test
    fun unequalTypes() {
        val value = Snowflake.generate()
        val a = TestId(value)
        val b = DifferentId(value)
        assertFalse(a.equals(b))
    }
}

private fun assertRepeated(count: Int, executable: Executable) {
    assertAll(generateSequence { executable }.take(count).toList())
}
