package de.kiwi.framework.support

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.framework.infrastructure.generic.*
import org.junit.jupiter.api.Test

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import java.time.LocalDateTime

/**
 * Create PersistenceContext to initialize JPA metamodel
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class FieldsetSourceGeneratorTest {

    @SpringBootApplication(exclude = [KiwiFrameworkConfiguration::class])
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    class TestApplicationConfig {
        @Bean
        @ConditionalOnMissingBean
        fun objectMapper() = jacksonObjectMapper()
    }

    @Test
    fun generateSource() {
        val options = TypescriptOptions()
        val renderer = TypescriptFieldsetSourceRenderer(options)
        val generator = FieldsetSourceGenerator(
            moduleName = "GenericTestSchema",
            fileHeader = listOf("--- Test Schema ---", LocalDateTime.now().toString()),
            renderer = renderer
        )

        val source = generator.generateSource(
            FieldsetSources(
                entityClasses = listOf(
                    Publisher::class.java,
                    Author::class.java,
                    Publication::class.java,
                ),
                entitySchemas = listOf(
                    PublisherSchema,
                    AuthorSchema,
                    PublicationSchema,
                ),
                fieldsetAggregates = listOf(
                    PublisherFieldsets,
                    AuthorFieldsets,
                    PublicationFieldsets,
                ),
                createCommandTypes = emptyList(),
                updateCommandTypes = emptyList(),
                genericCommandTypes = emptyList(),
            )
        )

        println("//>> ${source.filename}:")
        println(source.sourceCode)
        println("//---")

        //TODO: Assertions
    }

    @Test
    fun generateSourceMutable() {
        val options = TypescriptOptions(readOnlyFields = false, readOnlyArrays = false)
        val renderer = TypescriptFieldsetSourceRenderer(options)
        val generator = FieldsetSourceGenerator(
            moduleName = "GenericTestSchema",
            fileHeader = listOf("--- Mutable Test Schema ---", LocalDateTime.now().toString()),
            renderer = renderer
        )

        val source = generator.generateSource(
            FieldsetSources(
                entityClasses = listOf(
                    Publisher::class.java,
                    Author::class.java,
                    Publication::class.java,
                ),
                entitySchemas = listOf(
                    PublisherSchema,
                    AuthorSchema,
                    PublicationSchema,
                ),
                fieldsetAggregates = listOf(
                    PublisherFieldsets,
                    AuthorFieldsets,
                    PublicationFieldsets,
                ),
                createCommandTypes = emptyList(),
                updateCommandTypes = emptyList(),
                genericCommandTypes = emptyList(),
            )
        )

        println("//>> ${source.filename}:")
        println(source.sourceCode)
        println("//---")

        //TODO: Assertions
    }
}
