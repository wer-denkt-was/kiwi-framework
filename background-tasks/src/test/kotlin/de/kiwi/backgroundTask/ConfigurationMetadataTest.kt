package de.kiwi.backgroundTask

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.module.kotlin.jacksonObjectMapper
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * The configuration processor only documents properties it can detect as JavaBean properties (with setters),
 * see [KiwiBackgroundTaskProperties].
 * This test fails if a property is missing in the generated metadata.
 */
class ConfigurationMetadataTest {

    @Test
    fun `metadata documents all background task properties`() {
        assertThat(configurationMetadataPropertyNames())
            .containsAll(expectedPropertyNames<KiwiBackgroundTaskProperties>())
    }
}

/** Names of the properties bound by [T], nested data classes being property groups */
private inline fun <reified T : Any> expectedPropertyNames(): List<String> {
    val type = T::class
    val annotation = requireNotNull(type.findAnnotation<ConfigurationProperties>())
    return expectedPropertyNames(type, annotation.prefix.ifEmpty { annotation.value })
}

private fun expectedPropertyNames(type: KClass<*>, prefix: String): List<String> {
    val constructor = requireNotNull(type.primaryConstructor)
    return constructor.parameters.flatMap { parameter ->
        val suffix = requireNotNull(parameter.name).toSnakeCase()
        val name = "$prefix.$suffix"
        val parameterType = parameter.type.classifier as KClass<*>
        if (parameterType.isData) expectedPropertyNames(parameterType, name) else listOf(name)
    }
}

private fun String.toSnakeCase() = replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

/** Names of all properties in the configuration metadata files on the classpath */
private fun configurationMetadataPropertyNames(): Set<String> {
    val mapper = jacksonObjectMapper()
    val resolver = PathMatchingResourcePatternResolver(ConfigurationMetadataTest::class.java.classLoader)
    val resources = resolver.getResources("classpath*:META-INF/spring-configuration-metadata.json").toList()
    return resources
        .asSequence()
        .onEach { resource -> println("Found metadata in: ${resource.uri}") }
        .map { resource -> resource.inputStream.use { mapper.readTree(it) } }
        .flatMap { tree -> tree["properties"].values() }
        .map { property -> property["name"].asString() }
        .toSet()
}
