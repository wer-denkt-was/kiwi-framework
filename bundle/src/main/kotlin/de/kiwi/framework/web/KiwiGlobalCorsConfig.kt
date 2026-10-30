package de.kiwi.framework.web

import de.kiwi.framework.KiwiFrameworkProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("kiwi.web.global-cors.enabled", matchIfMissing = false)
class KiwiGlobalCorsConfig(private val props: KiwiFrameworkProperties) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        val config = props.web.globalCors
        if (!config.enabled) return

        val headers = config.allowedHeaders.toList()
        val methods = config.allowedMethods.map { it.name() }
        val origins = config.allowedOrigins.ifEmpty {
            throw IllegalStateException("At least one allowed origin is required in kiwi.web.global-cors.allowed-origins")
        }

        // Register global mapping for all API Endpoints
        registry.addMapping("/**")
            .allowedOrigins(*origins.toTypedArray())
            .applyIf(headers.isNotEmpty()) { allowedHeaders(*headers.toTypedArray()) }
            .applyIf(methods.isNotEmpty()) { allowedMethods(*methods.toTypedArray()) }
            .allowCredentials(config.allowCredentials)
            .maxAge(config.maxAge.toSeconds())
    }
}

private inline fun <T> T.applyIf(condition: Boolean, block: T.() -> Unit): T =
    apply { if (condition) block() }
