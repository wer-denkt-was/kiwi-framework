package de.kiwi.backgroundTask.domain

import de.kiwi.api.backgroundTask.BackgroundTaskRequest
import de.kiwi.backgroundTask.KiwiBackgroundTaskPackagesAutoConfiguration
import de.kiwi.backgroundTask.TestUser
import de.kiwi.backgroundTask.testObjectMapper
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/**
 * Validates the Liquibase changelog against the entity mapping on all supported database systems:
 * Hibernate's schema validation fails the startup if a column is missing or has an incompatible type.
 */
class BackgroundTaskSchemaValidationTest {

    data class ExampleRequest(val message: String) : BackgroundTaskRequest()

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                LiquibaseAutoConfiguration::class.java,
                HibernateJpaAutoConfiguration::class.java,
                KiwiBackgroundTaskPackagesAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "spring.liquibase.change-log=classpath:db/changelog/db.background-tasks-master.xml",
            "spring.jpa.hibernate.ddl-auto=validate",
        )

    @ParameterizedTest
    @ValueSource(strings = ["mysql:8", "mariadb:12.3", "postgresql:18"])
    @Execution(ExecutionMode.CONCURRENT)
    fun changelogMatchesEntityMapping(dbImage: String) {
        contextRunner
            .withPropertyValues("spring.datasource.url=jdbc:tc:${dbImage}:///background_tasks_schema")
            .run { context ->
                assertThat(context).hasNotFailed()

                val entityManagerFactory = context.getBean<EntityManagerFactory>()
                val entityManager = entityManagerFactory.createEntityManager()
                val objectMapper = testObjectMapper()

                // Persist and load a task, including the JSON request and the handle, on all database systems
                val (task) = BackgroundTask.create(
                    taskRequest = ExampleRequest(message = "hello"),
                    taskUser = TestUser(username = "tester"),
                    objectMapper = objectMapper,
                    clock = Clock.systemUTC(),
                )

                entityManager.transaction.begin()
                entityManager.persist(task)
                entityManager.transaction.commit()
                entityManager.clear()

                val loaded = entityManager
                    .createQuery("select t from BackgroundTask t where t.taskHandle = ?1", BackgroundTask::class.java)
                    .setParameter(1, task.taskHandle)
                    .singleResult

                val expectedTaskRequest = ExampleRequest("hello")
                assertThat(loaded.toTaskRequest(objectMapper)).isEqualTo(expectedTaskRequest)
                entityManager.close()
            }
    }
}
