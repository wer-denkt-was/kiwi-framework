package de.kiwi.gradle

import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

/**
 * Attaches the Mockito agent to test JVMs, as required for inline mocking since JDK 21.
 * The agent configuration is resolved lazily when the test task executes, not while configuring the build.
 * See: https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html#mockito-instrumentation
 */
class MockitoAgentArgumentProvider(
    @get:Classpath
    val mockitoAgent: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = mockitoAgent.files.map { "-javaagent:${it.absolutePath}" }
}
