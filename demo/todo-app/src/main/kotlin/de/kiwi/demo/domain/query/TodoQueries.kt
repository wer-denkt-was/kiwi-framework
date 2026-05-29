package de.kiwi.demo.domain.query

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.generic.GenericEntityQuery
import de.kiwi.api.olap.AnalyticalReportQuery
import de.kiwi.demo.domain.*
import org.springframework.data.jpa.domain.Specification

class TodoListQuery : GenericEntityQuery<TodoList>() {
    override fun filterById(id: EntityId): Specification<TodoList> = baseEntityFilter(id, TodoList_.id)
    override fun customFilter(user: UserDescriptor): Specification<TodoList>? = null
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class TodoItemQuery : GenericEntityQuery<TodoItem>() {
    override fun filterById(id: EntityId): Specification<TodoItem> = baseEntityFilter(id, TodoItem_.id)
    override fun customFilter(user: UserDescriptor): Specification<TodoItem>? = null
    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}

class TodoItemReportQuery : AnalyticalReportQuery<TodoItem>() {
    override val root get() = TodoItem_.class_!!

    override val fields by defineFields {
        dimension("listId", attribute(TodoItem_.todoList, TodoList_.id))
        metric("total", count())
        metric("completed", countWhere { equal(it[TodoItem_.status], TodoItemStatus.Done) })
        metric("incomplete", countWhere { equal(it[TodoItem_.status], TodoItemStatus.Open) })
    }

    override fun preAuthorize(user: UserDescriptor) = Unit //TODO: Implement authorization
}
