package de.kiwi.framework.mjml

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties("kiwi.mail.mjml")
data class MjmlMailProperties(
    var compiler: MjmlCompiler = MjmlCompiler.JS,
    var fonts: Map<String, URI> = mutableMapOf(),
) {
    enum class MjmlCompiler { JS }
}
