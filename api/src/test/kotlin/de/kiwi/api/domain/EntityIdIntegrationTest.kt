package de.kiwi.api.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.io.path.bufferedReader
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class ExampleId(value: Long) : EntityId(value)

class EntityIdIntegrationTest {

    @Test
    fun generateOnSingleProcess() {
        // Generate many IDs, assert that all of them are unique
        val idCount = 1_000
        val (generatedIds, time) = measureTimedValue {
            List(idCount) { EntityId.generate<ExampleId>() }
        }

        println("Generating $idCount IDs took $time")
        //println("Generated single process IDs = $generatedIds")
        assertUniqueEntityIds(generatedIds)
    }

    @RepeatedTest(4)
    fun generateOnMultipleProcesses() {
        val machineIds = listOf(1, 2, 3, 5, 8, 13) // Fibonacci
        Assumptions.assumeTrue(machineIds.size == machineIds.distinct().size, "Non-distinct machine IDs")

        val generatorFutures = machineIds.map { machineId -> runEntityIdGeneratorProcess(machineId) }
        val resultsFuture = generatorFutures.awaitAll()
        val results = resultsFuture.join()
        val generatedIds = results.flatMap { it }

        //println("Generated multi process IDs = $generatedIds")
        assertUniqueEntityIds(generatedIds)
    }

    private fun assertUniqueEntityIds(ids: List<EntityId>) {
        val countById = ids.groupingBy { it }.eachCount()
        val duplicateCountById = countById.filterValues { it > 1 }
        duplicateCountById.forEach { (id, count) -> println("EntityId [$id] generated $count times") }
        assertEquals(0, duplicateCountById.size, "Generated $duplicateCountById duplicated IDs")
    }

    private fun runEntityIdGeneratorProcess(machineId: Int): CompletableFuture<List<ExampleId>> {
        val idCount = 5_000
        val sharedStartTime = System.currentTimeMillis() + 2.seconds.inWholeMilliseconds
        val outputFile = Files.createTempFile("entity-id-gen-$machineId", ".txt")

        val processBuilder = buildJvmProcess(
            "-D${Snowflake.MACHINE_ID_SYSTEM_PROPERTY}=$machineId",
            EntityIdGeneratorMain::class.java.canonicalName,
            idCount.toString(),
            sharedStartTime.toString(),
            outputFile.toString(),
        )

        return CompletableFuture.supplyAsync {
            val process = processBuilder.start()
            val exitStatus = process.waitFor()
            assertEquals(0, exitStatus, "Unexpected generator exit status: $exitStatus")

            outputFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { line -> EntityId.fromString<ExampleId>(line) }.toList()
            }
        }.whenComplete { _, _ ->
            Files.deleteIfExists(outputFile)
        }
    }

    private fun buildJvmProcess(vararg args: String): ProcessBuilder {
        val javaHome = System.getProperty("java.home")!!
        val classpath = System.getProperty("java.class.path")!!
        val isWindows = System.getProperty("os.name", "unknown").lowercase().contains("win")
        val javaBin = if (isWindows) """$javaHome\bin\java.exe""" else "$javaHome/bin/java"
        Assumptions.assumeTrue(Files.exists(Path.of(javaBin)), "Missing Java binary: $javaBin")

        val javaCommand = listOf(javaBin, "-cp", classpath, *args)

        return ProcessBuilder(javaCommand)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
    }
}

private fun <T> Collection<CompletableFuture<T>>.awaitAll(): CompletableFuture<List<T>> {
    return CompletableFuture
        .allOf(*toTypedArray())
        .thenApply { map { it.join() } }
}
