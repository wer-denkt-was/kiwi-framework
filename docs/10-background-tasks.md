# 10. Background Tasks - Kiwi Server

Some use-cases take too long to complete within an HTTP request: Exporting large reports, sending hundreds of e-mails,
importing files, or recalculating statistics. The `kiwi-background-tasks` module runs such work as _background
tasks_: A request launches the task and returns immediately, a _worker_ executes the task, possibly on another
application instance, and clients follow its progress via messages, until the task is completed, has failed, or has
been canceled.

This chapter explains how background tasks work, how to set up the module, how to implement task handlers, and how to
make long-running tasks observable, cancelable and resumable.

## Overview

A background task consists of the following parts:

| Part                      | Provided by | Role                                                                   |
|---------------------------|-------------|------------------------------------------------------------------------|
| `BackgroundTaskRequest`   | Application | Serializable description of the work to do, e.g. `ExportTodoList(id)`  |
| `BackgroundTaskHandler`   | Application | Bean executing requests of one type, like a command handler            |
| `BackgroundTaskService`   | Module      | Launches, looks up, cancels and resumes tasks                          |
| `BackgroundTask` entity   | Module      | Persistent state of a task: status, progress, result or error, user    |
| `BackgroundTaskQueue`     | Module      | Distributes launched tasks to the workers, relays cancellation signals |
| `BackgroundTaskWorker`    | Module      | Executes tasks of the categories it accepts, in its own executor       |
| `BackgroundTaskMessaging` | Module      | Broadcasts status, progress and notifications of a task to its clients |
| Management endpoints      | Module      | Let administrators monitor, resume and restart tasks (Actuator)        |

The lifecycle of a task includes the following states:

- **Created**: When the task is first instantiated, before it is launched to the background-task queue
- **Launched**: When the task has successfully been launched to the background-task queue
- **Running**: When the task has been picked up by a worker and is being executed
- **RetryScheduled**: When the task fails, but the task-handler logic deems it eligible to be retried after a delay
- **Failed**: When the task is rejected by the worker, the worker shut down unexpectedly, or task-handler throws an
  Exception and the task should not be retried
- **Canceled**: When the task has been canceled before or during its execution
- **Completed**: When the task has finished execution successfully, and the task-handler logic returned a result value

Starting and running a background task involves the following steps:

1. The application calls `BackgroundTaskService.launch(request, user)`, typically in a command handler.
   The task is persisted in the caller's transaction, and enqueued once that transaction has been committed.
   The returned `BackgroundTaskDescriptor` contains the task's _handle_, a secret random UUID identifying the task.
2. The queue delivers the task to a worker accepting the request's _category_.
   The worker marks the task as running, and invokes the handler outside any transaction.
3. While the handler runs, it reports progress and sends notifications via its `BackgroundTaskContext`.
   The worker stores the progress on the task and broadcasts it to the clients subscribed to the task's handle.
4. When the handler returns, the task is completed with the handler's result value.
   If it throws, the task fails, unless the handler requests a retry (then it is enqueued again after a delay);
   if the task is canceled, the handler is interrupted.
   Either way, the worker closes the task (or schedules its retry) and broadcasts its status.

The persisted task is the source of truth: Messages are sent on a best-effort basis, and clients can always look up
the current status and result of a task by its handle.

## Setting up the Module

Add the module, and include its Liquibase changelog, which creates the tables `kiwi_background_task` and
`kiwi_background_task_data`, in your master changelog:

```kotlin
dependencies {
    // [...] other dependencies

    // Kiwi Background-Tasks feature module
    implementation("de.kiwi-framework:kiwi-background-tasks:$kiwiVersion")

    // [Optional] RabbitMQ AMQP provider, for distributing tasks to several application instances
    implementation("org.springframework.boot:spring-boot-starter-amqp")

    // [Optional] Simp messaging provider, broadcasting task messages via STOMP over WebSockets
    implementation("org.springframework.boot:spring-boot-starter-websocket")

    // [Optional] Management endpoints for monitoring and resuming tasks
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

```yaml
databaseChangeLog:
  - include: { file: "classpath:db/changelog/db.background-tasks-master.xml" }
  # [...] application changelogs
```

The background-tasks module requires Spring Data JPA, like the framework module.
Integrations with RabbitMQ, WebSockets and Spring Actuator are optional dependencies:
Their respective beans are only configured if the libraries are on the classpath.

The background-tasks module is enabled by default (`kiwi.background-task.enabled`),
and requires three configuration properties:

- **Queue provider** (`kiwi.background-task.queue.provider`, required):
    - `local` dispatches tasks to the workers of the same application instance.
    - `rabbit-mq` distributes them to the workers of all instances connected to a RabbitMQ broker.
- **Workers** (`kiwi.background-task.worker.enabled`, opt-in, default `false`):
    - Whether this instance executes tasks. An instance without workers only launches tasks.
- **Messaging provider** (`kiwi.background-task.messaging.provider`, required by workers):
    - `simp` broadcasts messages using Spring's `SimpMessagingTemplate`.

A single application instance executing its own tasks:

```yaml
kiwi:
  background-task:
    queue:
      provider: local
    worker:
      enabled: true
    messaging:
      provider: simp
```

Several instances sharing a RabbitMQ broker (configured with the usual `spring.rabbitmq.*` properties),
where the web instances launch tasks, and dedicated worker instances execute them:

```yaml
# Web server instances: launch tasks only
kiwi:
  background-task:
    queue:
      provider: rabbit-mq
---
# Worker instances: execute tasks
kiwi:
  background-task:
    queue:
      provider: rabbit-mq
    worker:
      enabled: true
    messaging:
      provider: simp
