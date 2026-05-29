package de.kiwi.framework.web

import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.EntityIdToNumberConverter
import de.kiwi.api.domain.EntityIdToStringConverter
import de.kiwi.api.domain.NumberToEntityIdConverterFactory
import de.kiwi.api.domain.StringToEntityIdConverterFactory
import de.kiwi.framework.infrastructure.bus.CommandQueryEventBusConfiguration
import de.kiwi.framework.infrastructure.generic.*
import de.kiwi.framework.infrastructure.olap.AnalyticalReportConfiguration
import de.kiwi.framework.infrastructure.security.SpringUserDescriptorProvider
import de.kiwi.framework.infrastructure.security.UserDescriptorArgumentResolver
import de.kiwi.framework.infrastructure.spring.SpringTransactionService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureDataJpa
import org.springframework.boot.test.autoconfigure.orm.jpa.AutoConfigureTestEntityManager
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.format.FormatterRegistry
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.LocalDate
import java.util.*

@WebMvcTest(
    excludeAutoConfiguration = [SecurityAutoConfiguration::class, SecurityFilterAutoConfiguration::class],
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///analyticalReportController",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.format_sql=true",
        "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=true",
    ]
)
@Transactional
@AutoConfigureDataJpa
@AutoConfigureTestEntityManager
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AnalyticalReportControllerTest {

    @SpringBootConfiguration
    @EnableWebMvc
    @EnableJpaRepositories
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    @Import(
        // General framework configs
        KiwiWebConfiguration::class,
        AnalyticalReportConfiguration::class,
        CommandQueryEventBusConfiguration::class,
        // Specific components
        AnalyticalReportMethodHandler::class,
        KiwiRouterFunctionConfig::class,
        KiwiRouteHelper::class,
    )
    class TestApplicationConfig : WebMvcConfigurer {
        @Bean
        fun userDescriptorProvider(): UserDescriptorProvider =
            SpringUserDescriptorProvider(emptyList())

        @Bean
        fun transactionService(manager: PlatformTransactionManager): TransactionService =
            SpringTransactionService(manager)

        override fun addFormatters(registry: FormatterRegistry) {
            registry.addConverterFactory(StringToEntityIdConverterFactory)
            registry.addConverterFactory(NumberToEntityIdConverterFactory)
            registry.addConverter(EntityIdToStringConverter)
            registry.addConverter(EntityIdToNumberConverter)
        }

        override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
            resolvers.add(UserDescriptorArgumentResolver(userDescriptorProvider()))
        }
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setUp() {
        val publisher1 = entityManager.merge(
            Publisher(
                id = EntityId.generate<PublisherId>(),
                name = "First Publisher",
                contact = Contact(country = "Germany")
            )
        )

        val auth1 = entityManager.merge(
            Author(
                id = EntityId.generate<AuthorId>(),
                email = "first@author",
                fullName = "First Author",
                contact = Contact(),
            )
        )
        val auth2 = entityManager.merge(
            Author(
                id = EntityId.generate<AuthorId>(),
                email = "second@author",
                fullName = "Second Author",
                contact = Contact(),
            )
        )
        val auth3 = entityManager.merge(
            Author(
                id = EntityId.generate<AuthorId>(),
                email = "third@author",
                fullName = "Third Author",
                contact = Contact(),
            )
        )

        entityManager.merge(
            Book(
                id = EntityId.generate<PublicationId>(),
                publisher = publisher1,
                title = UUID.randomUUID().toString(),
                description = null,
                firstPublishedOn = LocalDate.parse("2024-01-01"),
                isbn = UUID.randomUUID().toString(),
                bookType = BookType.Hardcover,
                mainAuthor = null,
            )
        ).also {
            auth1.addPublication(it)
        }

        entityManager.merge(
            Book(
                id = EntityId.generate<PublicationId>(),
                publisher = publisher1,
                title = UUID.randomUUID().toString(),
                description = null,
                firstPublishedOn = LocalDate.parse("2024-01-01"),
                isbn = UUID.randomUUID().toString(),
                bookType = BookType.Hardcover,
                mainAuthor = null,
            )
        ).also {
            auth1.addPublication(it)
            auth2.addPublication(it)
        }
        entityManager.merge(
            Magazine(
                id = EntityId.generate<PublicationId>(),
                publisher = publisher1,
                title = UUID.randomUUID().toString(),
                description = null,
                firstPublishedOn = LocalDate.parse("2024-01-01"),
                lastPublishedOn = LocalDate.parse("2024-06-01"),
                publishCycle = PublishCycle.Irregular,
            )
        ).also {
            auth1.addPublication(it)
            auth2.addPublication(it)
            auth3.addPublication(it)
        }

        entityManager.flush()
        entityManager.clear()
    }

    @AfterEach
    fun tearDown() {
        deleteAll<Publication>()
        deleteAll<Author>()
        deleteAll<Publisher>()
    }

    @Test
    fun analyticalReportPublication() {
        mvc.get("/publication/_report") {
            queryParam("_dimensions", "firstPublishedYear", "publisherName")
            queryParam("_metrics", "total", "books", "magazines")
            queryParam("_sort", "firstPublishedDate,desc")
            @Language("JSON") val sliceFirstPublishedYear = """{ "ge": [1990] }"""
            queryParam("firstPublishedYear", sliceFirstPublishedYear)
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    compareMode = JsonCompareMode.STRICT,
                    jsonContent = """
                        {
                          "headers": ["firstPublishedYear", "publisherName", "total", "books", "magazines"],
                          "body": [
                            [2024, "First Publisher", 3, 2, 1]
                          ]
                        }
                    """.trimIndent()
                )
            }
        }
    }

    @Test
    fun analyticalReportPublicationSchema() {
        mvc.get("/publication/_report/_schema").andExpect {
            status { isOk() }
            //TODO Assert content
        }.andDo {
            print()
        }
    }

    @Test
    fun analyticalReportAuthor() {
        mvc.get("/author/_report") {
            queryParam("_dimensions", "country")
            queryParam("_metrics", "total", "authoredPublications", "authoredBooks")
        }.andExpect {
            status { isOk() }
        }.andDo {
            print()
        }
    }

    private inline fun <reified E : Any> deleteAll(): Int {
        val builder = entityManager.criteriaBuilder
        val delete = builder.createCriteriaDelete(E::class.java)
        delete.from(E::class.java)
        delete.where(builder.conjunction())
        return entityManager.createQuery(delete).executeUpdate()
    }
}
