package de.kiwi.framework.infrastructure.schema

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.BaseEntity_
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.PageResult
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.KiwiFrameworkConfiguration
import de.kiwi.framework.infrastructure.generic.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.function.Executable
import org.skyscreamer.jsonassert.JSONAssert
import org.skyscreamer.jsonassert.JSONCompareMode
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
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.query.QueryUtils
import kotlin.reflect.KClass

@DataJpaTest(
    showSql = true,
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///springboot",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.format_sql=false",
    ],
)
class JpaQuerySchemaServiceTest {

    @SpringBootApplication(exclude = [KiwiFrameworkConfiguration::class])
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    @Import(JacksonAutoConfiguration::class)
    class TestApplicationConfig {
        @Bean
        @ConditionalOnMissingBean
        fun conversionService() = ConversionServiceFactoryBean()
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var querySchemaService: QuerySchemaService

    private lateinit var data: GenericExampleData

    companion object {
        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            UserDescriptorProvider.provideInstance { UserDescriptor.SYSTEM }
        }
    }

    @BeforeEach
    fun setUp(info: TestInfo) {
        println("----- SETUP TEST [${info.displayName}] -----")
        data = GenericExampleData(entityManager)
        println("----- BEGIN TEST [${info.displayName}] -----")
    }

    @AfterEach
    fun tearDown(info: TestInfo) {
        println("----- TEARDOWN TEST [${info.displayName}] -----")
        data.clear(entityManager)
        println("----- END TEST [${info.displayName}] -----")
    }

    @Test
    fun publisherFindOne() {
        assertAll(PublisherFieldsets.allFieldsets.map { fieldset ->
            Executable {
                val filter = Specification<Publisher> { root, _, builder ->
                    builder.equal(root[Publisher_.id], data.publisher1.id)
                }

                val actualResult = querySchemaService.findOne(fieldset, filter)

                val expectedEntity = findEntities(Publisher::class, filter).first()
                val expectedResult = fieldset.extract(expectedEntity)

                println("FIELDS >>> ${fieldset.name}")
                println("QUERY  >>> ${toPrettyJson(actualResult)}")
                println("ENTITY >>> ${toPrettyJson(expectedResult)}")

                assertJsonCompatible(expectedResult, actualResult)
            }
        })
    }

    @Test
    fun authorFindOne() {
        assertAll(AuthorFieldsets.allFieldsets.flatMap { fieldset ->
            listOf(data.author1, data.author2).map { author ->
                Executable {
                    val filter = Specification<Author> { root, _, builder ->
                        builder.equal(root[Author_.id], author.id)
                    }

                    val actualResult = querySchemaService.findOne(fieldset, filter)

                    val expectedEntity = findEntities(Author::class, filter).first()
                    val expectedResult = fieldset.extract(expectedEntity)

                    println("AUTHOR >>> ${author.id} <${author.email}>")
                    println("FIELDS >>> ${fieldset.name}")
                    println("QUERY  >>> ${toPrettyJson(actualResult)}")
                    println("ENTITY >>> ${toPrettyJson(expectedResult)}")

                    assertJsonCompatible(expectedResult, actualResult)
                }
            }
        })
    }

    @Test
    fun publicationFindOne() {
        assertAll(PublicationFieldsets.allFieldsets.flatMap { fieldset ->
            listOf(data.book1, data.book2, data.magazine1).map { publication ->
                Executable {
                    val filter = Specification<Publication> { root, _, builder ->
                        builder.equal(root[Publication_.id], publication.id)
                    }

                    val actualResult = querySchemaService.findOne(fieldset, filter)

                    val expectedEntity = findEntities(Publication::class, filter).first()
                    val expectedResult = fieldset.extract(expectedEntity)

                    println("PUBL.  >>> ${publication.id} <${publication.title}>")
                    println("FIELDS >>> ${fieldset.name}")
                    println("QUERY  >>> ${toPrettyJson(actualResult)}")
                    println("ENTITY >>> ${toPrettyJson(expectedResult)}")

                    assertJsonCompatible(expectedResult, actualResult)
                }
            }
        })
    }

