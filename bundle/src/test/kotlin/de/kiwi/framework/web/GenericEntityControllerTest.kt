package de.kiwi.framework.web

import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.domain.*
import de.kiwi.framework.application.generic.GenericMetadataProvider
import de.kiwi.framework.infrastructure.bus.CommandQueryEventBusConfiguration
import de.kiwi.framework.infrastructure.generic.*
import de.kiwi.framework.infrastructure.schema.FieldsetValidationConfiguration
import de.kiwi.framework.infrastructure.security.SpringUserDescriptorProvider
import de.kiwi.framework.infrastructure.security.UserDescriptorArgumentResolver
import de.kiwi.framework.infrastructure.spring.SpringTransactionService
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
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
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.format.FormatterRegistry
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.EnableWebMvc
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.LocalDate

@WebMvcTest(
    excludeAutoConfiguration = [SecurityAutoConfiguration::class, SecurityFilterAutoConfiguration::class],
    properties = [
        "spring.test.database.replace=none",
        "spring.datasource.url=jdbc:tc:mariadb:latest:///genericController",
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.format_sql=true",
        "spring.jpa.open-in-view=false",
    ],
)
@Transactional
@AutoConfigureDataJpa
@AutoConfigureTestEntityManager
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GenericEntityControllerTest {

    @SpringBootConfiguration
    @EnableWebMvc
    @EnableJpaRepositories
    @ComponentScan("de.kiwi.framework.infrastructure.generic")
    @EntityScan("de.kiwi.api", "de.kiwi.framework.infrastructure.generic")
    @EnableAutoConfiguration(exclude = [SecurityAutoConfiguration::class, SecurityFilterAutoConfiguration::class])
    @Import(
        KiwiWebConfiguration::class,
        GenericHandlerConfiguration::class,
        CommandQueryEventBusConfiguration::class,
        FieldsetValidationConfiguration::class,
        // Specific component classes
        GenericEntityMethodHandler::class,
        KiwiRouterFunctionConfig::class,
        KiwiRouteHelper::class,
        GenericMetadataProvider::class,
    )
    class TestApplicationConfig : WebMvcConfigurer {
        @Bean
        fun userDescriptorProvider() = SpringUserDescriptorProvider(emptyList())

        @Bean
        fun transactionService(manager: PlatformTransactionManager) = SpringTransactionService(manager)

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

    @Autowired
    private lateinit var jsonMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        deleteAll<Publication>()
        deleteAll<Author>()
        deleteAll<Publisher>()
        entityManager.flush()
    }

    @Test
    fun createEntity() {
        // Submit create
        val result = mvc.post("/publisher") {
            //language=JSON
            content = """{ "name": "New Publisher", "contact": { "country": "Germany" } }"""
            contentType = MediaType.APPLICATION_JSON
        }.andDo {
            print()
        }.andExpect {
            status { isCreated() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                content {
                    json(
                        jsonContent = """
                            {"name": "New Publisher", "contact": {"country": "Germany"}}
                        """,
                        compareMode = JsonCompareMode.LENIENT,
                    )
                }
            }
        }.andReturn()

        // Assert created entity exists
        val contentJson = jsonMapper.readTree(result.response.contentAsByteArray)
        val createdIdValue = contentJson["id"].asText()
        val createdEntity = findEntity<Publisher>(EntityId.fromString<PublisherId>(createdIdValue))
        assertNotNull(createdEntity)
        assertEquals("New Publisher", createdEntity?.name)
        assertEquals("Germany", createdEntity?.contact?.country)
    }

    @Test
    fun createEntityValidationFailure() {
        // Submit create with invalid field

        mvc.post("/publisher") {
            // Name must not be blank, so this should fail
            // language=json
            content = """{ "name": "", "contact": { "country": "German" } }"""
            contentType = MediaType.APPLICATION_JSON
        }.andDo { print() }.andExpect {
            status { isBadRequest() }
            content {
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
                      "message": "entity-field-invalid",
                      "arguments": {
                        "fieldName": "name",
                        "reason": "not-blank",
                        "entityType": "de.kiwi.framework.infrastructure.generic.Publisher"
                      }
                    }""",
                )
            }
        }

    }

    @Test
    fun updateEntity() {
        // Prepare existing entity
        val publisherId = newId<PublisherId>()
        entityManager.persist(
            Publisher(
                id = publisherId,
                name = "Existing Publisher",
                contact = Contact(country = "Prussia")
            )
        )
        entityManager.flush()

        // Submit update
        val result = mvc.put("/publisher/$publisherId") {
            //language=JSON
            content = """{ "contact": { "country": "Germany" } }"""
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                content {
                    json(
                        jsonContent = """
                            {
                              "name": "Existing Publisher",
                              "contact": {"country": "Germany"}
                            }
                        """,
                        compareMode = JsonCompareMode.LENIENT,
                    )
                }
            }
        }.andDo {
            print()
        }.andReturn()

        // Assert ID equality
        val contentJson = jsonMapper.readTree(result.response.contentAsByteArray)
        val updatedIdValue = contentJson["id"].asText()
        assertEquals(publisherId.toString(), updatedIdValue)
    }

    @Test
    fun updateEntityValidationFailure() {
        // Submit update with invalid field
        val publisherId = newId<PublisherId>()
        entityManager.persist(
            Publisher(
                id = publisherId,
                name = "Dummy Publisher",
                contact = Contact(country = "Dummyland")
            )
        )

        mvc.put("/publisher/$publisherId") {
            // Name must not be blank, so this should fail
            //language=JSON
            content = """{ "name": "" }"""
            contentType = MediaType.APPLICATION_JSON
        }.andDo { print() }.andExpect {
            status { isBadRequest() }
            content {
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
                          "message": "entity-field-invalid",
                          "arguments": {
                            "fieldName": "name",
                            "reason": "not-blank",
                            "entityType": "de.kiwi.framework.infrastructure.generic.Publisher"
                          }
                        }"""
                )
            }
        }
    }

    @Test
    fun deleteEntity() {
        // Prepare existing entity
        val publisherId = newId<PublisherId>()
        entityManager.persist(
            Publisher(
                id = publisherId,
                name = "Existing Publisher",
                contact = Contact(country = "USA"),
            )
        )
        entityManager.flush()

        // Submit update
        mvc.delete("/publisher/$publisherId").andExpect {
            status { isOk() }
        }.andDo {
            print()
        }

        // Assert entity does not exist anymore
        val deletedEntity = findEntity<Publisher>(publisherId)
        assertNull(deletedEntity)
    }

    @Test
    fun singleEntity() {
        // Prepare existing entity
        val publisherId = newId<PublisherId>()
        entityManager.persist(
            Publisher(id = publisherId, name = "Example Publisher", contact = Contact(country = "Germany"))
        )
        entityManager.flush()

        // Get entity by ID
        mvc.get("/publisher/$publisherId") {
            queryParam("_fieldset", "default")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    jsonContent = """
                        {
                          "name": "Example Publisher",
                          "contact": {"country": "Germany"}
                        }
                    """,
                    compareMode = JsonCompareMode.LENIENT,
                )
            }
        }
    }

    @Test
    fun singleEntityNamedFieldset() {
        // Prepare existing entity
        val publisherId = newId<PublisherId>()
        val publisher = entityManager.merge(
            Publisher(id = publisherId, name = "Example Publisher", contact = Contact(country = "Germany"))
        )
        val author = entityManager.merge(
            Author(
                id = newId(),
                email = "test@localhost",
                fullName = "Test Author",
                contact = Contact()
            )
        )
        entityManager.persist(
            Book(
                id = newId(),
                publisher = publisher,
                title = "First Book",
                description = null,
                firstPublishedOn = null,
                isbn = "9783527709861",
                bookType = BookType.Softcover,
                mainAuthor = null,
            ).also {
                author.addPublication(it)
            }
        )
        entityManager.persist(
            Book(
                id = newId(),
                publisher = publisher,
                title = "Second Book",
                description = null,
                firstPublishedOn = null,
                isbn = "9783866801929",
                bookType = BookType.Other,
                mainAuthor = null,
            ).also {
                author.addPublication(it)
            }
        )
        entityManager.flush()

        // Get entity by ID
        mvc.get("/publisher/$publisherId") {
            queryParam("_fieldset", "full")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                content {
                    json(
                        jsonContent = """
                            {
                              "name": "Example Publisher",
                              "contact": {"country": "Germany"},
                              "publications": [
                                {
                                  "type": "Book",
                                  "title": "First Book",
                                  "book": { "isbn":"9783527709861" }
                                },
                                {
                                  "type": "Book",
                                  "title": "Second Book",
                                  "book": { "isbn":"9783866801929" }
                                }
                              ]
                            }
                        """,
                        compareMode = JsonCompareMode.LENIENT,
                    )
                }
            }
        }
    }

    @Test
    fun singleEntityDynamicFieldset() {
        // Prepare existing entity
        val publisherId = newId<PublisherId>()
        entityManager.persist(
            Publisher(id = publisherId, name = "Example Publisher", contact = Contact(country = "Germany"))
        )
        entityManager.flush()

        // Get entity by ID
        mvc.get("/publisher/$publisherId") {
            queryParam("_fields", "name,publications:default")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    jsonContent = """
                        {
                          "name": "Example Publisher",
                          "publications": []
                        }
                    """,
                    compareMode = JsonCompareMode.LENIENT,
                )
            }
        }
    }

    @Test
    fun singleEntityNotFound() {
        // No existing entity!
        val missingPublisherId = newId<PublisherId>()

        // Get entity by ID
        mvc.get("/publisher/$missingPublisherId") {
            queryParam("_fieldset", "default")
        }.andDo {
            print()
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun listEntities() {
        // Prepare existing entities
        val pid1 = newId<PublisherId>()
        val pid2 = newId<PublisherId>()
        val pid3 = newId<PublisherId>()
        entityManager.persist(
            Publisher(id = pid1, name = "First Publisher", contact = Contact(country = "Germany"))
        )
        entityManager.persist(
            Publisher(id = pid2, name = "Second Publisher", contact = Contact(country = "USA"))
        )
        entityManager.persist(
            Publisher(id = pid3, name = "Third Publisher", contact = Contact(country = "France"))
        )
        entityManager.flush()

        // List entities
        mvc.get("/publisher") {
            queryParam("_fieldset", "default")
            queryParam("_size", "10")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    jsonContent = """
                        {
                          "pageSize": 10,
                          "pageNumber": 0,
                          "totalPages": 1,
                          "totalItems": 3,
                          "items": [
                            { "name": "First Publisher", "contact": {"country": "Germany"} },
                            { "name": "Second Publisher", "contact": {"country": "USA"} },
                            { "name": "Third Publisher", "contact": {"country": "France"} }
                          ]
                        }
                    """,
                    compareMode = JsonCompareMode.LENIENT,
                )
            }
        }
    }

    @Test
    fun listEntitiesWithAccelerate() {
        // Prepare existing entities
        val publisher1 =
            Publisher(newId(), "First Publisher", Contact(country = "Germany"))
                .also(entityManager::persist)
        val publisher2 =
            Publisher(newId(), "Second Publisher", Contact(country = "USA"))
                .also(entityManager::persist)

        val author1 =
            Author(newId(), "first@author", "First Author", Contact())
                .also(entityManager::persist)
        val author2 =
            Author(newId(), "second@author", "Second Author", Contact())
                .also(entityManager::persist)

        Book(newId(), publisher1, "First Book", null, null, "aaa", BookType.Other, null)
            .also { author1.addPublication(it) }
            .also { author2.addPublication(it) }
            .also(entityManager::persist)
        Book(newId(), publisher2, "Second Book", null, null, "bbb", BookType.Other, null)
            .also { author2.addPublication(it) }
            .also(entityManager::persist)
        Magazine(newId(), publisher1, "First Magazine", null, null, null, PublishCycle.Other)
            .also { author1.addPublication(it) }
            .also(entityManager::persist)

        entityManager.flush()

        // List entities
        mvc.get("/author") {
            queryParam("_fieldset", "withPublications")
            queryParam("_accelerate", "true")
            queryParam("_size", "10")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
  "content": {
    "items": [
      {
        "email": "first@author",
        "fullName": "First Author",
        "publicationCount": 2,
        "publications": [
          { "@": 2 },
          { "@": 3 }
        ]
      },
      {
        "email": "second@author",
        "fullName": "Second Author",
        "publicationCount": 2,
        "publications": [
          { "@": 2 },
          { "@": 6 }
        ]
      }
    ],
    "pageSize": 10,
    "pageNumber": 0,
    "totalPages": 1,
    "totalItems": 2
  },
  "references": [
    {
      "isbn": "aaa",
      "bookType": "Other",
      "mainAuthorId": null
    },
    {
      "name": "First Publisher"
    },
    {
      "type": "Book",
      "discriminator": "book",
      "publisherName": "First Publisher",
      "title": "First Book",
      "description": null,
      "shortDescription": null,
      "firstPublishedOn": null,
      "book": { "@": 0 },
      "publisher": { "@": 1 }
    },
    {
      "type": "Magazine",
      "discriminator": "magazine",
      "publisherName": "First Publisher",
      "title": "First Magazine",
      "description": null,
      "shortDescription": null,
      "firstPublishedOn": null,
      "book": null,
      "publisher": { "@": 1 }
    },
    {
      "isbn": "bbb",
      "bookType": "Other",
      "mainAuthorId": null
    },
    {
      "name": "Second Publisher"
    },
    {
      "type": "Book",
      "discriminator": "book",
      "publisherName": "Second Publisher",
      "title": "Second Book",
      "description": null,
      "shortDescription": null,
      "firstPublishedOn": null,
      "book": { "@": 4 },
      "publisher": { "@": 5 }
    }
  ],
  "accelerated": true
}"""
                )
            }
        }
    }

    @Test
    fun listEntitiesWithColumnFilterOperator() {
        // Prepare existing entities
        val pid1 = newId<PublisherId>()
        val pid2 = newId<PublisherId>()
        val pid3 = newId<PublisherId>()
        entityManager.persist(Publisher(id = pid1, name = "Fizz Publisher", contact = Contact(country = "Germany")))
        entityManager.persist(Publisher(id = pid2, name = "Buzz Publisher", contact = Contact(country = "USA")))
        entityManager.persist(Publisher(id = pid3, name = "FizzBuzz Publisher", contact = Contact(country = "France")))
        entityManager.flush()

        // List entities, passing a dynamic query column filter
        mvc.get("/publisher") {
            queryParam("_fieldset", "default")
            queryParam("_size", "10")
            queryParam("name:contains", "Buzz") // This will query `WHERE name LIKE '%Buzz%'`
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    jsonContent = """
                        {
                          "pageSize": 10,
                          "pageNumber": 0,
                          "totalPages": 1,
                          "totalItems": 2,
                          "items": [
                            { "name": "Buzz Publisher" },
                            { "name": "FizzBuzz Publisher" }
                          ]
                        }
                    """.trimIndent(),
                    compareMode = JsonCompareMode.LENIENT,
                )
            }
        }
    }

    @Test
    fun listEntitiesWithConvertedType() {
        val publisher = entityManager.merge(Publisher(id = newId<PublisherId>(), name = "test", contact = Contact()))
        val book1 = entityManager.merge(
            Book(
                id = newId<PublicationId>(),
                publisher = publisher,
                title = "1",
                description = null,
                firstPublishedOn = LocalDate.parse("2025-01-01"),
                isbn = "...1",
                bookType = BookType.EBook,
                mainAuthor = null,
            )
        )
        val book2 = entityManager.merge(
            Book(
                id = newId<PublicationId>(),
                publisher = publisher,
                title = "2",
                description = null,
                firstPublishedOn = LocalDate.parse("2025-02-02"),
                isbn = "...2",
                bookType = BookType.EBook,
                mainAuthor = null,
            )
        )
        entityManager.flush()

        mvc.get("/publication") {
            queryParam("firstPublishedOn:lt", "2025-01-31")
            queryParam("_fieldset", "default")
            queryParam("_size", "10")
        }.andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
                        "pageNumber": 0,
                        "totalItems": 1,
                        "items": [
                          {"id": "${book1.id}", "title": "1"}
                        ]
                    }"""
                )
            }
        }
    }

    @Test
    fun listEntitiesInvalidColumnFilterOperator() {
        mvc.get("/publisher") {
            queryParam("_fieldset", "default")
            queryParam("_size", "10")
            queryParam("name:INVALID_OPERATOR", "This should fail")
        }.andDo {
            print()
        }.andExpect {
            status { isBadRequest() }
            content {
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
                        "message": "unknown-column-filter-operator",
                        "arguments": {"columnName": "name", "operator": "INVALID_OPERATOR"}
                    }""".trimMargin()
                )
            }
        }
    }

    @Test
    fun listEntitiesInvalidColumnFilterAmbiguousValues() {
        mvc.get("/publisher") {
            queryParam("_fieldset", "default")
            queryParam("_size", "10")
            queryParam("name:contains", "This should have only a single value")
            queryParam("name:contains", "Two values will make it fail!")
        }.andDo {
            print()
        }.andExpect {
            status { isBadRequest() }
            content {
                json(
                    compareMode = JsonCompareMode.LENIENT, jsonContent = """{
                    "message": "operator-value-ambiguous",
                    "arguments": {"operator": "contains", "valueCount": 2}
                }"""
                )
            }
        }
    }

    @Test
    fun entitySchemaPublisher() {
        mvc.get("/publisher/_schema").andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = """{
  "entityName": "Publisher",
  "entityClassName": "de.kiwi.framework.infrastructure.generic.Publisher",
  "create": {
    "commandClassName": "de.kiwi.framework.infrastructure.generic.CreatePublisher",
    "properties": {}
  },
  "update": {
    "commandClassName": "de.kiwi.framework.infrastructure.generic.UpdatePublisher",
    "idClassName": "de.kiwi.framework.infrastructure.generic.PublisherId",
    "properties": {}
  },
  "delete": {
    "commandClassName": "de.kiwi.framework.infrastructure.generic.DeletePublisher",
    "idClassName": "de.kiwi.framework.infrastructure.generic.PublisherId"
  },
  "query": {
    "queryClassName": "de.kiwi.framework.infrastructure.generic.PublisherQuery",
    "filterProperties": {
      "nameLike": { }
    }
  },
  "schema": {
    "schemaClassName": "de.kiwi.framework.infrastructure.generic.PublisherSchema",
    "fieldsets": [ "default", "extreme", "full", "short", "veryShort" ],
    "columnProperties": {
      "id": { },
      "name": { },
      "contact": { }
    },
    "toOneRelations": {},
    "toManyRelations": {
      "publications": { "entityName": "Publication", "entityClassName": "de.kiwi.framework.infrastructure.generic.Publication" }
    },
    "toManyMapRelations": {}
  }
}""",
                )
            }
        }
    }

    @Test
    fun entitySchemaPublication() {
        mvc.get("/publication/_schema").andDo {
            print()
        }.andExpect {
            status { isOk() }
            content {
                contentType(MediaType.APPLICATION_JSON)
                json(
                    compareMode = JsonCompareMode.LENIENT,
                    jsonContent = $$"""
{
  "entityName": "Publication",
  "entityClassName": "de.kiwi.framework.infrastructure.generic.Publication",
  "query": {
    "queryClassName": "de.kiwi.framework.infrastructure.generic.PublicationQuery",
    "filterProperties": {
      "titleLike": { },
      "published": { },
      "publishedAfter": {  }
    }
  },
  "schema": {
    "schemaClassName": "de.kiwi.framework.infrastructure.generic.PublicationSchema",
    "fieldsets": [ "default", "extended", "full", "short", "veryInefficientDontDoThis" ],
    "columnProperties": {
      "id": { },
      "type": { },
      "discriminator": { },
      "publisherId": { },
      "publisherName": { },
      "title": { },
      "description": { },
      "shortDescription": { },
      "firstPublishedOn": { }
    },
    "toOneRelations": {
      "publisher": { "entityName": "Publisher", "entityClassName": "de.kiwi.framework.infrastructure.generic.Publisher" }
    },
    "toManyRelations": {
      "authors": { "entityName": "Author", "entityClassName": "de.kiwi.framework.infrastructure.generic.Author" }
    },
    "toManyMapRelations": {},
    "subtypeProperties": {
      "book": {
        "schemaClassName": "de.kiwi.framework.infrastructure.generic.PublicationSchema$BookSubtype",
        "fieldsets": [ "default" ],
        "columnProperties": {
          "isbn": { },
          "bookType": { },
          "mainAuthorId": { }
        },
        "toOneRelations": {
          "mainAuthor": { "entityName": "Author", "entityClassName": "de.kiwi.framework.infrastructure.generic.Author" }
        },
        "toManyRelations": {},
        "toManyMapRelations": {},
        "subtypeProperties": {}
      },
      "magazine": {
        "schemaClassName": "de.kiwi.framework.infrastructure.generic.PublicationSchema$MagazineSubtype",
        "fieldsets": [ "default" ],
        "columnProperties": {
          "lastPublishedOn": { },
          "publishCycle": { }
        },
        "toOneRelations": {},
        "toManyRelations": {},
        "toManyMapRelations": {},
        "subtypeProperties": {}
      }
    }
  },
  "create": null,
  "update": null,
  "delete": null
}
""",
                )
            }
        }
    }

    // --- Internal utilities ---

    private inline fun <reified ID : EntityId> newId() = EntityId.generate<ID>()

    private inline fun <reified E : Any> findEntity(entityId: EntityId): E? {
        return entityManager.find(E::class.java, entityId)
    }

    private inline fun <reified E : Any> deleteAll() {
        with(entityManager.criteriaBuilder) {
            val delete = createCriteriaDelete(E::class.java).apply { from(E::class.java) }
            entityManager.createQuery(delete).executeUpdate()
        }
    }
}
