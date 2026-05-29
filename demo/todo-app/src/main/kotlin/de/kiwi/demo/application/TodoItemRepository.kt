package de.kiwi.demo.application

import de.kiwi.demo.domain.TodoItem
import de.kiwi.demo.domain.TodoItemId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.stereotype.Repository

@Repository
interface TodoItemRepository : JpaRepository<TodoItem, TodoItemId>, JpaSpecificationExecutor<TodoItem>