    @Test
    fun publicationFindAll() {
        assertAll(PublicationFieldsets.allFieldsets.map { fieldset ->
            Executable {
                val filter = Specification.unrestricted<Publication>() // No filter
                val sort = Sort.by(BaseEntity_.ID)

                println("<<< BEGIN QuerySchema")
                val actualResult = querySchemaService.findAll(fieldset, specification = filter, sort = sort)
                println("<<< END QuerySchema")

                println("<<< BEGIN Entities")
                val expectedEntities = findEntities(Publication::class, filter = filter, sort = sort)
                val expectedResult = expectedEntities.map { fieldset.extract(it) }
                println("<<< END Entities")

                println("FIELDS           >>> ${fieldset.name}")
                println("ALL PUBL. QUERY  >>> ${toPrettyJson(actualResult)}")
                println("ALL PUBL. ENTITY >>> ${toPrettyJson(expectedResult)}")

                assertJsonCompatible(expectedResult, actualResult)
            }
        })
    }

    @Test
    fun findAll() {
        val result = querySchemaService.findAll(
            fieldset = PublisherFieldsets.extreme,
            specification = Specification.unrestricted(),
        )
        println(">>> ${toPrettyJson(result)}")
    }

    @Test
    fun findPage() {
        val page = querySchemaService.findPage(
            fieldset = PublisherFieldsets.extreme,
            specification = Specification.unrestricted(),
            pagination = Pageable.ofSize(10),
            sort = Sort.by(Publisher_.NAME)
        )
        val result = PageResult.fromPage(page)
        println(">>> ${toPrettyJson(result)}")
    }

    private fun <E : Any> findEntities(
        entityType: KClass<E>,
        filter: Specification<E>,
        sort: Sort = Sort.unsorted(),
    ): List<E> {
        val builder = entityManager.criteriaBuilder
        val query = builder.createQuery(entityType.java)
        val root = query.from(entityType.java)
        query.select(root)
        filter.toPredicate(root, query, builder)?.let { query.where(it) }
        if (sort.isSorted) query.orderBy(QueryUtils.toOrders(sort, root, builder))
        return entityManager.createQuery(query).resultList
    }

    @Test
    fun findWithFilter() {
        val filter = AuthorFilter()
        filter.hasPublication = setOf(data.magazine1.id)

        val result = assertDoesNotThrow {
            querySchemaService.findAll(
                fieldset = AuthorFieldsets.withPublications,
                specification = filter,
            )
        }
        println(">>> ${toPrettyJson(result)}")
        //TODO: Assertions
    }

    @Test
    fun findWithSort() {
        val result = assertDoesNotThrow {
            querySchemaService.findAll(
                fieldset = PublicationFieldsets.short,
                specification = Specification.unrestricted(),
                sort = Sort.by(
                    Sort.Order.desc("book.mainAuthor.fullName"),
                    Sort.Order.desc("title")
                )
            )
        }
        println(">>> ${toPrettyJson(result)}")
        //TODO: Assertions
    }

    @Test
    fun findWithSortInvalid() {
        val ex = assertThrows<DomainException> {
            querySchemaService.findAll(
                fieldset = PublicationFieldsets.short,
                specification = Specification.unrestricted(),
                sort = Sort.by(
                    Sort.Order.asc("unknownProperty"),
                )
            )
        }
        assertEquals("invalid-query-sort", ex.code)
        assertEquals("unknownProperty", ex.args["expression"])
    }
}

private val json = jacksonObjectMapper()
private val jsonWriter = json.writer()
private val prettyJsonWriter = json.writerWithDefaultPrettyPrinter()

private fun toPrettyJson(data: Any?) = toJson(data, pretty = true)

private fun toJson(data: Any?, pretty: Boolean = false): String {
    val writer = if (pretty) prettyJsonWriter else jsonWriter
    return writer.writeValueAsString(data)
}

private fun assertJsonCompatible(expected: Any?, actual: Any?) {
    val expectedJson = toJson(expected)
    val actualJson = toJson(actual)
    val mode = JSONCompareMode.NON_EXTENSIBLE
    JSONAssert.assertEquals(expectedJson, actualJson, mode)
}