```

Like the storage provider, the queue and messaging providers have no defaults: The application fails to start if a
required provider is missing, unless it declares its own `BackgroundTaskQueue` or `BackgroundTaskMessaging` bean.

> Note: The `local` queue only dispatches tasks to the workers of its own instance.
> An instance using the local queue without workers can't launch any task (`UnknownTaskCategory`),
> and logs a warning at startup.

## Defining Background Tasks

### Requests

A request describes the work of a task. It extends `BackgroundTaskRequest`, and must be serializable as JSON by
Jackson, as it is stored with the task and sent to the worker executing it. Like commands, requests should be
`data class`es carrying serializable IDs and parameters, not managed JPA entities:

```kotlin
/** Export the items of a todo list as a CSV file */
data class ExportTodoList(
    val todoListId: TodoListId,
) : BackgroundTaskRequest()
```

The request class name is included in the JSON, so the request class must be available on the classpath of every
worker instance, and renaming or moving it breaks tasks that have been launched before.

Each request belongs to a _category_, `"default"` unless the request overrides `category`. The category selects the
queue a task is enqueued in, and thereby the workers that may execute it, e.g. to run expensive tasks on dedicated
workers:

```kotlin
/** Recalculate statistics for a given year */
data class RecalculateStatistics(
    val year: Int,
) : BackgroundTaskRequest() {
    override val category get() = "statistics"
}
```

### Handlers

A `BackgroundTaskHandler` executes the requests of its type.
Handlers are Spring beans, resolved by request type like command handlers.
Exactly one handler must exist per request type:

```kotlin
@Component
class ExportTodoListHandler(
    private val transactions: TransactionService,
    private val todoItemRepository: TodoItemRepository,
    private val fileEntityService: FileEntityService,
) : BackgroundTaskHandler<ExportTodoList> {

    override fun handle(request: ExportTodoList, context: BackgroundTaskContext) = DomainResult { record ->
        // Handlers run outside of a transaction: Access the database in short transactions
        val items = transactions.read {
            todoItemRepository.findAllByTodoListId(request.todoListId)
        }

        // Generate CSV content, store in file-entity service
        val csv = items.joinToString("\n") {
            "${it.id};${it.title};${it.completed}"
        }
        val file = transactions.write {
            record(
                fileEntityService.createFileEntity(
                    filename = "todo-list-${request.todoListId}.csv",
                    contentType = "text/csv",
                    fileResource = ByteArrayResource(csv.toByteArray()),
                    user = context.user,
                ),
            )
        }

        // The result value is stored with the task, and sent to the clients as JSON
        mapOf("fileId" to file.id)
    }

    // Handle failure: Retry up to 3 attempts in total, after 10s and 20s, then fail the task
    override fun onFailure(request: ExportTodoList, exception: Exception, context: BackgroundTaskContext) =
        DomainResult {
            context.limitRetries(maxAttempts = 3) {
                retryWithBackoff(baseDelay = Duration.ofSeconds(10))
            }
        }

    // Handle cancellation, do nothing if task has been canceled
    override fun onCancel(request: ExportTodoList, context: BackgroundTaskContext) =
        DomainResult { }
}
```

- `handle` executes the task. When it returns, the task is completed, and the value of the returned `DomainResult` is
  stored as the task's result (serialized as JSON, `Unit` and `null` become JSON `null`).
- `onFailure` is invoked if `handle` throws. Its `FailureResponse` decides whether the task fails (`noRetry()`), or is
  retried after a delay (`retry(delay)`, `context.retryWithBackoff(...)`, limited by `context.limitRetries(...)`),
  see ["Retrying Failed Tasks"](#retrying-failed-tasks).
  A failed task may still be resumed by an administrator (see ["Resuming Tasks"](#resuming-tasks)).
- `onCancel` is invoked after `handle` has been interrupted by a cancellation, or if the task was canceled before a
  worker started it (then `handle` is never invoked).

The domain events recorded in the `DomainResult`s are published on the worker's event bus when the task is closed, on
behalf of the user that launched the task, and are audit-logged like the events of commands. The user is available as
`context.user`, e.g. to check permissions or to record who created a file.

> Note: The handler runs on a worker thread without an HTTP request context, a security context or a transaction.
> Don't rely on request-scoped beans or `SecurityContextHolder`; use `context.user`, and open transactions explicitly
> using the `TransactionService`.

### The launching UserDescriptor

The user passed to `launch` is stored with the task and sent to the worker, so it must be serializable.
A `UserDescriptor` that implements `BackgroundTaskUserDescriptor` is used as-is. Other user descriptors are wrapped in a
`BackgroundTaskUserDescriptor.Delegate`, which serializes the user including its class name; this requires the user
descriptor to be (de-)serializable by Jackson. `UserDescriptor.SYSTEM` and `UserDescriptor.ANONYMOUS` are supported
out of the box.

If your user descriptor can't be serialized, e.g. because it references a Spring Security principal, register a
`BackgroundTaskUserConverter<YourUserDescriptor>` in the application's `ConversionService` (e.g. in `addFormatters` of a
`WebMvcConfigurer`), converting it to a serializable `BackgroundTaskUserDescriptor`.

> **Warning - serialization of credentials:** The user is stored with the task in the database
> (`kiwi_background_task.user_data`) and sent in every task-launch message (with RabbitMQ, possibly retained in the
> dead-letter queue). The `Delegate` excludes well-known sensitive top-level properties of your user descriptor:
> `principal`, `credentials`, `password`, `token`, `accessToken`, `refreshToken`, `idToken`, `secret`, `apiKey` and
> `authentication`. They are missing when the user is deserialized on the worker, so your user descriptor must accept
> them missing (nullable, or with a default value). Any other property is serialized as it is, including nested
> objects: If your user descriptor holds credentials, tokens, or other secrets elsewhere (e.g. in its `identity`, or
> in a nested session object), register a `BackgroundTaskUserConverter` that converts it to a
> `BackgroundTaskUserDescriptor` without them.

## Launching and Observing Tasks

### Launching a task

Launch tasks in command handlers, so launching a task is part of the command's transaction:

```kotlin
// Command responsible for launching a background task
class ExportTodoListCommand(val todoListId: TodoListId) : Command<BackgroundTaskDescriptor>()

