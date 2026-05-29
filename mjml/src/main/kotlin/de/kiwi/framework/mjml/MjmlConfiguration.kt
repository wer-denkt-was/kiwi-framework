package de.kiwi.framework.mjml

import org.springframework.boot.autoconfigure.AutoConfigureOrder
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.ResourceLoader

const val mjmlJsLocation = "classpath:mjml/mjml.js"

@Configuration
@EnableConfigurationProperties(MjmlMailProperties::class)
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE) // Must initialize bean before KiwiFrameworkConfiguration
class MjmlConfiguration(
    private val props: MjmlMailProperties,
    private val resources: ResourceLoader,
) {

    @Bean
    fun mjmlMailTemplateRenderer(mjmlCompiler: MjmlCompiler): MjmlMailTemplateRenderer {
        return MjmlMailTemplateRenderer(mjmlCompiler)
    }

    @Bean
    fun mjmlCompiler(): MjmlCompiler = when (props.compiler) {
        MjmlMailProperties.MjmlCompiler.JS -> createJsMjmlCompiler()
    }

    private fun createJsMjmlCompiler() = JsMjmlCompiler(
        jsResource = resources.getResource(mjmlJsLocation),
        fonts = props.fonts.mapValues { (_, uri) -> uri.toString() },
    )
}
