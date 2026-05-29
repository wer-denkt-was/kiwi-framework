package de.kiwi.framework.application.olap

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.application.service.EntityTypeProvider
import de.kiwi.api.domain.AccessControl
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.olap.Slice
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.framework.infrastructure.generic.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.core.convert.support.DefaultConversionService
import java.time.LocalDate
import java.util.*

@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///springboot",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.format_sql=true",
    ]
)
class AnalyticalReportQueryHandlerTest {

    @SpringBootApplication(exclude = [KiwiFrameworkConfiguration::class])
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    class TestApplicationConfig {
        @Bean
        fun analyticalReportQueryHandler(entityTypeProvider: EntityTypeProvider) =
            AnalyticalReportQueryHandler(DefaultConversionService(), entityTypeProvider)

        @Bean
        @ConditionalOnMissingBean
        fun objectMapper() = jacksonObjectMapper()
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var handler: AnalyticalReportQueryHandler

    private lateinit var publisher1: Publisher
    private lateinit var publisher2: Publisher
    private lateinit var publisher3: Publisher

    @BeforeEach
    fun setUp(info: TestInfo) {
        println("----- SETUP TEST [${info.displayName}] -----")

        publisher1 = entityManager.merge(
            Publisher(
                id = EntityId.generate<PublisherId>(),
                name = "First Publisher",
                contact = Contact(country = "Germany"),
            )
        )
        publisher2 = entityManager.merge(
            Publisher(
                id = EntityId.generate<PublisherId>(),
                name = "Second Publisher",
                contact = Contact(country = "Germany"),
            )
        )
        publisher3 = entityManager.merge(
            Publisher(
                id = EntityId.generate<PublisherId>(),
                name = "Third Publisher",
                contact = Contact(country = "USA"),
            )
        )

        listOf(
            Triple(publisher1, "1996-01-01", BookType.Hardcover),
            Triple(publisher1, "1997-01-01", BookType.Softcover),
            Triple(publisher2, "1997-01-01", BookType.Paperback),
            Triple(publisher2, "1998-01-01", BookType.Paperback),
            Triple(publisher3, "1998-01-01", BookType.Hardcover),
            Triple(publisher3, "1999-01-01", BookType.EBook),
        ).forEach { (publisher, publishedOn, bookType) ->
            entityManager.persist(
                Book(
                    id = EntityId.generate<PublicationId>(),
                    publisher = publisher,
                    title = UUID.randomUUID().toString(),
                    description = null,
                    firstPublishedOn = LocalDate.parse(publishedOn),
                    isbn = UUID.randomUUID().toString(),
                    bookType = bookType,
                    mainAuthor = null,
                )
            )
        }

        listOf(
            Triple(publisher1, "1998-01-01", PublishCycle.Monthly),
            Triple(publisher1, "1999-01-01", PublishCycle.Daily),
            Triple(publisher2, "2000-01-01", PublishCycle.Weekly),
            Triple(publisher3, "2000-01-01", PublishCycle.Yearly),
            Triple(publisher3, "2001-01-01", PublishCycle.Monthly),
        ).forEach { (publisher, publishedOn, cycle) ->
            entityManager.persist(
                Magazine(
                    id = EntityId.generate<PublicationId>(),
                    publisher = publisher,
                    title = UUID.randomUUID().toString(),
                    description = null,
                    firstPublishedOn = LocalDate.parse(publishedOn),
                    lastPublishedOn = null,
                    publishCycle = cycle,
                )
            )
        }

        entityManager.flush()
        entityManager.clear()

        println("----- BEGIN TEST [${info.displayName}] -----")
    }

    @AfterEach
    fun tearDown(info: TestInfo) {
        println("----- TEARDOWN TEST [${info.displayName}] -----")

        deleteAll<Publication>()
        deleteAll<Publisher>()
        entityManager.flush()
        entityManager.clear()

        println("----- END TEST [${info.displayName}] -----")
    }

    @Test
    fun handle() {
        val query = PublicationReportQuery().apply {
            //dimensions.add("firstPublishedYear")
            //dimensions.add("publisherName")
            dimensions.add("publisherCountry")
            dimensions.add("bookType")
            metrics.add("total")
            metrics.add("books")
            metrics.add("magazines")
            //slices["firstPublishedYear"] = Slice(`in` = setOf("1999", "2000"))
            slices["publisherCountry"] = Slice(`in` = setOf("Germany", "USA"))
        }

        val report = handler.handle(query, UserDescriptor.SYSTEM)
        println("[*]> ${report.headers}")
        report.body.forEachIndexed { i, row -> println("[$i]> $row") }

        //TODO: Assert integrity of report
    }

    @Test
    fun handleWithAccessControl() {
        val query = PublicationReportQueryWithAccessControl().apply {
            // Only allow access to publishers 1 and 3, **not** to publisher 2
            onlyAllowPublisherIds.add(publisher1.id)
            onlyAllowPublisherIds.add(publisher3.id)

            dimensions.add("publisherId")
            dimensions.add("publisherName")
            metrics.add("total")
        }

        val report = handler.handle(query, UserDescriptor.SYSTEM)
        println("[*]> ${report.headers}")
        report.body.forEachIndexed { i, row -> println("[$i]> $row") }

        // Assert that publisher 2 does not appear in reports, due to lack of allowed access
        val publisherIdIndex = report.headers.indexOf("publisherId")
        val reportedPublisherIds = report.body.map { row ->
            val idString = row[publisherIdIndex].toString()
            EntityId.fromString<PublisherId>(idString)
        }
        assertThat(reportedPublisherIds, containsInAnyOrder(publisher1.id, publisher3.id))
        assertThat(reportedPublisherIds, not(hasItem(publisher2.id))) // No access!
    }

    private inline fun <reified E : Any> deleteAll() {
        val b = entityManager.criteriaBuilder
        val d = b.createCriteriaDelete(E::class.java)
        d.from(E::class.java)
        d.where(b.conjunction())
        entityManager.createQuery(d).executeUpdate()
    }

    private class PublicationReportQueryWithAccessControl : PublicationReportQuery() {
        val onlyAllowPublisherIds = HashSet<PublisherId>()

        override val accessControl = AccessControl.where { publication, _ ->
            // For testing purposes, this access control checks that access to the publisher ID is allowed
            val publisherId = publication[Publication_.publisher][Publisher_.id]
            publisherId.`in`(onlyAllowPublisherIds)
        }
    }
}
