package de.kiwi.backgroundTask.infrastructure.actuate

import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.api.domain.DomainException
import de.kiwi.backgroundTask.application.BackgroundTaskManagement
import de.kiwi.backgroundTask.application.BackgroundTaskManagement.TaskInfo
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.Selector
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint

/** Default number of listed tasks */
private const val DEFAULT_LIMIT = 100

/** HTTP status of operations conflicting with the task's state */
private const val STATUS_CONFLICT = 409

/**
 * Management endpoint `backgroundTasks` for monitoring and resuming background tasks:
 *
 * - `GET /actuator/backgroundTasks`: List tasks not closed yet, and failed tasks (latest first)
 * - `GET /actuator/backgroundTasks?status=Running,...`: List tasks in the given statuses
 * - `GET /actuator/backgroundTasks?stalled=true`: List stalled tasks, presumably orphaned: Pending, or their retry
 *   overdue, for longer than `kiwi.background-task.management.stalled-after`, or running with an expired lease
 * - `GET /actuator/backgroundTasks/{id}`: Details of a task, including its scratchpad keys
 * - `POST /actuator/backgroundTasks/{id}/resume`: Resume a failed, pending, retry-scheduled, or orphaned task, as a
 *   new execution attempt
 * - `POST /actuator/backgroundTasks/{id}/restart`: Resume a failed, pending, retry-scheduled, or orphaned task from a
 *   clean slate, discarding its scratchpad (including the checkpoints of its stages) and progress
 *
 * Statistics for monitoring and alerting are provided by the separate endpoint `backgroundTaskStats`
 * ([BackgroundTaskStatsEndpoint]).
 *
 * The endpoint must be exposed (`management.endpoints.web.exposure.include=backgroundTasks`), and should be secured
 * by the application, as it allows administrative operations.
 *
 * Failed operations respond with an [ErrorResponse]: `400` for invalid requests (e.g. a malformed ID or limit), `404`
 * if the task doesn't exist, and `409` if the operation is not possible (e.g. the task is not resumable).
 */
@WebEndpoint(id = "backgroundTasks")
class BackgroundTaskEndpoint(private val management: BackgroundTaskManagement) {

    /** List of tasks */
    data class TaskList(
        /** Listed tasks, the latest first */
        val tasks: List<TaskInfo>,
    )

    /** Error response of a failed operation */
    data class ErrorResponse(
        /** Error code */
        val error: String,
        /** Error message, `null` for errors of domain exceptions (identified by their code) */
        val message: String?,
    )

    /**
     * `GET /actuator/backgroundTasks`: List tasks, the latest first, see [BackgroundTaskManagement.listTasks]
     * @param status Statuses of the listed tasks (default: not closed, or failed)
     * @param stalled List the stalled tasks instead (default `false`), see [TaskInfo.stalled]
     * @param limit Maximum number of listed tasks (default `100`, at most [BackgroundTaskManagement.MAX_LIMIT])
     * @return [TaskList]
     */
    @ReadOperation
    fun listTasks(
        status: List<BackgroundTaskStatus>?,
        stalled: Boolean?,
        limit: Int?,
    ): WebEndpointResponse<*> = webResponse {
        val tasks = management.listTasks(
            statuses = status?.toSet(),
            stalled = stalled ?: false,
            limit = limit ?: DEFAULT_LIMIT,
        )
        TaskList(tasks)
    }

    /**
     * `GET /actuator/backgroundTasks/{id}`: Details of the task with the given [id], including its scratchpad keys
     * @return [TaskInfo]
     */
    @ReadOperation
    fun showTask(
        @Selector id: String,
    ): WebEndpointResponse<*> = webResponse {
        management.findTask(id) ?: notFound(id)
    }

    /**
     * `POST /actuator/backgroundTasks/{id}/{operation}`: Execute the [operation] on the task with the given [id].
     * Supported operations:
     * - `resume`: Resume the failed, pending, retry-scheduled, or orphaned task, as a new execution attempt.
     *   Responds with the task's [TaskInfo].
     * - `restart`: Resume the failed, pending, retry-scheduled, or orphaned task from a clean slate, discarding its
     *   scratchpad and progress, so the next attempt repeats all work. Responds with the task's [TaskInfo].
     */
    @WriteOperation
    fun operation(
        @Selector id: String,
        @Selector operation: String,
    ) = webResponse {
        when (operation) {
            "resume" -> management.resumeTask(id) ?: notFound(id)
            "restart" -> management.resumeTask(id, discardState = true) ?: notFound(id)
            else -> WebEndpointResponse(
                ErrorResponse("unknown-operation", "Unknown operation: $operation"),
                WebEndpointResponse.STATUS_BAD_REQUEST,
            )
        }
    }

    private fun notFound(id: String) = WebEndpointResponse(
        ErrorResponse("background-task-not-found", "BackgroundTask #$id not found"),
        WebEndpointResponse.STATUS_NOT_FOUND,
    )

    private inline fun webResponse(operation: () -> Any): WebEndpointResponse<*> = try {
        when (val result = operation()) {
            is WebEndpointResponse<*> -> @Suppress("UNCHECKED_CAST") (result as WebEndpointResponse<Any>)
            else -> WebEndpointResponse(result, WebEndpointResponse.STATUS_OK)
        }
    } catch (ex: IllegalArgumentException) {
        WebEndpointResponse(ErrorResponse("invalid-request", ex.message), WebEndpointResponse.STATUS_BAD_REQUEST)
    } catch (ex: DomainException) {
        // E.g. the task is not resumable in its current status, or its category is unknown:
        // Respond with the error code only, as the arguments may contain internals
        WebEndpointResponse(ErrorResponse(ex.code, message = null), STATUS_CONFLICT)
    }
}