@Component
class ExportTodoListCommandHandler(
    private val backgroundTaskService: BackgroundTaskService,
) : CommandHandler<ExportTodoListCommand, BackgroundTaskDescriptor> {

    override fun handle(command: ExportTodoListCommand, user: UserDescriptor) = DomainResult {
        user.requireAuthenticated()
        val export = ExportTodoList(command.todoListId)
        backgroundTaskService.launch(export, user)
    }
}
```

`launch` persists the task in the caller's transaction (or in a new one if there is none), and enqueues it after the
transaction has been committed. If the transaction rolls back, the task is never enqueued, so a task never runs for
changes that didn't happen. The launch fails

- immediately, with `UnknownTaskCategory`, if no queue or worker exists for the request's category,
- with an `IllegalStateException`, if the caller's transaction is read-only (e.g. in a query handler),
- on commit, with `TaskEnqueueFailed`, if the queue is unavailable (e.g. the RabbitMQ broker is down).

If enqueueing fails after the commit, the task is marked as failed, and the failure is logged.

Return the task's handle to the client, which uses it to subscribe to the task's messages and to look up its status:

```kotlin
@PostMapping("/TodoList/{id}/export")
fun exportTodoList(@PathVariable id: TodoListId, user: UserDescriptor): Map<String, UUID> {
    val task = bus.dispatch(ExportTodoListCommand(id), user)
    return mapOf("taskHandle" to task.taskHandle)
}
```

### The task handle

The task handle is a secure random UUID. Knowing the handle grants access to the task: Clients use it to look up the
task's details, to subscribe to its messages, and to cancel it.

_Treat it like a secret_: Hand it out only to the launching client, and don't log it in places accessible to other
users. The module itself keeps the handle out of its domain events, error arguments and the management endpoint.

> Note: `BackgroundTaskService.cancel(taskHandle, user)` doesn't authorize the user. If knowing the handle isn't
> sufficient authorization for your use-case, check the permission in your own command handler before canceling.

### Looking up a task

`lookupDetails(taskHandle)` returns the `BackgroundTaskDetails` of a task: its status, progress, attempt, result or
error, and timestamps. Expose it in an endpoint of your application, so clients can poll it, or catch up after a
reconnect:

```kotlin
@GetMapping("/background-task/{taskHandle}")
fun backgroundTask(@PathVariable taskHandle: UUID): ResponseEntity<BackgroundTaskDetails> {
    val details = backgroundTaskService.lookupDetails(taskHandle)
    return ResponseEntity.ofNullable(details)
}
```

### Messages

The worker executing a task broadcasts messages about it. With the `simp` messaging provider, messages are sent to the
topic `/topic/backgroundTask.<taskHandle>` by default (the prefix is configured by
`kiwi.background-task.messaging.simp.message-topic`), e.g. to browser clients subscribed via STOMP over WebSockets.
This requires a message broker configured by the application, e.g. with `@EnableWebSocketMessageBroker` and a simple
broker for `/topic`.

Each message is a JSON object with the `taskHandle` and a `messageType`:

| `messageType`    | Sent                                     | Properties                              |
|------------------|------------------------------------------|-----------------------------------------|
| `Started`        | A worker started executing the task      | `status`, `attempt`                     |
| `Progress`       | The progress changed (at a limited rate) | `progress` { `percent`, `stage` }       |
| `Notification`   | The handler sent a notification          | `message`, `arguments`                  |
| `Completed`      | The task has been completed              | `status`, `completionResult`            |
| `Failed`         | The task has failed                      | `status`, `error`                       |
| `RetryScheduled` | The task failed, and waits for its retry | `status`, `error`, `attempt`, `retryOn` |
| `Canceled`       | The task has been canceled               | `status`                                |
| _custom type_    | The handler sent a custom message        | `details`                               |

Example progress message a client may receive:

```json
{
  "taskHandle": "7c1d5c52-0f36-4f4b-9a63-6a3b3b0f1f36",
  "messageType": "Progress",
  "progress": {
    "percent": 42,
    "stage": {
      "name": "send",
      "index": 1,
      "count": 3,
      "completedItems": 4,
      "totalItems": 10
    }
  }
}
```

Messages are best-effort: A client subscribing after a message has been sent doesn't receive it, and messages may be
lost or arrive in a different order (e.g. a final `Progress` after a `Notification`). Clients should subscribe first,
then look up the task's details, and treat the status messages and the details as the source of truth.

Messages are sent by the worker, so the messaging provider is only required on instances with workers. With several
instances, the message broker must deliver messages to the clients connected to any instance, e.g. using a broker
relay (`enableStompBrokerRelay`) instead of the simple in-memory broker.

To use another transport, declare a `BackgroundTaskMessaging` bean.

### Canceling a task

`BackgroundTaskService.cancel(taskHandle, user)` cancels a task:

- A task that hasn't been started yet is canceled immediately;
  when a worker picks it up, it invokes `onCancel` instead of `handle`.
- A task waiting for its retry is canceled immediately as well. `onCancel` is not invoked (`onFailure` has been invoked
  for the failed attempt), and the `Canceled` message is broadcast when the retry would have been due.
- A running task is marked as canceled, and the worker executing it is signaled:
  It interrupts the handler's thread, invokes `onCancel` once the handler has stopped, and broadcasts `Canceled`.
- A closed task can't be canceled anymore.

`cancel` returns `true` if the worker executing the task received the signal.
If the signal is lost (e.g. the worker's instance is unreachable), the worker still notices the cancellation within
a third of the lease duration (see ["Leases"](#leases)).

On the JVM, interrupts are a "gentle" signal that indicate to the Thread that it should not keep going with its current
task, but doesn't strictly force a running thread to halt immediately. Instead, cancellation is _cooperative_:
The task handler must take care to stop when it's signaled. Blocking operations like `Thread.sleep`, I/O operations, and
most database drivers react to the Thread interrupt by throwing an `InterruptedException` (or a related exception),
which the handler should let propagate so the worker can properly cancel the background task.

In CPU-bound loops that don't involve blocking I/O or database operations,
the task handler itself should check for cancellation regularly to stop execution when the task should be canceled:

```kotlin
for (row in rows) {
    context.checkActive() // throws an InterruptedException if the task was canceled or aborted
    processRow(row) // proceed with processing if task has not been canceled or aborted
}
```

> Note: For more information on JVM Thread signaling and `Thread.interrupt()`/`Thread.interrupted()`
> and interrupt exceptions,
> see [Interrupts (The Java™ Tutorials)](https://docs.oracle.com/javase/tutorial/essential/concurrency/interrupt.html).

## The Task Context

The `BackgroundTaskContext` passed to the handler methods connects the handler with its task:

| Member                                 | Purpose                                                                                    |
|----------------------------------------|--------------------------------------------------------------------------------------------|
| `taskHandle`, `request`, `user`        | Identity of the task, its request, and the user that launched it                           |
| `attempt`, `isResumed`                 | Number of the execution attempt, `1` for the first one (see ["Resuming"](#resuming-tasks)) |
| `status`                               | `Running`, `CancelRequested`, or `AbortRequested` (incurs no database access)              |
| `checkActive()`                        | Cancellation point: throws an `InterruptedException` unless still running                  |
| `reportProgress(percent)`              | Reports the overall progress, `0` to `100`                                                 |
| `sendNotification(message, arguments)` | Sends a `Notification` message, e.g. a message code for localization                       |
| `sendMessage(messageType, details)`    | Sends a custom message, with `details` serialized as JSON                                  |
| `scratchpad`                           | Key-value store surviving failures, see ["Scratchpad"](#scratchpad)                        |
| `stages { ... }`                       | Runs the handler in stages, see ["Stages and Work Items"](#stages-and-work-items)          |

`status` is `CancelRequested` once the task has been canceled, and `AbortRequested` if the execution has been aborted
(the worker is shutting down, or the task has been resumed by another execution). Handlers that can't be interrupted,
e.g. because they wait on a non-interruptible API, can poll `status` to stop early:

```kotlin
// Tight CPU-bound loop doing work
while (hasMoreWork()) {
    // Terminate loop if the task was canceled/aborted
    if (!context.status.isActive) break

    // Proceed with work
    doSomeWork()
}

// [...] optional post-loop handling/cleanup

// Throw an InterruptedException if the task was canceled/aborted
context.checkActive()
```

Reporting progress is cheap and never blocks: The latest progress is stored on the task and broadcast at most once per
`kiwi.background-task.worker.progress-interval` (1 second by default), by a supervisor thread of the execution.
Report progress as often as you like, e.g. after every processed item. A `Progress` message is only sent if the
percentage changed.

Notifications and custom messages are sent immediately, and are not stored.

## Stages and Work Items

Long tasks often consist of steps of different duration: Gathering data, processing many items, and summarizing.
The stages DSL divides a handler into named _stages_, and derives the task's progress from the stages and their work
items. It also stores _checkpoints_ in the task scratchpad, so a resumed task skips completed stages and items
(see ["Resuming Tasks"](#resuming-tasks)).

The following example handler sends reminder e-mails for the overdue items of a todo list:

```kotlin
// Declare background task request for sending reminder e-mails
data class SendReminders(val todoListId: TodoListId) : BackgroundTaskRequest()

