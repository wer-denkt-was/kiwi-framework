# 5. Infrastructure and Integration - Kiwi Server

## Kiwi configuration properties

## Spring Security integration

...

To enable user authentication, add the `spring-boot-starter-security` dependency to the project `build.gradle.kts` file:

```kotlin
dependencies {
    // [...] other dependencies
    implementation("org.springframework.boot:spring-boot-starter-security")
}
```

...

### UserAccount entity

Before integrating Spring Security, the entity model requires an entity to represent the account of an authenticated
user.

For the example todo-app, declare a UserAccount entity which also implements the Spring-Security UserDetails interface.
This way, the user entity can be used to authenticate using Spring-Security's login and authentication mechanism.
Note that the Kiwi-Server specific User**Id**Details interface (not the Spring UserDetails interface) is used here,
which will later allow the UserDescriptor interface to provide a `userId` property.

```kotlin
// Entities are located in `domain` package
package com.example.domain

// Use Kiwi-Server API classes
import de.kiwi.api.domain.*
// Use JPA classes
import jakarta.persistence.*
// Use Spring-Security
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

// (1) Define a dedicated ID type for UserAccount
@Embeddable // Contains the `id` column attribute
class UserAccountId(value: Long) : EntityId(value)

// (2) Define the UserAccount entity
@Entity
@Table(name = "user_account") // Map to database table
class UserAccount(
    id: UserAccountId,

    // Columns of user_account table
    @Column(name = "username", length = 255, nullable = false) private var username: String,
    @Column(name = "password", length = 255, nullable = false) private var password: String,
) : BaseEntity<UserAccountId>(id), UserDetails { // Inherit from BaseEntity, implement Spring UserDetails
    // -> Inherits database column: id BIGINT NOT NULL PRIMARY KEY

    // Implement methods required by UserIdDetails
    override val userId get() = id.value
    override fun getUsername() = username
    override fun getPassword() = password
    override fun getAuthorities() = setOf(SimpleGrantedAuthority(Roles.USER))
}
```

Integrate the UserAccount entity into the application, adding a relation with the TodoList entity:

```kotlin
// (1) Changes in domain layer
package com.application.domain

// (1.1) Add UserAccount relation to TodoList, referencing the list author
@Entity
@Table(name = "todo_list")
class TodoList(
    id: TodoListId,

    // Add new author property,
    // maps to database column: author_id BIGINT
    @ManyToOne(optional = false)
    @JoinColumn(name = "author_id")
    val author: UserAccount?,

    @Column(name = "title", length = 255, nullable = false)
    var title: String,
) : BaseEntity<TodoListId>(id) {

    @OneToMany(mappedBy = "parentList")
    val items: MutableSet<TodoItem> = HashSet()

    companion object {
        val validTitle = TodoList::title.validateColumn() and notBlank

        // (1.2) Add author parameter to create function
        fun create(title: String, author: UserAccount?) = TodoList(
            id = EntityId.generate<TodoListId>(),
            author = author,
            title = validTitle(title),
        )
    }
}
```

Migrate the entity changes to the database using Liquibase:

```yaml
# Extend changelog with new changeSet
databaseChangeLog:
  # [...] previous changeSets
  - changeSet:
      id: add-user-account
      author: your-name-here
      changes:
        # (1) Create user_account table
        - createTable:
            tableName: user_account
            columns:
              - column:
                  name: id
                  type: bigint
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: username
                  type: varchar(255)
                  constraints:
                    nullable: false
              - column:
                  name: password
                  type: varchar(255)
                  constraints:
                    nullable: false
        # (2) Add author_id column to todo_list table
        - addColumn:
            tableName: todo_list
            columns:
              - column:
                  name: author_id
                  type: bigint
                  # Foreign key constraint, referencing user_account.id
                  constraints:
                    foreignKeyName: fk_todo_list_author_id
                    referencedTableName: user_account
                    referencedColumnNames: id
                    deleteCascade: true
```

Finally, create a UserAccount repository interface, and modify the CreateTodoList command-handler accordingly.

```kotlin
// (2) Add UserAccountRepository in application layer
package com.example.application

// (2.1) Declare UserAccount repository interface
@Repository
interface UserAccountRepository : JpaRepository<UserAccount, UserAccountId>

// (2.2) Modify the CreateTodoList command-handler to associate the current user account
@Component
class CreateTodoListHandler(
    // Autowire UserAccount repository interface
    private val userAccountRepository: UserAccountRepository,
) : GenericCreateHandler<CreateTodoList, TodoList>() {
    override fun createEntity(create: CreateBody, user: UserDescriptor) = DomainEvents<TodoList> { record ->
        // Look up UserAccount entity using given UserDescriptor
        val accountId = UserAccountId(requireNotNull(user.userId))
        val account = userAccountRepository.findById(accountId).getOrNull()

        val title = create.require(TodoListSchema.title)
        TodoList.create(title = title, author = account).also { list ->
            record(TodoListCreated(list))
        }
    }
}
```

### UserAccount AccessControl

## Rendering and sending E-Mails

### MJML Templates

## File Storage

### Local File Storage Service

### S3 File Storage Service

---

Next up, [Chapter 6: "Web MVC and Endpoints"](./06-web-mvc-endpoints.md) is about defining an HTTP API endpoints for the
application using MVC Controllers.
