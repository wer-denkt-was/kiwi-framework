package de.kiwi.testing.generic

import de.kiwi.api.domain.*
import de.kiwi.api.generic.GenericBodyFieldSource
import de.kiwi.api.generic.GenericCommandMisconfigured
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.api.schema.JsonDetailsProvider
import de.kiwi.framework.infrastructure.generic.*
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.BeanInstantiationException
import org.springframework.beans.BeanUtils
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.LocalDate

/**
 * Test commands are private nested classes, which are ignored by the classpath scanning of other tests.
 */
class GenericBodyCommandTest {
    private val objectMapper = jacksonMapperBuilder().addModule(EntityIdJsonModule()).build()

    private val publisherId = EntityId.generate<PublisherId>()
    private val authorId = EntityId.generate<AuthorId>()

    private fun json(@Language("JSON") json: String) = objectMapper.readTree(json)

    private class CreatePublication : GenericCreate<Publication>() {
        override val detailsProvider = PublicationFieldsets.default

        val type by subtype(PublicationSchema.discriminator)
        val publisher by reference(PublicationSchema.publisher)
        val authors by references(PublicationSchema.authors)
        val title by schema(PublicationSchema.title)
        val description by schema(PublicationSchema.description)
        val publishedOn by schema(PublicationSchema.firstPublishedOn)
        val isbn by schema(PublicationSchema.BookSubtype.isbn).optional()
        val bookType by schema(PublicationSchema.BookSubtype.bookType).optional()
        val mainAuthor by reference(PublicationSchema.BookSubtype.mainAuthor).optional()
        val notify by property<Boolean>().default(false)
        val tags by property<List<String>>().optional()
        val label by property<String>("custom-label").validate(Validation.notBlank).optional()

        override fun preAuthorize(user: UserDescriptor) {}
    }

    private class UpdatePublication : GenericUpdate<Publication, PublicationId>() {
        override val detailsProvider = PublicationFieldsets.default

        val title by schema(PublicationSchema.title)
        val description by schema(PublicationSchema.description)
        val isbn by schema(PublicationSchema.BookSubtype.isbn)
        val publisherId by schema(PublicationSchema.publisherId)
        val note by property<String?>()
        val notify by property<Boolean>().default(false)

        override fun preAuthorize(user: UserDescriptor) {}
    }

    /** Command with a details provider other than a fieldset, and chained validations */
    private class CreateWithDetailsProvider : GenericCreate<Publication>() {
        override val detailsProvider = JsonDetailsProvider<Publication> { _, _ -> JsonNodeFactory.instance.objectNode() }

        val code by property<String>().validate(Validation.notBlank).validate(Validation.maxLength(5))

        override fun preAuthorize(user: UserDescriptor) {}
    }

    /** Command declaring read-only columns: the primary key, and a column of a `val` property of another entity */
    private class CreateWithReadOnlyColumns : GenericCreate<Publication>() {
        override val detailsProvider = PublicationFieldsets.default
        val id by schema(PublicationSchema.id)
        val publisherId by schema(PublicationSchema.publisherId).optional()
        override fun preAuthorize(user: UserDescriptor) {}
    }

    private class EntityTypeColumnCommand : GenericCreate<Publication>() {
        override val detailsProvider = PublicationFieldsets.default
        val type by schema(PublicationSchema.type)
        override fun preAuthorize(user: UserDescriptor) {}
    }

    private class DuplicateKeyCommand : GenericCreate<Publication>() {
        override val detailsProvider = PublicationFieldsets.default
        val title by schema(PublicationSchema.title)
        val titleAgain by property<String>("title")
        override fun preAuthorize(user: UserDescriptor) {}
    }

