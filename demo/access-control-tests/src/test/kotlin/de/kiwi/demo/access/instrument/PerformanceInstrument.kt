package de.kiwi.demo.access.instrument

import de.kiwi.api.util.slf4j
import org.junit.jupiter.api.TestInfo
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull

private val log = slf4j<PerformanceInstrument>()

class PerformanceInstrument(
    private val explainInstrument: ExplainInstrument,
    private val processors: List<PerformanceProcessor>,
) {
    fun <T> analyze(info: TestInfo, block: () -> T): T {
        val (result, explains) = explainInstrument.executeWithExplain(block)
        processExplains(info, explains)
        return result
    }

    private fun processExplains(info: TestInfo, explains: List<ExplainResult>) {
        processors.forEach { it.process(info, explains) }
    }
}

interface PerformanceProcessor {
    fun process(info: TestInfo, explains: List<ExplainResult>)
}

abstract class BuildArtifactPerformanceProcessor : PerformanceProcessor {
    override fun process(info: TestInfo, explains: List<ExplainResult>) {
        val directory = outputDirectory()
        directory.createDirectories()
        val outputFile = directory.resolve(outputFilename(info))
        val outputData = processOutput(explains)
        outputFile.writeText(outputData, Charsets.UTF_8)
    }

    private fun outputDirectory(): Path {
        val classUri = javaClass.getResource("/")!!.toURI()
        val buildPath = Path.of(classUri).parent.parent.parent
        return buildPath.resolve("test-results/performance")
    }

    abstract fun outputFilename(info: TestInfo): String

    abstract fun processOutput(explains: List<ExplainResult>): String
}

private fun colorCode(colors: IntArray) = colors.joinToString(separator = ";") + "m"
private fun ansiControl(code: String) = "\u001B[$code"
private val ansiReset = ansiControl("0m")

private const val FG_BLACK = 30
private const val FG_RED = 31
private const val FG_GREEN = 32
private const val FG_YELLOW = 33
private const val FG_BLUE = 34
private const val FG_MAGENTA = 35
private const val FG_CYAN = 36
private const val FG_WHITE = 37

private const val BG_BLACK = 40
private const val BG_RED = 41
private const val BG_GREEN = 42
private const val BG_YELLOW = 43
private const val BG_BLUE = 44
private const val BG_MAGENTA = 45
private const val BG_CYAN = 46
private const val BG_WHITE = 47

class LogPerformanceProcessor(
    private val ansiOutput: Boolean = true,
) : PerformanceProcessor {

    private inline fun ansiColor(vararg colors: Int, message: () -> String) =
        if (ansiOutput) ansiControl(colorCode(colors)) + message() + ansiReset
        else message()

    override fun process(info: TestInfo, explains: List<ExplainResult>) {
        val testClassName = info.testClass.map { it.name }.getOrNull() ?: "<unknown class>"
        val testMethodName = info.testMethod.map { it.name }.getOrNull() ?: "<unknown method>"
        log.info("SQL Performance analysis for ${testClassName}::${testMethodName}")
        explains.forEachIndexed { queryIndex, explain ->
            log.info(">> Explain Query #$queryIndex: ${explain.sql.replace('\n', ' ')}")
            explain.explainJson.allTables().forEachIndexed { tableIndex, table ->
                val accessInfo = table.accessInfo()
                log.info("  - Table #${tableIndex} ${table.tableName}: ${accessDescription(accessInfo)}")
            }
            explain.explainJson.allSubqueries().forEachIndexed { subqueryIndex, subquery ->
                log.info("  - Subquery #${subqueryIndex} (dependent=${subquery.dependent}):")
                val accessInfos = subquery.accessInfos()
                if (accessInfos.isEmpty()) log.info("    * No access")
                else accessInfos.forEach { info -> log.info("    * ${accessDescription(info)}") }
            }
        }
    }

    private fun accessDescription(access: ExplainJson.AccessInfo): String {
        val (tableName, accessType, usedKey) = access
        val (accessBg, accessFg) = when (accessType.quality) {
            ExplainJson.AccessTypeQuality.Best -> BG_CYAN to FG_BLACK
            ExplainJson.AccessTypeQuality.Good -> BG_GREEN to FG_BLACK
            ExplainJson.AccessTypeQuality.Okay -> BG_YELLOW to FG_BLACK
            ExplainJson.AccessTypeQuality.Bad -> BG_RED to FG_BLACK
        }

        val accessDescription = ansiColor(accessBg, accessFg) { "${accessType.name} (${accessType.quality.name})" }
        val keyDescription = usedKey
            ?.let { "Key: ${ansiColor(FG_BLUE) { it }}" }
            ?: "Key: ${ansiColor(FG_RED) { "No key" }}"

        return """Access "$tableName", ${keyDescription}, $accessDescription"""
    }
}
