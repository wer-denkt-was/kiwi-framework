package de.kiwi.demo.application

import de.kiwi.api.application.command.CommandHandler
import de.kiwi.api.application.generic.GenericCreateHandler
import de.kiwi.api.application.generic.GenericDeleteHandler
import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.requireById
import de.kiwi.demo.domain.TodoItem
import de.kiwi.demo.domain.command.CreateTodoItem
import de.kiwi.demo.domain.command.DeleteTodoItem
import de.kiwi.demo.domain.command.MoveTodoItemToList
import de.kiwi.demo.domain.command.UpdateTodoItem
import org.springframework.stereotype.Component

@Component
class CreateTodoItemHandler(
    private val listRepository: TodoListRepository,
) : GenericCreateHandler<CreateTodoItem, TodoItem>() {
    override fun createEntity(create: CreateTodoItem, user: UserDescriptor) = DomainResult { record ->
        val list = listRepository.requireById(create.list)
        user.requireAdminOrListAuthor(list)
        record(TodoItem.create(list, title = create.title, content = create.content))
            .also { item -> list.addItem(item) }
    }
}

@Component
class UpdateTodoItemHandler : GenericUpdateHandler<UpdateTodoItem, TodoItem>() {
    override fun updateEntity(update: UpdateTodoItem, entity: TodoItem, user: UserDescriptor) = DomainResult { record ->
        user.requireAdminOrListAuthor(entity.todoList)
        update.patchFields(entity, update::title, update::content, update::status)
        update.ifPatched(update::order) { order -> record(entity.moveOrderInList(order)) }
    }
}

@Component
class MoveTodoItemToListHandler(
    private val itemRepository: TodoItemRepository,
    private val listRepository: TodoListRepository,
) : CommandHandler<MoveTodoItemToList, Unit> {
    override fun handle(command: MoveTodoItemToList, user: UserDescriptor) = DomainResult { record ->
        val item = itemRepository.requireById(command.itemId)
        val targetList = listRepository.requireById(command.targetListId)
        user.requireAdminOrListAuthor(item.todoList)
        user.requireAdminOrListAuthor(targetList)

        record(item.moveToList(targetList))
    }
}

@Component
class DeleteTodoItemHandler : GenericDeleteHandler<DeleteTodoItem, TodoItem>() {
    override fun beforeDeleteEntity(
        delete: DeleteTodoItem,
        entity: TodoItem,
        user: UserDescriptor,
    ) = DomainResult { record ->
        user.requireAdminOrListAuthor(entity.todoList)
        record(entity.delete())
    }
}
