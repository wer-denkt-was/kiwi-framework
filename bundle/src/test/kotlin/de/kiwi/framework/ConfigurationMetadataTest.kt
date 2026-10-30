package de.kiwi.framework

import de.kiwi.mjml.MjmlMailProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.ConfigurationProperties
import tools.jackson.databind.json.JsonMapper
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * The configuration processor only documents properties it can detect as JavaBean properties (with setters), see
 * [KiwiFrameworkProperties]. This test fails if a property is missing in the generated metadata.
 */
class ConfigurationMetadataTest {

    @Test
    fun `metadata documents all framework properties`() {
        assertThat(configurationMetadataPropertyNames())
            .containsAll(expectedPropertyNames(KiwiFrameworkProperties::class))
    }

    @Test
    fun `metadata documents all MJML properties`() {
        assertThat(configurationMetadataPropertyNames())
            .containsAll(expectedPropertyNames(MjmlMailProperties::class))
    }
}

/** Names of the properties bound by [type], nested data classes being property groups */
internal fun expectedPropertyNames(type: KClass<*>): List<String> {
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
internal fun configurationMetadataPropertyNames(): Set<String> {
    val mapper = JsonMapper()
    return ConfigurationMetadataTest::class.java.classLoader
        .getResources("META-INF/spring-configuration-metadata.json").toList()
        .flatMap { url -> url.openStream().use { mapper.readTree(it)["properties"].values() } }
        .map { it["name"].asString() }
        .toSet()
}
