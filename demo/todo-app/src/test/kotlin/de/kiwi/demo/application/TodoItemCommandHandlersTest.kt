package de.kiwi.demo.application

import tools.jackson.databind.ObjectMapper
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.EntityUpdated
import de.kiwi.api.generic.GenericBodyCommand
import de.kiwi.api.util.find
import de.kiwi.demo.domain.*
import de.kiwi.demo.domain.command.*
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate

@DemoDataJpaTest
class TodoItemCommandHandlersTest {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var createTodoItemHandler: CreateTodoItemHandler

    @Autowired
    private lateinit var updateTodoItemHandler: UpdateTodoItemHandler

    @Autowired
    private lateinit var deleteTodoItemHandler: DeleteTodoItemHandler

    @Autowired
    private lateinit var moveTodoItemToListHandler: MoveTodoItemToListHandler

    // Dummy users
    private val alice = dummyUser("Alice Doe")
    private val bob = dummyUser("Bob Smith")

    @AfterEach
    fun tearDown() {
        entityManager.deleteAll<TodoItem>()
        entityManager.deleteAll<TodoList>()
    }

    @Test
    fun createEntity() {
        val list = prepareTodoList(title = "new list", user = alice)

        val command = CreateTodoItem().apply {
            bindJson(
                """{
                |  "title": "new item",
                |  "content": "this is a create test item",
                |  "list": "${list.id}"
                |}""".trimMargin()
            )
        }
        val events = tx { createTodoItemHandler.handle(command, user = alice) }

        val created = events.findByType<TodoItemCreated>() ?: fail("missing CreateTodoItem")
        assertEquals(list.id, created.todoItem.todoList.id)
        assertEquals("new item", created.todoItem.title)
        assertEquals("this is a create test item", created.todoItem.content)
        assertEquals(TodoItemStatus.Open, created.todoItem.status)
        assertEquals(0, created.todoItem.order)
    }

    @Test
    fun updateEntity() {
        val list = prepareTodoList(title = "example list", user = alice)
        val originalItem = prepareTodoItem(list, title = "old title", content = "old content")

        val command = UpdateTodoItem().apply {
            entityId = originalItem.id
            bindJson("""{ "title": "new title!", "content": "new content!", "status": "Done" }""")
        }
        val events = tx { updateTodoItemHandler.handle(command, user = alice) }

        val updatedEvent = events.findByType<EntityUpdated>() ?: fail("missing EntityUpdated")
        assertEquals(originalItem.id, updatedEvent.entityId)

        val updatedItem = entityManager.find<TodoItem>(updatedEvent.entityId) ?: fail("missing updated TodoItem")
        assertEquals("new title!", updatedItem.title)
        assertEquals("new content!", updatedItem.content)
        assertEquals(TodoItemStatus.Done, updatedItem.status)
        assertEquals(list.id, updatedItem.todoList.id)
    }

    @Test
    fun updateOrderInList() {
        val list = prepareTodoList(title = "example list", user = alice)
        val itemA = prepareTodoItem(list, title = "A", content = "1")
        val itemB = prepareTodoItem(list, title = "B", content = "1")
        val itemC = prepareTodoItem(list, title = "C", content = "1")

        // Assert initial order
        assertThat(
            list.orderedItemList(),
            contains(
                matchItem(itemA.id, order = 0),
                matchItem(itemB.id, order = 1),
                matchItem(itemC.id, order = 2),
            )
        )

        // Update order of item B
        val command = UpdateTodoItem().apply {
            entityId = itemB.id
            bindJson("""{ "order": 2 }""")
        }
        val events = tx { updateTodoItemHandler.handle(command, user = alice) }
        entityManager.flush()

        val orderChanged = events.findByType<TodoListReordered>() ?: fail("missing TodoListReordered")
        assertEquals(list.id, orderChanged.todoList.id)

        // Assert updated order
        val reorderedList = entityManager.find<TodoList>(list.id) ?: fail("missing reordered TodoList")
        assertThat(
            reorderedList.orderedItemList(),
            contains(
                matchItem(itemA.id, order = 0),
                matchItem(itemC.id, order = 1),
                matchItem(itemB.id, order = 2), // Item B moved to order 2
            )
        )
    }

    @Test
    fun moveOrderToList() {
        val listA = prepareTodoList(title = "A", user = alice)
        val listB = prepareTodoList(title = "B", user = alice)
        val itemX = prepareTodoItem(listA, title = "X", content = "stay in list A")
        val itemY = prepareTodoItem(listA, title = "Y", content = "move to list B")
        val itemZ = prepareTodoItem(listB, title = "Z", content = "stay in list B")

        // Assert original membership
        assertThat(listA.items, containsInAnyOrder(matchItem(itemX.id), matchItem(itemY.id)))
        assertThat(listB.items, containsInAnyOrder(matchItem(itemZ.id)))

        // Move item from list A to list B
        val command = MoveTodoItemToList(
            itemId = itemY.id,
            targetListId = listB.id,
        )
        val events = tx { moveTodoItemToListHandler.handle(command, user = alice) }
        entityManager.flush()

        val itemMoved = events.findByType<TodoItemMovedToList>() ?: fail("missing TodoItemMovedToList")
        assertEquals(itemY.id, itemMoved.todoItem.id)
        assertEquals(listA.id, itemMoved.sourceList.id)
        assertEquals(listB.id, itemMoved.targetList.id)

        // Assert updated membership
        assertThat(listA.items, containsInAnyOrder(matchItem(itemX.id)))
        assertThat(listB.items, containsInAnyOrder(matchItem(itemY.id), matchItem(itemZ.id)))
    }

    @Test
    fun deleteEntity() {
        val list = prepareTodoList(title = "example list", user = alice)
        val item = prepareTodoItem(list, title = "delete me", content = "goodbye.")

        val command = DeleteTodoItem().apply {
            entityId = item.id
        }
        val events = tx { deleteTodoItemHandler.handle(command, user = alice) }

        val deleted = events.findByType<TodoItemDeleted>() ?: fail("missing TodoItemDeleted")
        assertEquals(item.id, deleted.todoItem.id)
        assertNull(entityManager.find<TodoItem>(deleted.todoItem.id))

        val updatedList = entityManager.find<TodoList>(list.id) ?: fail("missing updated TodoList")
        assertThat(updatedList.items, not(hasItem(matchItem(item.id))))
    }

    private fun prepareTodoList(title: String, user: UserDescriptor) = tx {
        entityManager.merge(TodoList.create(title, user).value)
    }!!

    private fun prepareTodoItem(list: TodoList, title: String, content: String) = tx {
        entityManager.merge(TodoItem.create(list, title, content).value)
            .also { item -> list.addItem(item) }
    }!!

    // --- Utilities ---

    private fun json(@Language("JSON") json: String) = objectMapper.readTree(json)

    private fun GenericBodyCommand<*>.bindJson(@Language("JSON") json: String) = bind(json(json), objectMapper)

    private fun <T> tx(action: () -> T) = transactions.execute { action() }

    private fun matchItem(
        id: TodoItemId,
        order: Int? = null,
    ): Matcher<TodoItem> = allOf(
        hasProperty(TodoItem_.ID, equalTo(id)),
        hasProperty(TodoItem_.ORDER, order?.let { equalTo(it) } ?: anything())
    )
}