    @Test
    fun bodyFieldDescriptors() {
        val fields = CreatePublication().bodyFields.associateBy { it.propertyName }

        assertEquals(
            setOf(
                "type", "publisher", "authors", "title", "description", "publishedOn", "isbn", "bookType",
                "mainAuthor", "notify", "tags", "label",
            ),
            fields.keys,
        )

        val typeField = fields.getValue("type")
        val tagsField = fields.getValue("tags")
        val labelField = fields.getValue("label")
        val notifyField = fields.getValue("notify")
        val publisherField = fields.getValue("publisher")
        val publishedOnField = fields.getValue("publishedOn")
        val authorsField = fields.getValue("authors")
        val mainAuthorField = fields.getValue("mainAuthor")

        // Schema field names are used as JSON keys, property() keys can be given explicitly
        assertEquals("firstPublishedOn", publishedOnField.jsonKey)
        assertEquals("custom-label", labelField.jsonKey)
        assertEquals("discriminator", typeField.jsonKey)
        assertEquals("notify", notifyField.jsonKey)

        val typeFieldSource = assertInstanceOf<GenericBodyFieldSource.Subtype>(typeField.source)
        assertEquals(mapOf("book" to Book::class.java, "magazine" to Magazine::class.java), typeFieldSource.subtypes)

        assertInstanceOf<GenericBodyFieldSource.ToOneReference>(publisherField.source)
        assertEquals(PublisherId::class.java, publisherField.referencedIdType)

        assertInstanceOf<GenericBodyFieldSource.ToOneReference>(mainAuthorField.source)
        assertEquals(AuthorId::class.java, mainAuthorField.referencedIdType)

        assertInstanceOf<GenericBodyFieldSource.ToManyReferences>(authorsField.source)
        assertEquals(AuthorId::class.java, authorsField.referencedIdType)

        assertInstanceOf<GenericBodyFieldSource.Property>(tagsField.source)

        // Required: non-null types without default
        val required = fields.values.filter { it.required }.map { it.propertyName }
        assertEquals(listOf("type", "publisher", "authors", "title"), required)

        // Nullable: nullable types of nullable columns/relations
        val nullable = fields.values.filter { it.nullable }.map { it.propertyName }
        assertEquals(listOf("description", "publishedOn", "mainAuthor", "tags", "label"), nullable)

        // optional() makes the property type nullable, default(...) makes it non-null
        assertTrue(fields.getValue("isbn").propertyType.isMarkedNullable)
        assertTrue(labelField.propertyType.isMarkedNullable)
        assertFalse(notifyField.propertyType.isMarkedNullable)

        // JSON value types
        assertEquals(String::class.java, typeField.valueType)
        assertEquals(String::class.java, publisherField.valueType)
        assertEquals(Array<String>::class.java, authorsField.valueType)
        assertNull(tagsField.referencedIdType)
    }

    @Test
    fun bindCreate() {
        val command = CreatePublication()
        command.bind(
            json(
                """{
                  "discriminator": "book",
                  "publisher": "$publisherId",
                  "authors": ["$authorId"],
                  "title": "The Title",
                  "firstPublishedOn": "2026-10-26",
                  "isbn": "978-3-16-148410-0",
                  "bookType": "Hardcover",
                  "mainAuthor": null,
                  "tags": ["a", "b"]
                }""",
            ),
            objectMapper,
        )

        assertEquals(Book::class.java, command.type)
        assertEquals(publisherId, command.publisher)
        assertEquals(listOf(authorId), command.authors)
        assertEquals("The Title", command.title)
        assertNull(command.description)
        assertEquals(LocalDate.of(2026, 10, 26), command.publishedOn)
        assertEquals("978-3-16-148410-0", command.isbn)
        assertEquals(BookType.Hardcover, command.bookType)
        assertNull(command.mainAuthor)
        assertFalse(command.notify)
        assertEquals(listOf("a", "b"), command.tags)
        assertNull(command.label)
    }

    @Test
    fun bindCreateReportsAllErrors() {
        val command = CreatePublication()
        val exception = assertThrows<EntityBodyInvalid> {
            command.bind(
                json(
                    """{
                      "discriminator": "newspaper",
                      "publisher": "not an id",
                      "authors": "$authorId",
                      "description": { "not": "a string" },
                      "custom-label": "",
                      "unknown": 42
                    }""",
                ),
                objectMapper,
            )
        }

        assertEquals("entity-body-invalid", exception.code)
        assertEquals(Publication::class.java, exception.entityType)
        val errors = exception.errors.map { it.code to (it.args["fieldName"] ?: it.args["fields"]) }
        assertEquals(
            listOf(
                "entity-field-unknown" to "unknown",
                "entity-field-invalid" to "discriminator",
                "entity-field-invalid" to "publisher",
                "entity-mismatched-input" to listOf("authors"),
                "entity-field-missing-value" to "title",
                "entity-mismatched-input" to listOf("description"),
                "entity-field-invalid" to "custom-label",
            ),
            errors,
        )
        assertFalse(command.isBound)
    }

