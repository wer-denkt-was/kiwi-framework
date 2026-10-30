package de.kiwi.demo

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Contact
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * This is a Kiwi-Framework demo application,
 * with the functionality a to-do list management tool
 */
@SpringBootApplication
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
