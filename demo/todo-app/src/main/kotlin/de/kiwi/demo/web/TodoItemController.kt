package de.kiwi.demo.web

import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.demo.domain.TodoItemId
import de.kiwi.demo.domain.TodoListId
import de.kiwi.demo.domain.command.MoveTodoItemToList
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/TodoItem")
@Tag(name = "kiwi.TodoItem")
class TodoItemController(
    private val bus: CommandQueryBus,
) {
    @Operation(summary = "Move a TodoItem into a specific TodoList")
    @PatchMapping(path = ["/{itemId}/TodoList/{listId}"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun moveItemToList(
        @PathVariable("itemId") itemId: TodoItemId,
        @PathVariable("listId") listId: TodoListId,
        user: UserDescriptor,
    ): ResponseEntity<Unit> {
        val command = MoveTodoItemToList(itemId = itemId, targetListId = listId)
        val result = bus.dispatch(command, user)
        return ResponseEntity.ok(result)
    }
}