@Component
class SendRemindersHandler(
    private val transactions: TransactionService,
    private val todoItemRepository: TodoItemRepository,
    private val reminderMailer: ReminderMailer,
) : BackgroundTaskHandler<SendReminders> {

    /** Serializable result of the "gather" stage */
    data class ReminderItem(val itemId: TodoItemId, val assignee: String)

    override fun handle(request: SendReminders, context: BackgroundTaskContext) = context.stages {
        // Stage 1: Gather the overdue items; its result is stored, and restored when the task is resumed
        val overdueItems = stage("gather", skippable = true, quota = 1) {
            transactions.read {
                todoItemRepository
                    .findOverdueItems(request.todoListId)
                    .map { ReminderItem(it.id, it.assignee) }
            }
        }

        // Stage 2: Send one e-mail per item; sent e-mails are skipped when the task is resumed
        val sentCount = stage("send", skippable = true, quota = 8) { stage ->
            stage.forEachItem(overdueItems.value, itemKey = { it.itemId.toString() }) { item ->
                reminderMailer.sendReminder(item.itemId, item.assignee)
            }
            overdueItems.value.size
        }

        // Stage 3: Record the result; the result of the last stage is the result of the task
        stage("summarize", skippable = false, quota = 1) { stage ->
            stage.record(RemindersSent(request.todoListId, sentCount.value))
            mapOf("sent" to sentCount.value)
        }
    }

    override fun onFailure(request: SendReminders, exception: Exception, context: BackgroundTaskContext) =
        DomainResult { BackgroundTaskHandler.noRetry() }

    override fun onCancel(request: SendReminders, context: BackgroundTaskContext) =
        DomainResult { }
}
```

### How stages are executed

The block passed to `stages` is invoked **twice**:

1. **Planning**:
    - The `stage` calls are recorded, but not executed.
    - This way, the total progress is known before the first stage runs.
    - The returned stage results are placeholders, and trying to access their `.value` will throw an Exception.
2. **Execution**:
    - The stages are executed in order.
    - The result of a stage is available to the stages declared after it, using `.value`.

This has consequences for the code in the block:

- The block must _only_ declare stages: Code outside the stage lambdas runs _twice_.
  Put side effects, logging and database access inside the stage execution blocks.
- Access stage results only inside later stages. Accessing `value` outside a stage lambda fails with an
  `IllegalStateException` explaining the problem.
- The declared stages must not depend on stage results or mutable state (e.g. declared conditionally):
  The executed stages _must_ match the planned stages, or the task fails.
- The `stages` declaration block returns the result of a stage, usually the last one.
  Its `value` is the result value of the task.

### Stage execution blocks

Each `stage(...) { ... }` is declared with an execution block; a lambda executed on the second execution pass of the
`stages` declaration. The `BackgroundTaskStage` parameter passed to the stage execution block, typically with the
parameter name `stage`, provides functionality to manage and interact with the execution stage:

| Member                                | Purpose                                                                              |
|---------------------------------------|--------------------------------------------------------------------------------------|
| `name`                                | Name of the execution stage                                                          |
| `index`                               | Index of the execution stage within `stages { ... }`                                 |
| `context`                             | Reference to the task's `BackgroundTaskContext`                                      |
| `totalItems(count: Int)`              | Set the total number of work items of this stage, making its progress determinate    |
| `itemsCompleted(count: Int = 1)`      | Record the completion of the given number of work items                              |
| `fractionCompleted(fraction: Double)` | Report the progress of a stage without items as a fraction (`0.0` to `1.0`)          |
| `mapEachItem(...)`                    | Map items and report progress, see ["Quotas and progress"](#quotas-and-progress)     |
| `forEachItem(....)`                   | Iterate items and report progress, see ["Quotas and progress"](#quotas-and-progress) |
| `record(event: DomainEvent)`          | Record a domain event, published when the stage is completed                         |
| `record(result: DomainResult<R>): R`  | Record the events of the given `DomainResult`, returning its value                   |

### Quotas and progress

The `quota` of a stage (default `1`) is its share of the total progress, relative to the other stages:
In the example above, sending e-mails accounts for 8 of 10 parts.

Within a stage, progress is reported via the `BackgroundTaskStage`:

| Method                                       | Progress of the stage                                             |
|----------------------------------------------|-------------------------------------------------------------------|
| `forEachItem(items, itemKey) { ... }`        | Processes the items, with a checkpoint after each item            |
| `mapEachItem(items, itemKey) { ... }`        | Like `forEachItem`, returning the mapped values (stored per item) |
| `totalItems(count)`, `itemsCompleted(count)` | Manual item counting, without checkpoints                         |
| `fractionCompleted(fraction)`                | For stages without discrete items, `0.0` to `1.0`                 |

A stage reporting none of these is indeterminate until it is completed.
The reported `BackgroundTaskProgress` includes the current stage (name, index, number of stages,
completed items, and total items), so clients can display e.g. "Sending e-mails (4/10)".

`forEachItem` and `mapEachItem` add the items to the stage's total items, skip the items completed by a previous
attempt, check for cancellation before each item, and store a checkpoint after each processed item. Item keys must be
unique within the stage (a duplicate key fails the stage before any item is processed), and should be stable across
attempts, e.g. entity IDs.

### Skippable stages and results

A stage declared as `skippable` stores its result in the task [scratchpad](#scratchpad) when it is completed, and is
skipped on later attempts, restoring its stored result instead. Stage results of skippable stages must therefore be
serializable as JSON; their type is inferred from the stage lambda (generic types like `List<ReminderItem>` work).
Declare stages as skippable if repeating them is expensive or has side effects, and as non-skippable if they produce
something that can't be stored, like an open connection, or if their result must be fresh.

Item checkpoints of `forEachItem` and `mapEachItem` are stored regardless.

### Events of stages

Domain events recorded by a stage (`stage.record(event)`) are published when the stage is completed, in the same
transaction that stores its result checkpoint. The events of a completed stage are published exactly once, even if
the task is resumed later. The events of a stage that fails are discarded. The `DomainResult` returned by `stages`
contains no events, as they have been published already.

> **Don't record events per item in `forEachItem` or `mapEachItem`.** Each item writes ites checkpoint when it has been
> processed, but the events recorded for it are only published when the whole stage completes. If the stage fails
> after some items, their events are discarded, while the items are skipped when the task is resumed: Their events are
> **never** published. Record events once per stage instead (e.g. a summary event after the iteration, as
> `RemindersSent` in the example above), or publish an item's events in the same transaction as its side effect inside
> the item action, e.g. by executing a command.

## Scratchpad

The scratchpad is a key-value store of the task, for data that must survive a failed or interrupted execution.
It is the foundation of the stage checkpoints, and can also be used directly by handlers with or without stages:

```kotlin
override fun handle(request: SendReminders, context: BackgroundTaskContext) = DomainResult {
    val items = loadOverdueItems(request.todoListId)
    for (item in items) {
        val key = "sent:${item.itemId}"
        if (key in context.scratchpad) continue // sent by a previous attempt

        context.checkActive()
        reminderMailer.sendReminder(item)
        context.scratchpad.put(key, true) // checkpoint right after the side effect
    }
}
```

- Values are serialized as JSON. Read them with `scratchpad.get<Type>(key)`.
- `put` is durable when it returns: Write a checkpoint right after the side effect it records. If the execution is
  interrupted between the side effect and the checkpoint, that single side effect is repeated when the task is resumed
  (_at-least-once_). Side effects that must not be repeated at all need an idempotency key at the receiving system.
- Keys are case-sensitive, not blank, and at most 255 characters long. By convention, stage checkpoints are stored
  with keys starting with `kiwi:stage:` (`kiwi:stage:<stage>:result`, `kiwi:stage:<stage>:item:<itemKey>`); don't use
  keys starting with `kiwi:` for your own values.
- The scratchpad is kept while the task may be retried or resumed (i.e. if it failed, or waits for its retry), and
  deleted when the task is completed or canceled.

## Retrying Failed Tasks

When `handle` throws an exception, the worker invokes the handler's `onFailure`,
whose `FailureResponse` decides how to proceed:

| `FailureResponse`                                         | Effect                                                   |
|-----------------------------------------------------------|----------------------------------------------------------|
| `noRetry()`                                               | The task fails (it may still be resumed)                 |
| `retry(delay)`, `retryInSeconds(seconds)`                 | The task is retried after the delay                      |
| `context.retryWithBackoff(baseDelay, exponent, maxDelay)` | Retried after `baseDelay * exponent^(attempt - 1)`       |
| `context.limitRetries(maxAttempts) { ... }`               | `noRetry()` after `maxAttempts` attempts, else the block |

```kotlin
override fun onFailure(request: SendReminders, exception: Exception, context: BackgroundTaskContext) =
    DomainResult {
        when (exception) {
            // Transient error: The mail server may be back soon
            // -> Retries after 30s, 1m, 2m, 4m, then the task fails
            is MailServerUnavailable -> context.limitRetries(maxAttempts = 5) {
                retryWithBackoff(
                    baseDelay = Duration.ofSeconds(30),
                    maxDelay = Duration.ofMinutes(10),
                )
            }
            // Unrecoverable error: Retrying won't help
            else -> BackgroundTaskHandler.noRetry()
        }
    }
