package de.kiwi.demo.application

import tools.jackson.databind.ObjectMapper
import de.kiwi.api.domain.AccessDenied
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityUpdated
import de.kiwi.api.generic.GenericBodyCommand
import de.kiwi.api.util.find
import de.kiwi.demo.domain.TodoItem
import de.kiwi.demo.domain.TodoList
import de.kiwi.demo.domain.command.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.`is`
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

@DemoDataJpaTest
class TodoListCommandHandlersTest {
    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var createTodoListHandler: CreateTodoListHandler

    @Autowired
    private lateinit var updateTodoListHandler: UpdateTodoListHandler

    @Autowired
    private lateinit var deleteTodoListHandler: DeleteTodoListHandler

    // Dummy users
    private val alice = dummyUser("Alice Doe")
    private val bob = dummyUser("Bob Smith")

    @BeforeEach
    fun setUp() {
    }

    @AfterEach
    fun tearDown() {
        entityManager.deleteAll<TodoItem>()
        entityManager.deleteAll<TodoList>()
    }

    @Test
    fun createEntity() {
        val command = CreateTodoList().apply {
            bindJson("""{ "title": "create test list" }""")
        }
        val events = tx { createTodoListHandler.handle(command, user = alice) }

        val created = events.findByType<TodoListCreated>() ?: fail("missing TodoListCreated")
        assertEquals(alice.username, created.todoList.author)
        assertEquals("create test list", created.todoList.title)
        assertThat(created.todoList.items, `is`(empty()))
    }

    @Test
    fun updateEntity() {
        val todoList = prepareTodoList(title = "old list title", user = alice)

        val command = UpdateTodoList().apply {
            entityId = todoList.id
            bindJson("""{ "title": "new list title!" }""")
        }
        val events = tx { updateTodoListHandler.handle(command, user = alice) }

        val updated = events.findByType<EntityUpdated>() ?: fail("missing EntityUpdated")
        val updatedTodoList = entityManager.find<TodoList>(updated.entityId) ?: fail("missing updated TodoList")
        assertEquals(todoList.id, updatedTodoList.id)
        assertEquals("new list title!", updatedTodoList.title)
        assertEquals(alice.username, updatedTodoList.author)
    }

    @Test
    fun denyUpdateEntityWrongUser() {
        val todoList = prepareTodoList(title = "list by bob", user = bob)

        val command = UpdateTodoList().apply {
            entityId = todoList.id
            bindJson("""{ "title": "alice can't update bob's list!" }""")
        }
        assertThrows<AccessDenied> {
            tx { updateTodoListHandler.handle(command, user = alice) }
        }
    }

    @Test
    fun deleteEntity() {
        val todoList = prepareTodoList(title = "delete this list", user = alice)

        val command = DeleteTodoList().apply {
            entityId = todoList.id
        }
        val events = tx { deleteTodoListHandler.handle(command, user = alice) }

        val deleted = events.findByType<TodoListDeleted>() ?: fail("missing TodoListDeleted")
        assertNull(entityManager.find<TodoList>(deleted.todoList.id))
    }

    @Test
    fun denyDeleteEntityWrongUser() {
        val todoList = prepareTodoList(title = "list by alice", user = alice)

        val command = DeleteTodoList().apply {
            entityId = todoList.id
        }
        assertThrows<AccessDenied> {
            tx { deleteTodoListHandler.handle(command, user = bob) }
        }
    }

    private fun prepareTodoList(title: String, user: UserDescriptor) =
        tx { entityManager.merge(TodoList.create(title, user).value) }!!

    // --- Utilities ---

    private fun json(@Language("JSON") json: String) = objectMapper.readTree(json)

    private fun GenericBodyCommand<*>.bindJson(@Language("JSON") json: String) = bind(json(json), objectMapper)

    private inline fun <T> tx(crossinline action: () -> T) = transactions.execute { action() }
}
