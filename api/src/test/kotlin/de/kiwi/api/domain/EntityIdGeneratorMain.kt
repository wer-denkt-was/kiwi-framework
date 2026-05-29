package de.kiwi.api.domain

import de.kiwi.api.util.slf4j
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private val log = slf4j<EntityIdGeneratorMain>()

/**
 * Main process generating [EntityId] values in a separate JVM.
 * Generated ID values are written into an output text file.
 * The JVM expects to be passed a "kiwi.snowflake.machine.id" system property.
 */
object EntityIdGeneratorMain {

    @JvmStatic
    fun main(args: Array<String>) {
        // Assert snowflake machine ID, log with own PID
        val machineId = checkNotNull(System.getProperty(Snowflake.MACHINE_ID_SYSTEM_PROPERTY)) {
            "Missing system property ${Snowflake.MACHINE_ID_SYSTEM_PROPERTY}"
        }
        val selfPid = ProcessHandle.current().pid()
        log.info("EntityID generator: PID = $selfPid, Machine ID = $machineId")

        // Evaluate process args
        require(args.size == 3) { "Usage: <count> <startTime> <outputFile>" }
        val (countArg, startTimeArg, outputFile) = args
        val count = countArg.toInt()
        val startTimeMs = startTimeArg.toLong()
        val currentTimeMs = System.currentTimeMillis()

        // Wait until common start time to ensure IDs are generated concurrently
        Thread.sleep((startTimeMs - currentTimeMs).coerceAtLeast(0))

        // Generate entity IDs
        val output = buildString {
            repeat(count) {
                val exampleId = EntityId.generate<ExampleId>()
                appendLine(exampleId)
            }
        }

        // Write output to file
        val outputPath = Path.of(outputFile)
        outputPath.parent.let { Files.createDirectories(it) }
        outputPath.writeText(output, Charsets.UTF_8)
    }
}