```

A task to be retried waits in the status `RetryScheduled`: It is pending again, keeps the error of the failed attempt
(in its details and the `RetryScheduled` message), and records when the retry is due (`retryOn`). The queue enqueues
it again, and once the delay has passed, a worker starts it as its next _attempt_. The retry works exactly like
resuming (see below): The scratchpad and the stage checkpoints of the failed attempt are retained, so completed work is
skipped, and the progress doesn't drop. The events recorded by `onFailure` are published when the retry is scheduled.

- **Delays are minimums**: A due retry waits for a free worker. Delays are capped by
  `kiwi.background-task.worker.max-retry-delay` (default 1 day).
- **Retries are at-least-once**: If a worker crashes right after the failure, the task may be retried before its delay
  (and after resuming a task waiting for its retry, see ["Resuming Tasks"](#resuming-tasks)). If a retry is lost (the
  local queue's process stops, or an instance crashes before publishing the retry to RabbitMQ), the task keeps waiting:
  The module doesn't recover it automatically. Such tasks are revealed by `oldestRetryOn` in the statistics (alert on
  it, see ["Statistics"](#statistics)), and can be resumed.
- **Limit the attempts**: The module doesn't inherently limit retries: A handler that always returns `retry()` retries
  forever. Prefer `limitRetries` with `maxAttempts`, and retry only failures that are transient.
- `onFailure` is also invoked when a worker aborts a task on shutdown (with a `BackgroundTaskWorkerShutdown`
  exception): Requesting a retry lets another worker continue the task.
- No retry happens if `onFailure` itself throws, for cancellations, or if the task was resumed concurrently.
- `context.attempt` counts all attempts, retries as well as resumes: A task resumed by an administrator has fewer
  retries left.

## Resuming Tasks

A task that failed, e.g. because an external system was unavailable, or whose worker crashed, can be _resumed_:
It is enqueued again, and executed as a new _attempt_, with its progress and scratchpad retained.

Resumable are:

- **Failed** tasks: The handler threw, the worker was shut down while the task was running (after the shutdown
  timeout), or the task was rejected before it could be started (e.g. its request couldn't be deserialized).
- Tasks waiting for their **retry**: Resuming retries them now, e.g. if the retry has been lost. The pending retry is
  still delivered when it is due, and skipped unless the task waits for a retry again by then (then the next attempt
  starts early). If the task has been canceled before it started again, clients may receive the `Canceled` message
  twice.
- **Pending** tasks (`Created`, `Launched`) whose launch has been lost: The instance crashed between persisting and
  enqueueing the task, the local queue's process stopped, or the broker dropped the launch message (e.g. without
  publisher confirms, or dead-lettered by the delivery limit). If the launch message still exists, the task is
  enqueued twice: It is executed once, the other delivery is skipped.
- **Running** tasks whose **lease expired**: The worker executing the task stopped renewing its lease, presumably
  because it crashed or lost its database connection.

Resuming is an administrative operation, triggered via the management endpoint (see
["Management Endpoints"](#management-endpoints)) or `BackgroundTaskService.resume(taskHandle)`. There is no automatic
takeover of orphaned tasks: Monitoring detects them as _stalled_ (see ["Statistics"](#statistics)), and administrators
list and resume them. Failed tasks are retried automatically only if their handler requests it (see
["Retrying Failed Tasks"](#retrying-failed-tasks)). A resumed task returns to `Created`, is enqueued on behalf of the
user that launched it, and the next worker starting it increments its `attempt`.

If the retained work can't be trusted anymore, e.g. because the data the checkpoints refer to has been fixed manually,
restart the task from a clean slate instead: `resume(taskHandle, discardState = true)`, or the `restart`
operation of the management endpoint, deletes the task's scratchpad (including all stage checkpoints) and resets its
progress. The next attempt repeats all work, including the side effects of previous attempts.

Handlers support resuming by not repeating work that has been done before:

- With stages, completed skippable stages and completed items are skipped automatically.
- Without stages, use the scratchpad to record completed side effects.
- `context.attempt` and `context.isResumed` tell the handler whether it runs again, e.g. for logging.

The progress of a resumed task doesn't drop: With stages, the reported progress starts at the progress of the previous
attempt, and skipped stages aren't reported.

### Leases

While a worker executes a task, it holds a _lease_ on the task: The task records the worker's name
(`<pid>@<host>/<bean>`) and the lease's expiry, and the worker renews the lease every third of
`kiwi.background-task.worker.lease-duration` (default 1 minute). A running task whose lease expired is listed as
stalled by the management endpoint, and can be resumed.

The lease also protects against two executions of the same task: An execution that doesn't own the task anymore,
because the task has been resumed in the meantime, can't change the task, its scratchpad, or close it. It notices this
when renewing its lease or writing a checkpoint, and aborts. Until then, both executions may run concurrently, so
choose a lease duration well above the pauses to expect, like garbage collection or short database outages.

## Workers

Each application instance with `kiwi.background-task.worker.enabled` runs one or more workers.
A worker executes tasks in its own executor, and accepts tasks of configurable categories:

```yaml
kiwi:
  background-task:
    worker:
      enabled: true
      shutdown-timeout: 30s
      instances:
        # Worker for all categories except "statistics", 4 tasks at once on virtual threads
        - thread-mode: virtual-threads
          max-concurrent-tasks: 4
          exclude-categories: [ "statistics" ]
        # Worker for the "statistics" category, one task at a time
        - thread-mode: single-thread
          include-categories: [ "statistics" ]
```

Without `instances`, a single worker executing one task at a time for all categories is created. The thread modes:

| Thread mode          | Executor                                                 | Concurrent tasks       |
|----------------------|----------------------------------------------------------|------------------------|
| `single-thread`      | A single thread (default)                                | 1                      |
| `fixed-thread-pool`  | A fixed pool of `max-concurrent-tasks` threads           | `max-concurrent-tasks` |
| `cached-thread-pool` | Threads created on demand, terminated after idling 1 min | `max-concurrent-tasks` |
| `virtual-threads`    | A virtual thread per running task                        | `max-concurrent-tasks` |

A worker never executes more than `max-concurrent-tasks` tasks at once (default `1`): Further tasks assigned to the
worker wait in its executor until a running task is finished. With RabbitMQ, `max-concurrent-tasks` is also the
prefetch count, i.e. the number of pending tasks of a category the broker assigns to the worker at once, so other
workers can pick up the remaining tasks. Category patterns support `*` wildcards and are case-insensitive.

Choose `max-concurrent-tasks` by the resources the tasks share, not by the threads: A running task holds a database
connection while it accesses the database, and its supervisor syncs its progress and lease regularly, so the concurrent
tasks of all workers of an instance should stay well below the size of the connection pool. Virtual threads make
waiting for I/O cheap, but they don't make the database, a mail server, or the CPU scale.

When the application stops, the workers are unregistered from the queue first, so they don't receive new tasks, after
the web server has stopped (so requests still being processed can launch tasks). Running tasks may finish within the
`shutdown-timeout`; tasks still running afterward are aborted: Their handler is interrupted, and the task fails with
`background-task-worker-shutdown`, so it can be resumed (or it is retried, if `onFailure` requests a retry).
Tasks that were delivered to the worker, but not started yet, fail as rejected.

## Queue Providers

### Local queue

The local queue (`kiwi.background-task.queue.provider=local`) dispatches each task to a random local worker accepting
its category. It doesn't need any infrastructure, and suits single-instance deployments and tests. Pending tasks are
kept in the worker's executor, not persisted in the queue: If the process crashes, tasks that haven't been started stay
`Launched`, and running tasks stay `Running` until their lease expires: Both are listed as stalled by the management
endpoint, and can be resumed. Retries are scheduled in memory, and enqueued to a random worker accepting the task's
category when
they are due: Retries still scheduled when the process stops are lost, their tasks stay `RetryScheduled` (so they can
be resumed).

### RabbitMQ queue

The RabbitMQ queue (`kiwi.background-task.queue.provider=rabbit-mq`) requires `spring-boot-starter-amqp` and an
`org.springframework.amqp.rabbit.connection.ConnectionFactory`, configured by Spring Boot from `spring.rabbitmq.*`.

**RabbitMQ 4.4** or later is _required_, which supports delayed messages in quorum queues: The application fails to
start if the broker announces an older version (and logs a warning if the version is unknown).

It declares:

- a direct exchange (default `background_tasks`),
- a task-launch _quorum_ queue per category (default `launch_task.<category>`),
  consumed by the workers accepting the category,
- an exclusive worker-control queue per instance (default `worker_control.<random>`), bound to the handles of the tasks
  executed by the instance's workers, receiving cancellation signals,
- a dead-letter exchange and queue (default `background_tasks_dlx`, `launch_task_dlq`),
  retaining rejected task-launch messages.

A task-launch message is acknowledged when the task has been executed: If an instance crashes before starting a task
it received, the task is redelivered to another worker. A task that had been started is skipped when it is
redelivered; it is recovered by resuming it once its lease has expired. The categories are configured in
`kiwi.background-task.categories`; without configuration, only the `default` category is declared:

```yaml
spring:
  rabbitmq:
    host: rabbitmq.example.com
    # Confirm task-launch messages, so launching fails if the broker rejects them
    publisher-confirm-type: correlated
    publisher-returns: true

