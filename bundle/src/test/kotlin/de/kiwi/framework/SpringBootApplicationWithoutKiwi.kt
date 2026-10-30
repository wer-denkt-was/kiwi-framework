package de.kiwi.framework

import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.infrastructure.mail.KiwiMailAutoConfiguration
import de.kiwi.framework.web.KiwiOpenApiAutoConfiguration
import de.kiwi.framework.web.KiwiWebAutoConfiguration
import de.kiwi.mjml.KiwiMjmlAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * Test application configuration excluding all Kiwi auto-configurations,
 * for tests setting up the Kiwi components under test explicitly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootApplication(
    exclude = [
        KiwiFrameworkAutoConfiguration::class,
        KiwiBusAutoConfiguration::class,
        KiwiMailAutoConfiguration::class,
        KiwiWebAutoConfiguration::class,
        KiwiOpenApiAutoConfiguration::class,
        KiwiMjmlAutoConfiguration::class,
    ],
)
annotation class SpringBootApplicationWithoutKiwi
