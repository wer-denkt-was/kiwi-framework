package de.kiwi.demo.domain

import de.kiwi.api.domain.*
import de.kiwi.api.domain.Validation.Validators.notBlank
import de.kiwi.api.domain.and
import de.kiwi.demo.domain.command.TodoListCreated
import de.kiwi.demo.domain.command.TodoListDeleted
import de.kiwi.demo.domain.command.TodoListReordered
import jakarta.persistence.*

@Embeddable
class TodoListId(value: Long) : EntityId(value)

@Entity
@Table(name = "todo_list")
class TodoList(
    id: TodoListId,

    @Column(name = "title", nullable = false, length = 255)
    var title: String,

    @Column(name = "author", nullable = true, length = 255)
    val author: String?,
) : BaseEntity<TodoListId>(id) {

    @OneToMany(mappedBy = "todoList", orphanRemoval = true)
    val items: MutableSet<TodoItem> = HashSet()
    fun addItem(item: TodoItem) = items.add(item)
    fun removeItem(item: TodoItem) = items.remove(item)
    fun orderedItemList() = items.sortedBy { it.order }

    companion object {
        val validTitle = TodoList::title.validateColumn() and notBlank and Validation.map { it.trim() }

        fun create(
            title: String,
            user: UserDescriptor,
        ): DomainResult<TodoList> = DomainResult { record ->
            TodoList(
                id = EntityId.generate<TodoListId>(),
                title = validTitle(title),
                author = user.username,
            ).also {
                record(TodoListCreated(it))
            }
        }
    }

    fun delete() = DomainResult { record ->
        record(TodoListDeleted(this))
    }

    fun moveItemToIndex(movedItem: TodoItem, newIndex: Int): DomainResult<Unit> {
        // Create list from current order, move item in list, then apply new order to entities
        val itemList = items.sortedBy { it.order }
        val reorderedItemList = itemList.moveToIndex(newIndex) { it == movedItem }
        reorderedItemList.applyListOrder { order = it }

        return DomainResult.events(TodoListReordered(this))
    }
}

private fun <T> List<T>.moveToIndex(targetIndex: Int, predicate: (T) -> Boolean): List<T> {
    val sourceIndex = indexOfFirst(predicate).takeUnless { it == -1 } ?: return this
    val reordered = toMutableList()
    val movedItem = reordered.removeAt(sourceIndex)
    reordered.add(targetIndex, movedItem)
    return reordered
}

private fun <T> List<T>.applyListOrder(apply: T.(Int) -> Unit) {
    forEachIndexed { index, item -> item.apply(index) }
}
