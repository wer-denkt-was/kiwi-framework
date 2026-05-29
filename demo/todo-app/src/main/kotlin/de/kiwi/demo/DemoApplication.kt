package de.kiwi.demo

import de.kiwi.auditLog.KiwiAuditLogConfiguration
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.storage.KiwiStorageConfiguration
import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * This is a Kiwi-Framework demo application,
 * with the functionality a to-do list management tool
 */
@SpringBootApplication
@Import(
    KiwiFrameworkConfiguration::class,
    KiwiAuditLogConfiguration::class,
    KiwiStorageConfiguration::class,
)
@EntityScan
@EnableJpaRepositories
@OpenAPIDefinition(
    info = Info(
        version = "1.0",
        title = "Kiwi Todo-App Demo Application",
        description = "Simple Todo-App Server, demonstrating the usage of the Kiwi Framework",
        contact = Contact(name = "Alexander Dahmen", email = "dahmen@werdenktwas.de"),
    )
)
class DemoApplication

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}
