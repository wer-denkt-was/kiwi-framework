package de.kiwi.framework.infrastructure.schema

import de.kiwi.api.application.service.QueryBuilder
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.framework.infrastructure.generic.PublicationFieldsets
import de.kiwi.framework.infrastructure.generic.PublicationFilter
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.support.ConversionServiceFactoryBean
import org.springframework.data.domain.Pageable
import java.time.LocalDate

@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///springboot",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.format_sql=true",
    ],
)
class SchemaQueryBuilderTest {

    @SpringBootApplication(exclude = [KiwiFrameworkConfiguration::class])
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    @Import(JacksonAutoConfiguration::class)
    class TestApplicationConfig {
        @Bean
        @ConditionalOnMissingBean
        fun conversionService() = ConversionServiceFactoryBean()

        @Bean
        fun queryBuilder(schemaService: QuerySchemaService): QueryBuilder =
            SchemaQueryBuilder(schemaService)
    }

    @Autowired(required = false)
    private lateinit var queryBuilder: SchemaQueryBuilder

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private lateinit var data: GenericExampleData

    @BeforeEach
    fun setUp() {
        data = GenericExampleData(entityManager)
    }

    @AfterEach
    fun tearDown() {
        data.clear(entityManager)
    }

    @Test
    fun select() {
        val result = queryBuilder
            .select(PublicationFieldsets.default)
            .where(PublicationFilter().apply {
                publishedAfter = LocalDate.parse("1972-01-01")
            })
            .page(Pageable.ofSize(10))

        println(result.toList())
        assertEquals(5, result.totalElements)
    }
}
