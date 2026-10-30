package de.kiwi.demo.application

import tools.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.domain.Roles
import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.EntityManager
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Profile
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ContextConfiguration

@MustBeDocumented
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mysql:latest:///demo-test-db",
        "spring.jpa.hibernate.ddl-auto=validate",
    ]
)
@ContextConfiguration(classes = [DemoDataJpaTestConfig::class])
annotation class DemoDataJpaTest

@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan("de.kiwi.demo.domain")
@ComponentScan("de.kiwi.demo.application", "de.kiwi.demo.infrastructure")
@EnableJpaRepositories("de.kiwi.demo.application")
@Profile("!application-tests") // Disable config for DemoApplicationTests
class DemoDataJpaTestConfig {
    @Bean
    fun objectMapper() = jacksonObjectMapper()

    @Bean
    fun clockProvider() = ClockProvider.SystemUTC
}

// --- Utility/helper methods ---

inline fun <reified E : Any> EntityManager.deleteAll() = deleteAll(E::class.java)

fun <E : Any> EntityManager.deleteAll(entityClass: Class<E>) {
    with(criteriaBuilder) {
        val delete = createCriteriaDelete(entityClass).apply {
            from(entityClass)
            where(conjunction())
        }
        createQuery(delete).executeUpdate()
    }
}

fun dummyUser(username: String, roles: Set<String> = setOf(Roles.USER)) = object : UserDescriptor {
    override val userId = null
    override val identity = username
    override val username = username
    override val authenticated = true
    override val roles = roles
    override val principal = null
}