    @Test
    fun bindCreateMissingAndNull() {
        val exception = assertThrows<EntityBodyInvalid> {
            CreatePublication().bind(json("""{ "discriminator": "magazine", "title": null }"""), objectMapper)
        }
        val errors = exception.errors.map { it.code to it.args["fieldName"] }
        assertEquals(
            listOf(
                "entity-field-missing-value" to "publisher",
                "entity-field-missing-value" to "authors",
                "entity-field-missing-value" to "title",
            ),
            errors,
        )
    }

    @Test
    fun bindNonObject() {
        val exception = assertThrows<EntityBodyInvalid> {
            CreatePublication().bind(json("""[]"""), objectMapper)
        }
        assertEquals("entity-mismatched-input", exception.errors.single().code)
    }

    @Test
    fun unboundAccessFails() {
        assertThrows<GenericCommandMisconfigured> { CreatePublication().title }
    }

    @Test
    fun bindUpdatePatch() {
        val command = UpdatePublication()
        command.bind(json("""{ "title": "New Title", "description": null }"""), objectMapper)

        assertTrue(command.isPatched(command::title))
        assertTrue(command.isPatched(command::description))
        assertFalse(command.isPatched(command::isbn))
        assertFalse(command.isPatched(command::note))

        // Reading an absent property fails, defaults are not applied in updates
        assertThrows<GenericCommandMisconfigured> { command.isbn }
        assertFalse(command.isPatched(command::notify))
        assertThrows<GenericCommandMisconfigured> { command.notify }

        val patched = mutableListOf<Any?>()
        command.ifPatched(command::title) { patched += it }
        command.ifPatched(command::description) { patched += it }
        command.ifPatched(command::isbn) { patched += it }
        command.ifPatchedNotNull(command::description) { patched += it }
        assertEquals(listOf("New Title", null), patched)

        val book = book()
        command.patchFields(book, command::title, command::description, command::isbn)
        assertEquals("New Title", book.title)
        assertNull(book.description)
        assertEquals("isbn", book.isbn)

        // Binding again replaces all values
        command.bind(json("""{ "isbn": "123" }"""), objectMapper)
        assertFalse(command.isPatched(command::title))
        assertTrue(command.isPatched(command::isbn))
        assertEquals("123", command.isbn)
    }

    @Test
    fun bindUpdateNullNotAllowed() {
        // Absent keys are allowed in updates, null is only accepted by nullable properties
        val exception = assertThrows<EntityBodyInvalid> {
            UpdatePublication().bind(json("""{ "title": null, "note": null }"""), objectMapper)
        }
        val error = exception.errors.single()
        assertEquals("entity-field-missing-value" to "title", error.code to error.args["fieldName"])
    }

    @Test
    fun patchSubtypeFieldOfOtherSubtype() {
        val command = UpdatePublication()
        command.bind(json("""{ "isbn": "123" }"""), objectMapper)

        val book = book()
        command.patchFields(book, command::isbn)
        assertEquals("123", book.isbn)

        val magazine = Magazine(
            id = EntityId.generate(),
            publisher = book.publisher,
            title = "Magazine",
            description = null,
            firstPublishedOn = null,
            lastPublishedOn = null,
            publishCycle = PublishCycle.entries.first(),
        )
        val exception = assertThrows<EntityFieldWriteNotAllowed> { command.patchFields(magazine, command::isbn) }
        assertEquals("isbn", exception.fieldName)

        // Only schema(...) properties can be written to the entity
        assertThrows<GenericCommandMisconfigured> { command.patchFields(book, command::note) }
    }

    @Test
    fun readOnlyColumns() {
        val id = EntityId.generate<PublicationId>()
        val command = CreateWithReadOnlyColumns()
        command.bind(json("""{ "id": "$id", "publisherId": "$publisherId" }"""), objectMapper)
        assertEquals(id, command.id)
        assertEquals(publisherId, command.publisherId)

        // Read-only columns are parsed by the column type
        val exception = assertThrows<EntityBodyInvalid> {
            CreateWithReadOnlyColumns().bind(json("""{ "id": [] }"""), objectMapper)
        }
        val error = exception.errors.single()
        assertEquals("entity-mismatched-input" to listOf("id"), error.code to error.args["fields"])
    }

