package de.kiwi.mjml

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ResourceLoader

const val mjmlJsLocation = "classpath:mjml/mjml.js"

/**
 * Autoconfiguration of the MJML e-mail template renderer,
 * which is added to the Kiwi e-mail template rendering pipeline.
 * Can be disabled using `kiwi.mail.mjml.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty("kiwi.mail.mjml.enabled", matchIfMissing = true)
@EnableConfigurationProperties(MjmlMailProperties::class)
class KiwiMjmlAutoConfiguration(
    private val props: MjmlMailProperties,
    private val resources: ResourceLoader,
) {
    @Bean
    @ConditionalOnMissingBean
    fun mjmlMailTemplateRenderer(mjmlCompiler: MjmlCompiler): MjmlMailTemplateRenderer {
        return MjmlMailTemplateRenderer(mjmlCompiler)
    }

    @Bean
    @ConditionalOnMissingBean
    fun mjmlCompiler(): MjmlCompiler = when (props.compiler) {
        MjmlMailProperties.MjmlCompiler.JS -> createJsMjmlCompiler()
    }

    private fun createJsMjmlCompiler() = JsMjmlCompiler(
        jsResource = resources.getResource(mjmlJsLocation),
        fonts = props.fonts.mapValues { (_, uri) -> uri.toString() },
        poolSize = props.poolSize,
    )
}
