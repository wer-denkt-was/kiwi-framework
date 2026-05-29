package de.kiwi.demo.application

import de.kiwi.demo.domain.TodoList
import de.kiwi.demo.domain.TodoListId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TodoListRepository : JpaRepository<TodoList, TodoListId>
