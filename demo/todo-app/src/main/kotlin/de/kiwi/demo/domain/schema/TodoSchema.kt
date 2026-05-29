package de.kiwi.demo.domain.schema

import de.kiwi.api.schema.ColumnType
import de.kiwi.api.schema.EntitySchema
import de.kiwi.api.schema.FieldsetsAvailable
import de.kiwi.demo.domain.*

// --- Schemata

object TodoListSchema : EntitySchema<TodoList>() {
    val id by entityId()

    val title by column<String>(TodoList::title)
    val author by column<String?>(TodoList::author)

    val items by toMany(TodoList::items, TodoItemSchema)
}

object TodoItemSchema : EntitySchema<TodoItem>() {
    val id by entityId()
    val list by toOne(TodoItem::todoList, TodoListSchema)
    val listId by parentEntityId(TodoItem::todoList)

    val title by column<String>(TodoItem::title)
    val content by column<String>(TodoItem::content)
    val status by column<TodoItemStatus, String>(TodoItem::status, type = ColumnType.enum())
    val order by column<Int>(TodoItem::order)
}

// --- Fieldsets

object TodoListFieldsets : FieldsetsAvailable<TodoList>(TodoListSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val details by defineFieldset {
        extendsFieldset(default)
        includeToMany(TodoListSchema.items, TodoItemFieldsets.short)
    }

    val itemsOrder by defineFieldset {
        include(TodoListSchema.id)
        includeToMany(TodoListSchema.items, TodoItemFieldsets.order)
    }
}

object TodoItemFieldsets : FieldsetsAvailable<TodoItem>(TodoItemSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        extendsFieldset(default)
        exclude(TodoItemSchema.listId)
    }

    val order by defineFieldset {
        include(TodoItemSchema.id, TodoItemSchema.order)
    }
}
