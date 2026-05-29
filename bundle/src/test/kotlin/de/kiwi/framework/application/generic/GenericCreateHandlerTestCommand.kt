package de.kiwi.framework.application.generic

import com.fasterxml.jackson.databind.ObjectMapper
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityCreated
import de.kiwi.api.util.flushAndClear
import de.kiwi.framework.example.CreateExample
import de.kiwi.framework.example.CreateExampleHandler
import de.kiwi.framework.example.ExampleEntity
import de.kiwi.framework.matchEntityCreated
import de.kiwi.framework.matchExample
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.equalTo
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springframework.beans.factory.annotation.Autowired

@ExampleDataJpaTest
class GenericCreateHandlerTestCommand {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var handler: CreateExampleHandler

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun json(@Language("JSON") json: String) = objectMapper.readTree(json)

    @Test
    fun handleCreate() {
        val command = CreateExample()
        command.jsonBody = json("""{ "name": "Long Live The New Fresh", "optional": "Danimal Cannon" }""")

        val events = handler.handle(command, UserDescriptor.SYSTEM)

        assertThat(
            events,
            containsInAnyOrder(
                matchEntityCreated(createdName = equalTo("Long Live The New Fresh"))
            )
        )

        entityManager.flushAndClear()
        val createdEvent = events.findByType<EntityCreated>() ?: fail("Missing EntityCreated")
        val createdEntity = entityManager.find(ExampleEntity::class.java, createdEvent.entityId)

        assertThat(
            createdEntity,
            matchExample(
                name = equalTo("Long Live The New Fresh"),
                optional = equalTo("Danimal Cannon"),
            )
        )
    }

    @Test
    fun handleCreateJson() {
        val command = CreateExample()
        command.jsonBody = json("""{ "name": "Milky Ways", "optional": "Bossfight" }""")

        val events = handler.handle(command, UserDescriptor.SYSTEM)

        assertThat(
            events,
            containsInAnyOrder(
                matchEntityCreated(createdName = equalTo("Milky Ways"))
            )
        )
    }
}
