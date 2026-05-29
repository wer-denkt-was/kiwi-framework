package de.kiwi.demo.domain.command

import de.kiwi.api.domain.Authorized
import de.kiwi.api.domain.Command
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericCreate
import de.kiwi.api.generic.GenericDelete
import de.kiwi.api.generic.GenericUpdate
import de.kiwi.demo.domain.TodoItem
import de.kiwi.demo.domain.TodoItemId
import de.kiwi.demo.domain.TodoList
import de.kiwi.demo.domain.TodoListId
import de.kiwi.demo.domain.schema.TodoItemFieldsets
import de.kiwi.demo.domain.schema.TodoListFieldsets

class CreateTodoList : GenericCreate<TodoList>() {
    override fun entityId(entity: TodoList) = entity.id
    override val entityFieldset = TodoListFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class UpdateTodoList : GenericUpdate<TodoList, TodoListId>() {
    override val entityFieldset = TodoListFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class DeleteTodoList : GenericDelete<TodoList, TodoListId>() {
    override val entityFieldset = TodoListFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class CreateTodoItem : GenericCreate<TodoItem>() {
    override fun entityId(entity: TodoItem) = entity.id
    override val entityFieldset = TodoItemFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class UpdateTodoItem : GenericUpdate<TodoItem, TodoItemId>() {
    override val entityFieldset = TodoItemFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

data class MoveTodoItemToList(val itemId: TodoItemId, val targetListId: TodoListId) : Command<Unit>(), Authorized {
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class DeleteTodoItem : GenericDelete<TodoItem, TodoItemId>() {
    override val entityFieldset = TodoItemFieldsets.default
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}
