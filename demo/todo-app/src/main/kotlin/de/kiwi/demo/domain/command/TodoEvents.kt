package de.kiwi.demo.domain.command

import de.kiwi.api.domain.DomainEvent
import de.kiwi.demo.domain.TodoItem
import de.kiwi.demo.domain.TodoList
import de.kiwi.demo.domain.schema.TodoItemFieldsets
import de.kiwi.demo.domain.schema.TodoListFieldsets

data class TodoListCreated(val todoList: TodoList) : DomainEvent() {
    override val details = TodoListFieldsets.default.extract(todoList)
}

data class TodoListReordered(val todoList: TodoList) : DomainEvent() {
    override val details = TodoListFieldsets.itemsOrder.extract(todoList)
}

data class TodoListDeleted(val todoList: TodoList) : DomainEvent() {
    override val details = TodoListFieldsets.default.extract(todoList)
}

data class TodoItemCreated(val todoItem: TodoItem) : DomainEvent() {
    override val details = TodoItemFieldsets.default.extract(todoItem)
}

data class TodoItemMovedToList(
    val todoItem: TodoItem,
    val sourceList: TodoList,
    val targetList: TodoList,
) : DomainEvent() {
    override val details = mapOf(
        "item" to TodoItemFieldsets.default.extract(todoItem),
        "sourceList" to TodoListFieldsets.default.extract(sourceList),
        "targetList" to TodoListFieldsets.default.extract(targetList),
    )
}

data class TodoItemDeleted(val todoItem: TodoItem) : DomainEvent() {
    override val details = TodoItemFieldsets.default.extract(todoItem)
}
