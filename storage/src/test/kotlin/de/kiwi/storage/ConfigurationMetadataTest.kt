package de.kiwi.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * The configuration processor only documents properties it can detect as JavaBean properties (with setters), see
 * [KiwiStorageProperties]. This test fails if a property is missing in the generated metadata.
 */
class ConfigurationMetadataTest {

    @Test
    fun `metadata documents all storage properties`() {
        assertThat(configurationMetadataPropertyNames())
            .containsAll(expectedPropertyNames(KiwiStorageProperties::class))
    }
}

/** Names of the properties bound by [type], nested data classes being property groups */
private fun expectedPropertyNames(type: KClass<*>): List<String> {
    val annotation = requireNotNull(type.findAnnotation<ConfigurationProperties>())
    return expectedPropertyNames(type, annotation.prefix.ifEmpty { annotation.value })
}

private fun expectedPropertyNames(type: KClass<*>, prefix: String): List<String> =
    requireNotNull(type.primaryConstructor).parameters.flatMap { parameter ->
        val name = "$prefix.${requireNotNull(parameter.name).replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()}"
        val parameterType = parameter.type.classifier as KClass<*>
        if (parameterType.isData) expectedPropertyNames(parameterType, name) else listOf(name)
    }

/** Names of all properties in the configuration metadata files on the classpath */
private fun configurationMetadataPropertyNames(): Set<String> {
    val mapper = JsonMapper()
    return ConfigurationMetadataTest::class.java.classLoader
        .getResources("META-INF/spring-configuration-metadata.json").toList()
        .flatMap { url -> url.openStream().use { mapper.readTree(it)["properties"].values() } }
        .map { it["name"].asString() }
        .toSet()
}
