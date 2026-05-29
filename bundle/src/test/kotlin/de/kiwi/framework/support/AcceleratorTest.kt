package de.kiwi.framework.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.generic.PageResult
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.lessThan
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.util.unit.DataSize

private val json = jacksonObjectMapper()
private val prettyJson = json.writerWithDefaultPrettyPrinter()
private val toJson = json::writeValueAsBytes
private val toPrettyJson = prettyJson::writeValueAsString

private val paddingData = "/".repeat(64)
private val testData = listOf<Any>(
    mapOf(
        "id" to 10001,
        "name" to "first",
        "training" to mapOf(
            "id" to 9001,
            "title" to "some training",
            "padding" to paddingData,
            "organization" to mapOf<String, Any>(
                "id" to 333,
                "country" to "Germany",
            )
        ),
        "items" to listOf(
            mapOf<String, Any>("item" to "AAA", "padding" to paddingData),
            mapOf<String, Any>("item" to "BBB", "padding" to paddingData),
        )
    ),
    mapOf(
        "id" to 10002,
        "name" to "second",
        "training" to mapOf(
            "id" to 8999,
            "title" to "another training",
            "padding" to paddingData,
            "organization" to mapOf<String, Any>(
                "id" to 333,
                "country" to "Germany",
            ),
        ),
        "items" to listOf(
            mapOf<String, Any>("item" to "AAA", "padding" to paddingData),
            mapOf<String, Any>("item" to "CCC", "padding" to paddingData),
        )
    ),
    mapOf(
        "id" to 10003,
        "name" to "third",
        "training" to mapOf(
            "id" to 9001,
            "title" to "some training",
            "padding" to paddingData,
            "organization" to mapOf<String, Any>(
                "id" to 333,
                "country" to "Germany",
            )
        ),
        "items" to listOf(
            mapOf<String, Any>("item" to "BBB", "padding" to paddingData),
            mapOf<String, Any>("item" to "CCC", "padding" to paddingData),
        )
    )
)
private val testPage = PageResult(
    items = listOf(
        mapOf("id" to 1, "redundant" to mapOf("this" to "is some redundant payload")),
        mapOf("id" to 2, "redundant" to mapOf("this" to "is some redundant payload")),
        mapOf("id" to 3, "redundant" to mapOf("this" to "is some redundant payload")),
        mapOf("id" to 4, "redundant" to mapOf("this" to "is some redundant payload")),
        mapOf("id" to 5, "redundant" to mapOf("this" to "is some redundant payload")),
    ),
    pageSize = 5,
    pageNumber = 0,
    totalPages = 1,
    totalItems = 5
)

class AcceleratorTest {
    @Test
    fun accelerate() {
        val accelerated = Accelerator.accelerate(testData)
        val decelerated = accelerated.decelerate()
        assertAccelerationSanity(testData, accelerated, decelerated, expectAcceleration = true)
    }

    @Test
    fun acceleratePage() {
        val accelerated = Accelerator.acceleratePage(testPage)
        val decelerated = accelerated.deceleratePage()
        assertAccelerationSanity(testPage, accelerated, decelerated, expectAcceleration = true)
    }

    @Test
    fun doNotAccelerateListOfPrimitives() {
        val input = listOf("Hello", "World")
        val accelerated = Accelerator.accelerate(input)
        val decelerated = accelerated.decelerate()
        assertAccelerationSanity(input, accelerated, decelerated, expectAcceleration = false)
    }

    @Test
    fun doNotAcceleratePrimitive() {
        val input = "Hello World"
        val accelerated = Accelerator.accelerate(input)
        val decelerated = accelerated.decelerate()
        assertAccelerationSanity(input, accelerated, decelerated, expectAcceleration = false)
    }

    @Test
    fun accelerateJson() {
        val input = json.readTree(json.writeValueAsString(testData))
        val accelerated = Accelerator.accelerate(input)
        val decelerated = accelerated.decelerate()
        assertAccelerationSanity(input, accelerated, decelerated, expectAcceleration = true)
    }
}

private fun <T> assertAccelerationSanity(
    input: T,
    accelerated: Accelerated<out T>,
    decelerated: Any?,
    expectAcceleration: Boolean,
) {
    println("INPUT >>> ${toPrettyJson(input)}")
    println()
    println("ACCELERATED >>> ${toPrettyJson(accelerated)}")
    println()
    println("DECELERATED >>> ${toPrettyJson(decelerated)}")

    assertNotNull(accelerated)
    assertNotNull(decelerated)
    assertEquals(input, decelerated) { "Deceleration mismatch" }
    assertEquals(expectAcceleration, accelerated.accelerated) { "Acceleration mismatch" }

    // Assert that this whole thing even makes sense:
    // Accelerated content should be smaller (less redundant) than raw input
    if (accelerated.accelerated) {
        val beforeJson = toJson(input)
        val afterJson = toJson(accelerated)
        println(">>> BEFORE SIZE = ${DataSize.ofBytes(beforeJson.size.toLong())}")
        println(">>> AFTER SIZE  = ${DataSize.ofBytes(afterJson.size.toLong())}")
        assertThat(afterJson.size, lessThan(beforeJson.size))
    }
}
