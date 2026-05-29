package de.kiwi.api.domain

import de.kiwi.api.domain.Snowflake
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.time.measureTimedValue

private const val DEBUG_LOG = false

class SnowflakeTest {

    @Test
    fun generateManyIds() {
        val count = 100_000
        val (ids, duration) = measureTimedValue {
            List(count) { Snowflake.generate() }
        }

        println("Snowflake test: Generated $count IDs in $duration")
        if (DEBUG_LOG) printAllIds(ids)
        assertEncodeDecodeIds(ids)
        assertUniqueIds(ids)
    }

    @Test
    fun generateIdsWithRandomDelays() {
        val count = 1_000
        val random = Random.Default
        val (ids, duration) = measureTimedValue {
            List(count) {
                Thread.sleep(0, random.nextInt(0, 10))
                Snowflake.generate()
            }
        }

        println("Snowflake test: Generated $count IDs in $duration with random delays")
        if (DEBUG_LOG) printAllIds(ids)
        assertEncodeDecodeIds(ids)
        assertUniqueIds(ids)
    }

    @Test
    fun generateIdsMultiThreaded() {
        val count = 100_000
        val threads = 10
        val executable = Callable { Snowflake.generate() }
        val threadPool = Executors.newFixedThreadPool(threads)

        val (ids, duration) = measureTimedValue {
            threadPool
                .invokeAll(List(count) { executable })
                .map { it.get()!! }
        }

        println("Snowflake multi-thread test: Generated $count IDs on $threads threads in $duration")
        if (DEBUG_LOG) printAllIds(ids)
        assertEncodeDecodeIds(ids)
        assertUniqueIds(ids)
    }

    @Test
    fun extractIdTimestamp() {
        val utc = Clock.systemUTC()
        val earliestTimestamp = Instant.now(utc)
        Thread.sleep(1)
        val ids = List(100_000) { Snowflake.generate() }
        Thread.sleep(1)
        val latestTimestamp = Instant.now(utc)

        assertAll(ids.map { id ->
            Executable {
                val extractedTimestamp = Snowflake.extractTimestamp(id)
                assertTrue(extractedTimestamp >= earliestTimestamp)
                assertTrue(extractedTimestamp <= latestTimestamp)
            }
        })
    }
}

private fun printAllIds(ids: List<Long>) {
    println(
        "[ Nr.]>         Decimal ID ::      Encoded ::     |                                Timestamp |   HostId | Seq"
    )

    ids.forEachIndexed { i, id ->
        val label = i.toString().padStart(4)
        val decimal = id.toString(10).padStart(18, ' ')
        val binary = id.toString(2).padStart(64, '0')
        val encoded = Snowflake.encode(id).padStart(12)

        // Add markers for bit groups: "unused | timestamp | machine | sequence"
        val binaryMarked = StringBuilder(binary)
        listOf(Snowflake.SEQUENCE_ID_BITS, Snowflake.MACHINE_ID_BITS, Snowflake.TIMESTAMP_BITS)
            .runningFold(0) { sum, n -> sum + n }
            .drop(1)
            .forEach { binaryMarked.insert(64 - it, '|') }
        println("[$label]> $decimal :: $encoded :: $binaryMarked")
    }
}

private fun assertUniqueIds(ids: List<Long>) {
    val countPerId = ids.groupingBy { it }.eachCount()
    assertAll(countPerId.map {
        Executable {
            assertEquals(1, it.value) {
                "Duplicated ID: [${it.key}] occurred ${it.value} times"
            }
        }
    })
}

private fun assertEncodeDecodeIds(ids: List<Long>) {
    assertAll(ids.map { id ->
        Executable {
            val encoded = Snowflake.encode(id)
            val decoded = Snowflake.decode(encoded)
            assertEquals(id, decoded, "Discrepancy encode/decode ID")
        }
    })
}
