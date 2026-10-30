package de.kiwi.demo.application

import de.kiwi.api.application.generic.GenericCreateHandler
import de.kiwi.api.application.generic.GenericDeleteHandler
import de.kiwi.api.application.generic.GenericUpdateHandler
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.domain.requireAuthorization
import de.kiwi.demo.domain.TodoList
import de.kiwi.demo.domain.command.CreateTodoList
import de.kiwi.demo.domain.command.DeleteTodoList
import de.kiwi.demo.domain.command.TodoListDeleted
import de.kiwi.demo.domain.command.UpdateTodoList
import org.springframework.stereotype.Component

@Component
class CreateTodoListHandler : GenericCreateHandler<CreateTodoList, TodoList>() {
    override fun createEntity(create: CreateTodoList, user: UserDescriptor) = DomainResult { record ->
        record(TodoList.create(title = create.title, user = user))
    }
}

@Component
class UpdateTodoListHandler : GenericUpdateHandler<UpdateTodoList, TodoList>() {
    override fun updateEntity(update: UpdateTodoList, entity: TodoList, user: UserDescriptor) = DomainResult {
        user.requireAdminOrListAuthor(entity)
        update.patchFields(entity, update::title)
    }
}

@Component
class DeleteTodoListHandler : GenericDeleteHandler<DeleteTodoList, TodoList>() {
    override fun beforeDeleteEntity(
        delete: DeleteTodoList,
        entity: TodoList,
        user: UserDescriptor,
    ) = DomainResult { record ->
        user.requireAdminOrListAuthor(entity)
        record(TodoListDeleted(entity))
    }
}

fun UserDescriptor.requireAdminOrListAuthor(list: TodoList) =
    requireAuthorization { isAdmin || username == list.author }