    @Test
    fun patchReadOnlyColumnFails() {
        val otherPublisherId = EntityId.generate<PublisherId>()
        val command = UpdatePublication()
        command.bind(json("""{ "title": "New Title", "publisherId": "$otherPublisherId" }"""), objectMapper)

        val patched = mutableListOf<Any?>()
        command.ifPatched(command::publisherId) { patched += it }
        assertEquals(listOf(otherPublisherId), patched)

        // Patching a read-only column fails, before any value is written
        val book = book()
        assertThrows<GenericCommandMisconfigured> { command.patchFields(book, command::title, command::publisherId) }
        assertEquals("Title", book.title)
        assertEquals(publisherId, book.publisher.id)

        // Also if the property is absent in the JSON body
        command.bind(json("""{ "title": "New Title" }"""), objectMapper)
        assertThrows<GenericCommandMisconfigured> { command.patchFields(book, command::publisherId) }
    }

    @Test
    fun invalidDeclarationsFailOnInstantiation() {
        val entityType = assertThrows<BeanInstantiationException> {
            BeanUtils.instantiateClass(EntityTypeColumnCommand::class.java)
        }
        assertInstanceOf(IllegalArgumentException::class.java, entityType.cause)

        val duplicate = assertThrows<BeanInstantiationException> {
            BeanUtils.instantiateClass(DuplicateKeyCommand::class.java)
        }
        assertInstanceOf(IllegalArgumentException::class.java, duplicate.cause)
    }

    @Test
    fun optionalPropertyOfNonNullableColumn() {
        val requiredKeys = """
            "discriminator": "book",
            "publisher": "$publisherId",
            "authors": [],
            "title": "The Title"
        """

        // Absent keys of optional() properties are null
        val command = CreatePublication()
        command.bind(json("{ $requiredKeys }"), objectMapper)
        assertNull(command.isbn)
        assertNull(command.bookType)
        assertEquals(emptyList<AuthorId>(), command.authors)

        // A JSON null is not accepted, as the column is not nullable
        val exception = assertThrows<EntityBodyInvalid> {
            CreatePublication().bind(json("""{ $requiredKeys, "isbn": null }"""), objectMapper)
        }
        val error = exception.errors.single()
        assertEquals("entity-field-missing-value" to "isbn", error.code to error.args["fieldName"])
    }

    @Test
    fun bindMismatchedReferencesAndSubtype() {
        val exception = assertThrows<EntityBodyInvalid> {
            CreatePublication().bind(
                json(
                    """{
                      "discriminator": 1,
                      "publisher": true,
                      "authors": ["$authorId", "not an id"],
                      "title": "The Title"
                    }""",
                ),
                objectMapper,
            )
        }
        val errors = exception.errors.map { it.code to (it.args["fieldName"] ?: it.args["fields"]) }
        assertEquals(
            listOf(
                "entity-mismatched-input" to listOf("discriminator"),
                "entity-mismatched-input" to listOf("publisher"),
                "entity-field-invalid" to "authors",
            ),
            errors,
        )
    }

    @Test
    fun detailsProviderAndChainedValidations() {
        val command = CreateWithDetailsProvider()

        // Entity class is resolved from the type argument, if the details provider is not a fieldset
        assertEquals(Publication::class.java, command.entityClass)

        command.bind(json("""{ "code": "abc" }"""), objectMapper)
        assertEquals("abc", command.code)

        val reasons = listOf(" ", "abcdef").map { code ->
            val exception = assertThrows<EntityBodyInvalid> {
                CreateWithDetailsProvider().bind(json("""{ "code": "$code" }"""), objectMapper)
            }
            assertEquals(Publication::class.java, exception.entityType)
            exception.errors.single().args["reason"]
        }
        assertEquals(listOf("not-blank", "too-long"), reasons)
    }

    private fun book() = Book(
        id = EntityId.generate(),
        publisher = Publisher(id = publisherId, name = "Publisher", contact = Contact()),
        title = "Title",
        description = "Description",
        firstPublishedOn = null,
        isbn = "isbn",
        bookType = BookType.Paperback,
        mainAuthor = null,
    )
}
