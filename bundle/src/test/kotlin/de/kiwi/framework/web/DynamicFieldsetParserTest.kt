package de.kiwi.framework.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.schema.*
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.framework.infrastructure.generic.AuthorSchema
import de.kiwi.framework.infrastructure.generic.PublicationFieldsets
import de.kiwi.framework.infrastructure.generic.PublicationSchema
import de.kiwi.framework.infrastructure.generic.PublisherFieldsets
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan

@DataJpaTest(properties = ["spring.jpa.hibernate.ddl-auto=none"])
@AutoConfigureTestDatabase(connection = EmbeddedDatabaseConnection.H2)
class DynamicFieldsetParserTest {

    @SpringBootApplication(exclude = [KiwiFrameworkConfiguration::class])
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    class TestApplicationConfig {
        @Bean
        @ConditionalOnMissingBean(ObjectMapper::class)
        fun objectMapper() = jacksonObjectMapper()
    }

    private lateinit var parser: DynamicFieldsetParser

    @BeforeEach
    fun setUp() {
        parser = DynamicFieldsetParser(TestSchemaProvider())
    }

    @Test
    fun parseFieldset() {
        val result = parser.parseFieldset(PublicationFieldsets, "id,title,publisher:short,authors:(fullName)")
        println(result)

        val entityFieldset = assertInstanceOf(EntityFieldset::class.java, result)
        assertThat(
            entityFieldset.columns,
            containsInAnyOrder(
                hasProperty("fieldName", equalTo("id")),
                hasProperty("fieldName", equalTo("title")),
            )
        )
        assertThat(
            entityFieldset.toOne,
            allOf(
                aMapWithSize(1),
                hasEntry(
                    hasProperty("fieldName", equalTo("publisher")),
                    sameInstance(PublisherFieldsets.short)
                )
            )
        )
        assertThat(
            entityFieldset.toMany,
            allOf(
                aMapWithSize(1),
                hasEntry(
                    hasProperty("fieldName", equalTo("authors")),
                    allOf(
                        hasProperty(
                            "columns",
                            containsInAnyOrder<ColumnField<*, *, *>>(
                                hasProperty("fieldName", equalTo("fullName"))
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun parseSingleColumnFieldset() {
        val result = parser.parseFieldset(PublicationFieldsets, "!title")
        println(result)

        val singleColumnFieldset = assertInstanceOf(SingleColumnFieldset::class.java, result)
        assertThat(
            singleColumnFieldset.singleColumn,
            sameInstance(PublicationSchema.title),
        )
    }

    @Test
    fun parseFieldsetWithSingleColumnRef() {
        val result = parser.parseFieldset(PublicationFieldsets, "id,title,authors:!fullName")
        println(result)

        val entityFieldset = assertInstanceOf(EntityFieldset::class.java, result)
        assertThat(
            entityFieldset.columns,
            contains(
                sameInstance(PublicationSchema.id),
                sameInstance(PublicationSchema.title),
            )
        )
        assertThat(
            entityFieldset.toMany,
            allOf(
                aMapWithSize<ToManyField<*, *, *, *>, Fieldset<*>>(1),
                hasEntry(
                    sameInstance(PublicationSchema.authors),
                    allOf(
                        instanceOf(SingleColumnFieldset::class.java),
                        hasProperty("singleColumn", sameInstance(AuthorSchema.fullName))
                    )
                )
            )
        )
    }

    @Test
    fun parseFieldsetInvalidChar() {
        val ex = assertThrows(DynamicFieldsetParser.FieldsetParseException::class.java) {
            parser.parseFieldset(PublicationFieldsets, "hêllö,wörld")
        }
        println(ex.message)
        assertThat(ex.message, containsStringIgnoringCase("ê"))
    }

    @Test
    fun parseFieldsetInvalidSyntax() {
        val ex = assertThrows(DynamicFieldsetParser.FieldsetParseException::class.java) {
            parser.parseFieldset(PublicationFieldsets, "foo and bar, please")
        }
        println(ex.message)
        assertThat(ex.message, containsStringIgnoringCase("and"))
    }
}
