package de.kiwi.mjml

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Configuration properties (prefix `kiwi.mail.mjml`). Properties are `var` to be included in the configuration
 * metadata, see `KiwiFrameworkProperties` of the kiwi-framework module.
 */
@ConfigurationProperties("kiwi.mail.mjml")
data class MjmlMailProperties(
    /** Enable the MJML e-mail template renderer */
    var enabled: Boolean = true,
    var compiler: MjmlCompiler = MjmlCompiler.JS,
    var fonts: Map<String, URI> = mutableMapOf(),
    /** Number of JS contexts compiling MJML concurrently, each one loads its own instance of the MJML compiler */
    var poolSize: Int = 1,
) {
    enum class MjmlCompiler { JS }
}
