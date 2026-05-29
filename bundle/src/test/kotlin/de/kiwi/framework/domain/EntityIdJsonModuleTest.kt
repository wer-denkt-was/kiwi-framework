package de.kiwi.framework.domain

import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityIdJsonModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode

class EntityIdJsonModuleTest {
    private val json = jacksonObjectMapper().apply {
        registerModule(EntityIdJsonModule())
    }

    class FirstId(value: Long) : EntityId(value)
    class SecondId(value: Long) : EntityId(value)
    data class TestObject(val first: FirstId, val second: SecondId)

    @Test
    fun testSerialize() {
        val firstId = EntityId.generate<FirstId>()
        val secondId = EntityId.generate<SecondId>()
        val data = TestObject(first = firstId, second = secondId)
        val actualJson = json.writeValueAsString(data)

        // language=json
        val expectedJson = """{ "first": "$firstId", "second": "$secondId" }"""
        JSONAssert.assertEquals(expectedJson, actualJson, JSONCompareMode.NON_EXTENSIBLE)
    }

    @Test
    fun testDeserialize() {
        val firstId = EntityId.generate<FirstId>()
        val secondId = EntityId.generate<SecondId>()
        val expectedData = TestObject(first = firstId, second = secondId)

        // language=json
        val serialized = """{ "first": "$firstId", "second": ${secondId.value} }"""
        val actualData = json.readValue<TestObject>(serialized)
        assertEquals(expectedData, actualData)
    }

    @Test
    fun testDeserializeInvalid() {
        // language=json
        val invalid = """{ "first": "Not a valid EntityId!", "second": "" }"""

        val exception = assertThrows<JsonMappingException> { json.readValue<TestObject>(invalid) }
        println(exception.message)
    }
}
