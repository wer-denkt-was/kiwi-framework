package de.kiwi.demo.domain

import de.kiwi.api.domain.*
import de.kiwi.api.domain.Validation.Validators.notBlank
import de.kiwi.demo.domain.command.TodoItemCreated
import de.kiwi.demo.domain.command.TodoItemDeleted
import de.kiwi.demo.domain.command.TodoItemMovedToList
import jakarta.persistence.*

@Embeddable
class TodoItemId(value: Long) : EntityId(value)

@Entity
@Table(name = "todo_item")
class TodoItem(
    id: TodoItemId,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: TodoItemStatus,

    @ManyToOne(optional = false)
    @JoinColumn(name = "todo_list_id", nullable = false)
    var todoList: TodoList,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "order_index", nullable = false)
    var order: Int,
) : BaseEntity<TodoItemId>(id) {

    companion object {
        val validTitle = TodoItem::title.validateColumn() and notBlank and Validation.map { it.trim() }
        val validContent = TodoItem::content.validateColumn()

        fun create(
            list: TodoList,
            title: String,
            content: String,
        ): DomainResult<TodoItem> = DomainResult { record ->
            TodoItem(
                id = EntityId.generate<TodoItemId>(),
                status = TodoItemStatus.Open,
                todoList = list,
                title = validTitle(title),
                content = validContent(content),
                order = list.items.size,
            ).also {
                record(TodoItemCreated(it))
            }
        }
    }

    fun delete() = DomainResult { record ->
        todoList.removeItem(this)
        record(TodoItemDeleted(this))
    }

    fun moveToList(targetList: TodoList): DomainResult<Unit> = DomainResult { record ->
        val sourceList = todoList
        if (sourceList != targetList) {
            if (sourceList.removeItem(this)) {
                if (targetList.addItem(this)) {
                    todoList = targetList
                    record(TodoItemMovedToList(this, sourceList = sourceList, targetList = targetList))
                }
            }
        }
    }

    fun moveOrderInList(newIndex: Int): DomainResult<Unit> {
        return todoList.moveItemToIndex(this, newIndex)
    }
}

enum class TodoItemStatus { Open, Done }
