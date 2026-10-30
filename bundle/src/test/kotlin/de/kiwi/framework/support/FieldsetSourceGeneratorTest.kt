package de.kiwi.framework.support

import de.kiwi.framework.SpringBootApplicationWithoutKiwi
import de.kiwi.framework.infrastructure.generic.*
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.LocalDateTime

/**
 * Create PersistenceContext to initialize JPA metamodel
 */
@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class FieldsetSourceGeneratorTest {

    @SpringBootApplicationWithoutKiwi
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
            renderer = renderer,
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
                genericCreateTypes = emptyList(),
                genericUpdateTypes = emptyList(),
            ),
        )

        println("//>> ${source.filename}:")
        println(source.sourceCode)
        println("//---")

        //TODO: Assertions
    }

    @Test
    fun generateGenericCommandSource() {
        val renderer = TypescriptFieldsetSourceRenderer(TypescriptOptions())
        val generator = FieldsetSourceGenerator(
            moduleName = "GenericTestSchema",
            fileHeader = listOf("--- Generic Commands ---"),
            renderer = renderer,
        )

        val source = generator.generateSource(
            FieldsetSources(
                entityClasses = listOf(Publisher::class.java),
                entitySchemas = listOf(PublisherSchema),
                fieldsetAggregates = listOf(PublisherFieldsets),
                createCommandTypes = emptyList(),
                updateCommandTypes = emptyList(),
                genericCreateTypes = listOf(CreatePublisher::class.java),
                genericUpdateTypes = listOf(UpdatePublisher::class.java),
            ),
        )

        println(source.sourceCode)

        // Create commands require non-null body properties, update commands are patches with optional properties
        val create = Regex("""interface ICreatePublisher \{\s*name: string;\s*contact: \{""")
        val update = Regex("""interface IUpdatePublisher \{\s*name\?: string;\s*contact\?: \{""")
        assertTrue(create.containsMatchIn(source.sourceCode), "ICreatePublisher")
        assertTrue(update.containsMatchIn(source.sourceCode), "IUpdatePublisher")
    }

    @Test
    fun generateSourceMutable() {
        val options = TypescriptOptions(readOnlyFields = false, readOnlyArrays = false)
        val renderer = TypescriptFieldsetSourceRenderer(options)
        val generator = FieldsetSourceGenerator(
            moduleName = "GenericTestSchema",
            fileHeader = listOf("--- Mutable Test Schema ---", LocalDateTime.now().toString()),
            renderer = renderer,
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
                genericCreateTypes = emptyList(),
                genericUpdateTypes = emptyList(),
            ),
        )

        println("//>> ${source.filename}:")
        println(source.sourceCode)
        println("//---")

        //TODO: Assertions
    }
}