kiwi:
  background-task:
    queue:
      provider: rabbit-mq
    categories:
      default: { }
      statistics: { max-queue-length: 100 }
```

The task-launch queues are durable quorum queues. If a task-launch queue already exists with another type (e.g. a
classic queue) or other arguments (e.g. a changed `max-queue-length`), the application fails to start: Drain the queue
(stop launching tasks, let the workers finish them) and delete it, so it is redeclared.

With publisher confirms enabled, launching a task fails if the broker doesn't confirm the message within
`kiwi.background-task.queue.rabbit-mq.publish-confirm-timeout`, or rejects it because the category's queue is full
(`max-queue-length`; a quorum queue rejects messages once it _exceeds_ its maximum length, so it holds one more
message). Without publisher confirms, a rejected message is lost silently.

Only messages of tasks that couldn't be _launched_ are dead-lettered: The message can't be read (e.g. its request
class is unknown to the worker), the worker rejects the task, or it fails to start it (e.g. no handler is found).
The task is marked as failed first, so it can be resumed once the problem has been fixed. A task that fails during its
execution (its handler throws, or the worker shuts down) is acknowledged as usual, as the failure is recorded with
the task. **Nothing consumes the dead-letter queue**: Administration is responsible for monitoring it,
and it should be purged regularly.

A retry is published to the category's task-launch queue as a delayed message, before the launch message of the
failed attempt is acknowledged: Its header `x-opt-delivery-time` holds the time the retry is due (epoch milliseconds,
by the clock of the application instance, so a clock skew to the broker shifts the delay).
The broker delivers it once it is due, regardless of other delayed messages in the queue. Waiting retries count toward
the queue's `max-queue-length`: If the retry can't be published (e.g. the queue is full), the task fails (rejected with
`retry-failed`), so it can be resumed.

Quorum queues limit redeliveries (`x-delivery-limit`, by default 20 since RabbitMQ 4.0): A launch message returned to
the queue more often (e.g. requeued repeatedly by crashing or stopping workers) is dead-lettered, without failing its
task. The task stays `Launched` (or `Running` until its lease expires): It is listed as stalled by the management
endpoint, and can be resumed.

When the connection to the broker is re-established (e.g. after a network outage or a broker restart), the queue
redeclares its exchanges, queues and bindings, including the exclusive worker-control queue (deleted by the broker
with its connection) and the task-control bindings of the tasks still running, so they can still be canceled.
Unacknowledged task-launch messages are requeued by the broker when the connection is lost, and redelivered; tasks
that have been started already are skipped.

## Management Endpoints

With Spring Boot Actuator on the classpath, the module provides management endpoints:

- `backgroundTasks` for monitoring, resuming and restarting tasks
- `backgroundTaskStats` for statistics about tasks

Management endpoints must be exposed explicitly:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: [ health, backgroundTasks, backgroundTaskStats, ... ]
```

Operations of `backgroundTasks` include:

| Request                                            | Operation                                                                 |
|----------------------------------------------------|---------------------------------------------------------------------------|
| `GET /actuator/backgroundTasks`                    | Lists the tasks not closed yet, and the failed tasks                      |
| `GET /actuator/backgroundTasks?status=Running,...` | Lists the tasks in the given statuses                                     |
| `GET /actuator/backgroundTasks?stalled=true`       | Lists the stalled tasks (presumably orphaned, see below)                  |
| `GET /actuator/backgroundTasks/{id}`               | Shows a task, including its scratchpad keys                               |
| `POST /actuator/backgroundTasks/{id}/resume`       | Resumes a failed, pending, retry-scheduled, or stalled task               |
| `POST /actuator/backgroundTasks/{id}/restart`      | Restarts a failed, pending, retry-scheduled, or stalled task from scratch |

A task is _stalled_ if it doesn't progress, i.e. it is presumably orphaned:

- it has been pending (`Created`, `Launched`) for longer than `kiwi.background-task.management.stalled-after`
  (default 10 minutes; since it has been launched or resumed),
- its retry is overdue by more than `stalled-after`, or
- it is running, but its worker's lease expired.

A stalled pending task may also just wait behind a long queue: Resuming it is safe nonetheless (it is executed once),
but choose `stalled-after` above the usual waiting time of your tasks.

