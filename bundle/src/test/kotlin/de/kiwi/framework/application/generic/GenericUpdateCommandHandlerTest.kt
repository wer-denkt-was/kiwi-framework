package de.kiwi.framework.application.generic

import de.kiwi.api.domain.*
import de.kiwi.framework.example.ExampleEntity
import de.kiwi.framework.example.ExampleId
import de.kiwi.framework.example.UpdateExample
import de.kiwi.framework.example.UpdateExampleHandler
import de.kiwi.framework.matchEntityUpdated
import de.kiwi.framework.matchExample
import de.kiwi.framework.mergeAndDetach
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

@ExampleDataJpaTest
class GenericUpdateCommandHandlerTest {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var handler: UpdateExampleHandler

    private fun json(json: String) = objectMapper.readTree(json)

    private fun UpdateExample.bindJson(json: String) = bind(json(json), objectMapper)

    @Test
    fun handleUpdate() {
        val (before, after, events) = executeUpdateExample(
            ExampleEntity.create(name = "Before Update").value
        ) {
            UpdateExample().apply {
                // Update "name" field
                bindJson("""{ "name": "After Update" }""")
                entityId = it.id
            }
        }

        assertThat(before, matchExample(name = equalTo("Before Update")))
        assertThat(after, matchExample(name = equalTo("After Update")))
        assertThat(
            events.events,
            containsInAnyOrder(
                matchEntityUpdated(
                    id = before.id,
                    nameBefore = equalTo("Before Update"),
                    nameAfter = equalTo("After Update")
                )
            )
        )
    }

    @Test
    fun handleNoUpdate() {
        val (before, after, events) = executeUpdateExample(
            ExampleEntity.create(name = "No Update").value
        ) {
            UpdateExample().apply {
                // Don't set any properties, no updates
                bindJson("{}")
                entityId = it.id
            }
        }

        assertThat(before, matchExample(name = equalTo("No Update"), optional = nullValue()))
        assertThat(after, matchExample(name = equalTo("No Update"), optional = nullValue()))
        assertThat(
            events.events,
            containsInAnyOrder(
                matchEntityUpdated(id = before.id, nameBefore = equalTo("No Update"), nameAfter = equalTo("No Update"))
            )
        )
    }

    @Test
    fun handleUpdateSetNull() {
        val (before, after, events) = executeUpdateExample(
            ExampleEntity.create(name = "Not null", optional = "Not null yet").value
        ) {
            UpdateExample().apply {
                // Setting a nullable field to null works as expected
                bindJson("""{ "optional": null }""")
                entityId = it.id
            }
        }

        // "name" null ignored, "optional" set to null
        assertThat(before, matchExample(name = equalTo("Not null"), optional = equalTo("Not null yet")))
        assertThat(after, matchExample(name = equalTo("Not null"), optional = nullValue()))
        assertThat(events.events, containsInAnyOrder(matchEntityUpdated(before.id)))
    }

    @Test
    fun handleUpdateNullNotAllowed() {
        // Setting a non-nullable field to null will throw an exception
        val bodyException = assertThrows(EntityBodyInvalid::class.java) {
            executeUpdateExample(
                ExampleEntity.create(name = "Not null", optional = null).value
            ) {
                UpdateExample().apply {
                    bindJson(
                        // language=JSON
                        """{ "name": null }"""
                    )
                    entityId = it.id
                }
            }
        }
        val exception = bodyException.errors.single() as EntityFieldMissingValue
        assertEquals(ExampleEntity::class.java.name, exception.args["entityType"])
        assertEquals("name", exception.args["fieldName"])
    }

    @Test
    fun handleUpdateBlankNotAllowed() {
        // Setting a notBlank validated field to null will throw an exception
        val bodyException = assertThrows(EntityBodyInvalid::class.java) {
            executeUpdateExample(ExampleEntity.create(name = "Not blank", optional = null).value) {
                UpdateExample().apply {
                    bindJson(
                        // language=json
                        """{ "name": "   ", "optional": "name must not be blank" }"""
                    )
                    entityId = it.id
                }
            }
        }
        assertEquals("entity-body-invalid", bodyException.code)
        val exception = bodyException.errors.single() as EntityFieldInvalid
        assertEquals("entity-field-invalid", exception.code)
        assertEquals("name", exception.fieldName)
        assertEquals("not-blank", exception.reason)
    }

    @Test
    fun updateEntityNotFound() {
        val missingId = ExampleId(-1) // Negative ID is never valid
        val command = UpdateExample().apply {
            entityId = missingId
            bindJson("""{ "name": "This should not work!" }""")
        }

        val ex = assertThrows(EntityNotFound::class.java) {
            handler.handle(command, UserDescriptor.SYSTEM)
        }

        println(ex)
        assertThat(ex.message, stringContainsInOrder("entity-not-found", "ExampleEntity"))
        assertThat(ex.code, equalTo("entity-not-found"))
        assertThat(ex.status, sameInstance(DomainException.Status.NotFound))
        assertThat(
            ex.args,
            allOf(
                hasEntry("entityType", ExampleEntity::class.java.name),
                hasEntry("id", missingId.toString())
            )
        )
    }

    // ---

    private fun executeUpdateExample(
        entity: ExampleEntity,
        getCommand: (ExampleEntity) -> UpdateExample,
    ): ExampleUpdateResult {
        val before = entityManager.mergeAndDetach(entity)
        val events = handler.handle(getCommand(before), UserDescriptor.SYSTEM)
        entityManager.flush()
        val after = entityManager.find(before.javaClass, before.id)
        return ExampleUpdateResult(before = before, after = after, events = events)
    }

    private data class ExampleUpdateResult(
        val before: ExampleEntity,
        val after: ExampleEntity,
        val events: DomainResult<*>,
    )
}
