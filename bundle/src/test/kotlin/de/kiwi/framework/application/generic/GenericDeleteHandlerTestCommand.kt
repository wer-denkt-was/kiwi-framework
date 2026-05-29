package de.kiwi.framework.application.generic

import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.EntityNotFound
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.framework.example.DeleteExample
import de.kiwi.framework.example.DeleteExampleHandler
import de.kiwi.framework.example.ExampleEntity
import de.kiwi.framework.example.ExampleId
import de.kiwi.framework.matchEntityDeleted
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired

@ExampleDataJpaTest
class GenericDeleteHandlerTestCommand {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var handler: DeleteExampleHandler

    private lateinit var existingEntity: ExampleEntity

    @BeforeEach
    fun setUp() {
        existingEntity = ExampleEntity.create(name = "delete me!").value
        entityManager.persist(existingEntity)
        entityManager.flush()
    }

    @Test
    fun handleDelete() {
        val command = DeleteExample().apply { entityId = existingEntity.id }
        val events = handler.handle(command, UserDescriptor.SYSTEM)
        entityManager.flush()

        assertThat(
            events.events,
            containsInAnyOrder(
                matchEntityDeleted(id = existingEntity.id, deletedName = equalTo("delete me!"))
            )
        )

        val afterDelete = entityManager.find(ExampleEntity::class.java, existingEntity.id)
        assertNull(afterDelete)
    }

    @Test
    fun handleDeleteNotFound() {
        val missingId = ExampleId(-1) // Negative ID is never valid
        val command = DeleteExample().apply { entityId = missingId }

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
                hasEntry("id", missingId.toString()),
            )
        )
    }
}