Tasks are listed latest first, at most `limit` tasks (default 100, at most 1000). Tasks are identified by their
internal ID, _not_ by their secret handle. Each task lists its status, attempt, progress, worker and lease, whether it
is stalled or resumable, the launching user, its timestamps (including since when it is pending, and when its retry is
due) and its error. Failed operations
respond with `400` (invalid request),
`404` (unknown task) or `409` (e.g. the task isn't resumable), with a JSON body containing the `error` code.

> Note: The endpoints allow administrative operations, and must be secured. Use the Kiwi management security
> (see [Chapter 5: "Management endpoint security"](./05-infrastructure-integration.md#management-endpoint-security)),
> or your own Spring Security configuration for the actuator endpoints.

For example, a typical recovery after an incident may look like:

```bash
# Find stalled tasks, e.g. whose worker crashed, or whose launch has been lost
curl -u management:secret "https://example.com/actuator/backgroundTasks?stalled=true"

# Inspect a failed task, its error and its checkpoints
curl -u management:secret "https://example.com/actuator/backgroundTasks/123456789"

# Resume it
curl -u management:secret -X POST "https://example.com/actuator/backgroundTasks/123456789/resume"

# Or restart it from a clean slate, if its checkpoints are outdated
curl -u management:secret -X POST "https://example.com/actuator/backgroundTasks/123456789/restart"
```

### Statistics

`GET /actuator/backgroundTaskStats` either returns statistics of all tasks, or of the tasks of a recent period,
for monitoring systems polling the endpoint. The period can be set with `?period`
(e.g. `?period=15m` or `?period=PT15M`, at most 31 days):

```yaml
# GET /actuator/backgroundTaskStats
{
  "now": "2026-11-11T14:00:00",
  "status":
    {
      "Created": 0,
      "Launched": 2,
      "Running": 4,
      "RetryScheduled": 1,
      "Completed": 1250,
      "Failed": 7,
      "Canceled": 12,
    },
  "pending": 2,
  "oldestPendingSince": "2026-11-11T13:59:41",
  "oldestRetryOn": "2026-11-11T14:02:30",
  "stalled": 0,
}
---
# GET /actuator/backgroundTaskStats?period=1h
{
  "now": "2026-11-11T14:00:00",
  "duration": "PT1H",
  "since": "2026-11-11T13:00:00",
  "created": 85,
  "started": 83,
  "completed": 79,
  "failed": 3,
  "failedUnstarted": 1,
  "canceled": 1,
  "pendingSincePeriodStart": 0,
  "failedByRequestClass": { "com.example.todo.SendReminders": 3 },
}
```

| Figure                    | Meaning                                                      | Alert when ...                   |
|---------------------------|--------------------------------------------------------------|----------------------------------|
| `failed`                  | Tasks failed in the period                                   | it grows relative to `completed` |
| `failedUnstarted`         | Tasks failed without being started: not enqueued or rejected | above `0`                        |
| `failedByRequestClass`    | Tasks failed in the period, per request class                | one request class stands out     |
| `pending`                 | Tasks waiting for a worker (`Created`, `Launched`)           | it keeps growing                 |
| `oldestPendingSince`      | Since when the longest waiting task waits (launch or resume) | older than a few minutes         |
| `pendingSincePeriodStart` | Tasks waiting for a worker for longer than the period        | above `0`                        |
| `oldestRetryOn`           | Earliest due retry of the tasks waiting for their retry      | due more than a few minutes ago  |
| `stalled`                 | Stalled tasks, presumably orphaned (list `?stalled=true`)    | above `0`                        |

Tasks are never deleted, so stats `status` counts cover all tasks ever launched. The figures of the period count the
tasks created, started, or closed (completed, failed, canceled) within it; a resumed task counts again when it is
started or closed again. Times are local date-times of the application's clock, like the timestamps of the tasks.

## Errors

When a task fails, its error is stored with the task and sent in the `Failed` (or `RetryScheduled`) message as a
`BackgroundTaskError`, which
matches the error responses of the Kiwi web layer (`message`, `arguments`, `errorType`):

- `DomainException`s are rendered with their code and arguments, e.g. `invalid-background-task-status`.
- Well-known general exceptions (and their subclasses) are rendered with an error code and their message as `reason`:
    - `IllegalArgumentException` (`illegal-argument`)
    - `IllegalStateException` (`illegal-state`)
    - `UnsupportedOperationException` (`unsupported-operation`)
    - `NoSuchElementException` (`no-such-element`)
    - `TimeoutException` (`timeout`)
- Other exceptions are rendered as `unknown`, with only their simple class name as the argument `type`, so internals
  like SQL statements or file paths are not disclosed to clients.

Throw `DomainException`s for failures that clients should understand and localize. The complete exception, including
its message, is recorded in the `BackgroundTaskFailed` event, i.e. in the audit log and the application log.

The module records the domain events `BackgroundTaskCreated`, `BackgroundTaskStarted`, `BackgroundTaskCompleted`,
`BackgroundTaskFailed`, `BackgroundTaskRetryScheduled`, `BackgroundTaskCanceled` and `BackgroundTaskResumed`, which are
audit-logged, and can be
subscribed to by `EventSubscriber`s, e.g. to notify users by e-mail when their task has completed.

## Configuration Properties

All properties have the prefix `kiwi.background-task`:

| Property                                        | Default                | Description                                                                |
|-------------------------------------------------|------------------------|----------------------------------------------------------------------------|
| `enabled`                                       | `true`                 | Enables the module                                                         |
| `queue.provider`                                | _required_             | `local` or `rabbit-mq`, unless a `BackgroundTaskQueue` bean is declared    |
| `queue.rabbit-mq.exchange`                      | `background_tasks`     | Exchange for launching and controlling tasks                               |
| `queue.rabbit-mq.task-launch-queue`             | `launch_task`          | Name prefix of the task-launch queues, followed by `.<category>`           |
| `queue.rabbit-mq.worker-control-queue`          | `worker_control`       | Name prefix of the worker-control queues                                   |
| `queue.rabbit-mq.task-control-binding`          | `task_control`         | Routing key prefix of task-control messages                                |
| `queue.rabbit-mq.dead-letter-exchange`          | `background_tasks_dlx` | Dead-letter exchange for rejected task-launch messages                     |
| `queue.rabbit-mq.dead-letter-task-launch-queue` | `launch_task_dlq`      | Dead-letter queue                                                          |
| `queue.rabbit-mq.wait-for-reply-timeout`        | `10s`                  | Timeout for a worker to confirm a cancellation signal                      |
| `queue.rabbit-mq.publish-confirm-timeout`       | `10s`                  | Timeout for the broker to confirm a task-launch message                    |
| `categories.<name>.max-queue-length`            | unlimited              | Maximum number of pending tasks of the category                            |
| `worker.enabled`                                | `false`                | Runs workers in this instance                                              |
| `worker.instances[N].thread-mode`               | `single-thread`        | Executor of the worker, see ["Workers"](#workers)                          |
| `worker.instances[N].max-concurrent-tasks`      | `1`                    | Maximum number of concurrent tasks (except `single-thread`)                |
| `worker.instances[N].include-categories`        | all                    | Category patterns accepted by the worker                                   |
| `worker.instances[N].exclude-categories`        | none                   | Category patterns rejected by the worker                                   |
| `worker.shutdown-timeout`                       | `30s`                  | Time for running tasks to finish when the application stops                |
| `worker.progress-interval`                      | `1s`                   | Minimum time between two progress updates of a task, `0` sends immediately |
| `worker.lease-duration`                         | `1m`                   | Lease duration of a running task, renewed every third of it                |
| `worker.max-retry-delay`                        | `1d`                   | Maximum delay of a retry requested by a handler, longer delays are capped  |
| `management.stalled-after`                      | `10m`                  | Time after which a pending task or an overdue retry is considered stalled  |
| `handlers.enable-caching`                       | `true`                 | Caches the resolved handler per request class                              |
| `messaging.provider`                            | _required_ (workers)   | `simp`, unless a `BackgroundTaskMessaging` bean is declared                |
| `messaging.simp.message-topic`                  | `backgroundTask`       | Topic prefix, messages are sent to `/topic/<prefix>.<taskHandle>`          |

## Best Practices

- **Keep requests small and stable.** Requests are stored as JSON and may be executed after a deployment:
  Pass IDs and parameters instead of entities or large payloads, and keep request classes backward compatible.
- **Launch tasks in commands.** The task is only enqueued if the command's transaction commits, and the launch is
  recorded together with the command's events.
- **Use short transactions in handlers.** A handler may run for several minutes; don't hold a transaction (and its
  database connection and locks) for the whole task. Read the data you need, process it outside a transaction, write
  results in separate transactions.
- **Make handlers cancelable.** Let `InterruptedException`s propagate, call `context.checkActive()` in loops, and keep
  `onCancel` fast.
- **Design for resuming.** Use stages or the scratchpad for side effects, write checkpoints right after each side
  effect, and use stable item keys. Assume that any single side effect may happen twice.
- **Retry transient failures only, and limit the attempts.** Use `retryWithBackoff` within `limitRetries`
  (`maxAttempts`) for failures that may pass (unavailable external systems, timeouts, etc.), and `noRetry()` for
  failures that won't (invalid data, bugs, etc.).
- **Report progress generously, notify sparingly.** Progress is throttled and cheap; notifications are sent
  immediately to every subscribed client.
- **Throw `DomainException`s** for failures that users should understand; other exceptions are reported as `unknown`
  or with their message only, if they are well-known.
- **Separate expensive work by category**, and run dedicated workers for it, so long tasks don't block short ones.
- **Keep the handle secret**, and authorize cancellation in your own command handler if knowing the handle isn't
  sufficient.
- **Secure and monitor the management endpoints**: Alert on the figures of `backgroundTaskStats` (failed, unstarted,
  long pending, overdue retries, and stalled tasks), resume stalled tasks, and monitor/purge the RabbitMQ dead-letter
  queue.

## Testing Background Tasks

### Unit tests with the in-memory context

Handlers are beans like command handlers, so most of their logic can be tested by invoking them directly.
The Kiwi API provides an `InMemoryBackgroundTaskContext` for this, which needs neither the background-tasks module,
nor workers, a queue, messaging, or a database. The handler runs synchronously in the test's thread,
and the context records what it reports:

| Member                                         | Purpose                                                                                     |
|------------------------------------------------|---------------------------------------------------------------------------------------------|
| `progress`, `progressPercents`, `lastProgress` | Progress reported by the handler or its stages, unthrottled, in order                       |
| `notifications`, `messages`                    | Notifications and custom messages sent by the handler                                       |
| `scratchpad`                                   | `InMemoryBackgroundTaskScratchpad`, storing values as JSON, including the stage checkpoints |
| `completedStages`, `publishedEvents`           | Stages completed in this attempt, and the events published by them                          |
| `cancel()`, `abort()`                          | Change the `status`, so the handler stops at its next `checkActive()`                       |
| `resumed()`                                    | Context of the next attempt: same request and scratchpad, `attempt` + 1                     |

The context behaves like the context of a worker where it matters for handlers:

- Stages are executed by the same `BackgroundTaskStagesRunner` as on a worker: The declaration block runs twice,
  checkpoints are stored in the scratchpad, completed stages are skipped by the next attempt, and the events of a stage
  are only published once it has been completed.
- The scratchpad stores values as JSON, so values (and results of skippable stages) that can't be serialized fail
  like on a worker. It uses a `JsonMapper` with the modules on the classpath (e.g. the Kotlin module) and the
  `EntityIdJsonModule`; pass `scratchpad = InMemoryBackgroundTaskScratchpad(objectMapper)` with your application's
  `ObjectMapper` if your values need its configuration.
- Calling `checkActive()` throws an `InterruptedException` once the task has been canceled or aborted.

The following JUnit tests cover the `SendRemindersHandler` from ["Stages and Work Items"](#stages-and-work-items).
The repository is mocked with [mockito-kotlin](https://github.com/mockito/mockito-kotlin), the
`TransactionService.DoNothing` of the Kiwi API runs the transaction blocks without a transaction, and a fake
`ReminderMailer` (an interface of the application) records the reminders sent, failing for selected items:

```kotlin
/** Fake mailer recording the sent reminders, failing for the items in [failFor] */
class FakeReminderMailer : ReminderMailer {
    val sent = mutableListOf<TodoItemId>()
    var failFor = emptySet<TodoItemId>()
    var onSent: () -> Unit = { }

    override fun sendReminder(itemId: TodoItemId, assignee: String) {
        if (itemId in failFor) throw IllegalStateException("mail-server-unavailable")
        sent.add(itemId)
        onSent()
    }
}

class SendRemindersHandlerTest {
    private val todoListId = TodoListId(1)
    private val overdueItems = (1L..3L).map { id ->
        TodoItem(
            id = TodoItemId(id),
            assignee = "assignee-$id@example.org",
        )
    }
    private val overdueItemIds = overdueItems.map { it.id }

    private val todoItemRepository = mock<TodoItemRepository> {
        on { findOverdueItems(todoListId) } doReturn overdueItems
    }

    private val mailer = FakeReminderMailer()
    private val handler = SendRemindersHandler(TransactionService.DoNothing, todoItemRepository, mailer)
    private val request = SendReminders(todoListId)

    @Test
    fun sendsRemindersInStages() {
        // Setup: Context of the first attempt, launched by Alice
        val context = InMemoryBackgroundTaskContext(request, user = dummyUser("Alice Doe"))

        // Execution: Invoke the handler directly, as the worker would
        val result = handler.handle(request, context)

        // Assertion: Result, side effects, stages, events and progress
        assertEquals(mapOf("sent" to 3), result.value)
        assertEquals(overdueItemIds, mailer.sent)
        assertEquals(listOf("gather", "send", "summarize"), context.completedStages)
        assertEquals(1, context.publishedEvents.filterIsInstance<RemindersSent>().size)
        assertEquals(100, context.lastProgress?.percent)
        assertEquals(context.progressPercents.sorted(), context.progressPercents) // never drops
    }

    @Test
    fun resumedTaskDoesNotSendRemindersTwice() {
        // The first attempt fails at the third item
        mailer.failFor = setOf(overdueItemIds[2])
        val firstAttempt = InMemoryBackgroundTaskContext(request)
        assertThrows<IllegalStateException> { handler.handle(request, firstAttempt) }
        assertEquals(overdueItemIds.take(2), mailer.sent)
        assertEquals(listOf("gather"), firstAttempt.completedStages)

        // The second attempt shares the scratchpad of the first one
        mailer.failFor = emptySet()
        val secondAttempt = firstAttempt.resumed()
        val result = handler.handle(request, secondAttempt)

        // Each reminder has been sent once, the gathered items have been restored instead of loaded again
        assertEquals(mapOf("sent" to 3), result.value)
        assertEquals(overdueItemIds, mailer.sent)
        assertEquals(listOf("send", "summarize"), secondAttempt.completedStages)
        verify(todoItemRepository, times(1)).findOverdueItems(todoListId)

        // The progress of the second attempt starts at the progress of the first attempt
        val failedProgress = firstAttempt.lastProgress!!.percent
        assertTrue(secondAttempt.progressPercents.all { it >= failedProgress })
    }

    @Test
    fun canceledTaskStopsSendingReminders() {
        val context = InMemoryBackgroundTaskContext(request)
        mailer.onSent = { context.cancel() } // cancel the task after the first reminder

        assertThrows<InterruptedException> { handler.handle(request, context) }
        assertEquals(overdueItemIds.take(1), mailer.sent)
    }
}
```

- `handle` returns the handler's `DomainResult`. With stages, its events are empty: The events of the stages have been
  "published" when each stage was completed, and are collected in `publishedEvents`.
- A failed attempt is simulated by letting a collaborator throw. Its context's `resumed()` provides the context of
  the next attempt, which sees the checkpoints of the failed one, like a task resumed by an administrator.
- The in-memory context doesn't invoke `onFailure` or `onCancel`, and doesn't throttle progress: Test those callbacks
  by invoking them directly, and assert the progress the handler reports, rather than the messages a client receives.
  E.g. test the retries of a handler by invoking `onFailure` with contexts of increasing attempts
  (`InMemoryBackgroundTaskContext(request, attempt = 3)`), and simulate the retry with `resumed()`.

### Integration tests

The in-memory context doesn't cover the parts provided by the module: Launching in a transaction, the queue, workers,
serialization of the request and the user, persisting progress, results and errors, and the messages sent to clients.
Test these in integration tests with the local queue and workers, and a `TestBackgroundTaskMessaging` bean that
records the broadcast messages, so tests can wait for a task to be closed:

```yaml
kiwi:
  background-task:
    queue:
      provider: local
    worker:
      enabled: true
```

```kotlin
@TestConfiguration
class BackgroundTaskTestConfiguration {
    /** Records the messages of the tasks, replacing the Simp messaging */
    @Bean
    fun testBackgroundTaskMessaging() = TestBackgroundTaskMessaging()
}
```

As tasks run in the threads of the workers, tests wait for the task to be closed before asserting its result:

```kotlin
@SpringBootTest
@Import(BackgroundTaskTestConfiguration::class)
class ExportTodoListIntegrationTest {
    @Autowired
    private lateinit var commandBus: CommandBus

    @Autowired
    private lateinit var backgroundTaskService: BackgroundTaskService

    @Autowired
    private lateinit var messaging: RecordingBackgroundTaskMessaging

    private val alice = dummyUser("Alice Doe")

    @Test
    fun exportIsCompleted() {
        val todoListId = createTodoList(alice) // test fixture

        // Launch the task in a command, like the application does
        val task = commandBus.submit(ExportTodoListCommand(todoListId), alice).value

        // Block until messaging indicates the background task has completed
        val closed = messaging.awaitClosed(Duration.ofSeconds(10), task.taskHandle).get()
        assertInstanceOf<StatusChanged.Completed>(closed)

        // Assert details of background task completion
        val details = backgroundTaskService.lookupDetails(task.taskHandle)!!
        assertEquals(BackgroundTaskStatus.Completed, details.status)
        assertEquals(100, details.progress.percent)
        assertNotNull(details.result?.get("fileId"))
    }
}
```

The module's own integration tests use the same setup with a MariaDB Testcontainer,
and run the same test cases with the local and the RabbitMQ queue.
