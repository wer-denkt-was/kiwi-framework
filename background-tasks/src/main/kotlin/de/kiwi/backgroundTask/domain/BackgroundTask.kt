package de.kiwi.backgroundTask.domain

import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.PersistableBaseEntity
import de.kiwi.backgroundTask.domain.interaction.*
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.NaturalId
import org.hibernate.type.SqlTypes
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.*

/**
 * ID of a [BackgroundTask], used internally.
 * The task is identified externally by its secret handle.
 */
@Embeddable
class BackgroundTaskId(value: Long) : EntityId(value)

/**
 * Entity representing a background task, recording its request, the user that launched it, and its lifecycle status.
 *
 * The task is identified externally by its secret, random [taskHandle], while the [id] is used internally.
 * Status transitions are guarded: a task is [created][create], [launched], [started], and finally [completed],
 * [failed] or [canceled]. A running task whose handler requested a retry after a failure is [retryScheduled], and
 * started again for its next attempt. Closed tasks can't change their status anymore, except failed tasks, which may be
 * [resumed] (as may pending tasks, e.g. if their launch message has been lost, and running tasks whose worker's lease
 * expired), returning to [Created][BackgroundTaskStatus.Created] for a new execution attempt.
 *
 * A worker executing the task holds a lease ([leaseWorkerName], [leaseExpiresOn]), which it renews while the task is
 * running. An expired lease indicates that the worker crashed or lost its connection to the database.
 * A task that doesn't progress (pending or waiting for its retry for too long, or running with an expired lease) is
 * considered ["stalled"][isStalled], e.g. it has been orphaned, and may be resumed by an administrator.
 *
 * @see BackgroundTaskStatus
 */
@Entity
@Table(name = "kiwi_background_task")
class BackgroundTask(
    id: BackgroundTaskId,

    /** Secret, secure random handle identifying the task externally */
    @NaturalId
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "handle", unique = true, nullable = false, updatable = false, length = 36)
    val taskHandle: UUID,

    /** Class name of the task's [BackgroundTaskRequest] */
    @Column(name = "request_class", nullable = false, updatable = false, length = 1024)
    val taskRequestClassName: String,

    /** The task's [BackgroundTaskRequest] as JSON, including its class name */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_data", nullable = false, updatable = false)
    val taskRequestJson: String,

    /** When the task has been launched */
    @Column(name = "created_on", nullable = false, updatable = false)
    val createdOn: LocalDateTime,

    /** ID of the authenticated user that launched the task, if any */
    @Column(name = "created_by_user_id", nullable = true, updatable = false)
    val createdByUserId: Long?,

    /** Name of the authenticated user that launched the task, if any */
    @Column(name = "created_by_username", nullable = true, updatable = false, length = 1024)
    val createdByUsername: String?,

    /** The [BackgroundTaskUserDescriptor] that launched the task as JSON, so a resumed task runs on its behalf */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_data", nullable = false, updatable = false)
    val taskUserJson: String,
) : PersistableBaseEntity<BackgroundTaskId>(id) {

    /** Current status of the task */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: BackgroundTaskStatus = BackgroundTaskStatus.Created
        protected set

    /** Last progress reported by the task handler, in percent */
    @Column(name = "progress_percent", nullable = false)
    var progressPercent: Int = 0
        protected set

    /** Last progress reported by the task handler as JSON ([BackgroundTaskProgress]), `null` if none reported */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_data", nullable = true)
    var progressJson: String? = null
        protected set

    /** Number of the latest execution attempt, `0` until the task has been started */
    @Column(name = "attempt", nullable = false)
    var attempt: Int = 0
        protected set

    /** Name of the worker executing (or last executing) the task, for monitoring */
    @Column(name = "worker_name", nullable = true, length = 1024)
    var leaseWorkerName: String? = null
        protected set

    /** Expiry of the lease of the worker executing the task, `null` unless running */
    @Column(name = "lease_expires_on", nullable = true)
    var leaseExpiresOn: LocalDateTime? = null
        protected set

    /** When a worker started executing the task (the latest attempt), `null` until started (again, if resumed) */
    @Column(name = "started_on", nullable = true)
    var startedOn: LocalDateTime? = null
        protected set

    /**  When the task became pending (created, resumed, or its retry scheduled), `null` unless status is pending */
    @Column(name = "pending_since", nullable = true)
    var pendingSince: LocalDateTime? = createdOn
        protected set

    /** When the retry of the task is due, `null` unless [RetryScheduled][BackgroundTaskStatus.RetryScheduled] */
    @Column(name = "retry_on", nullable = true)
    var retryOn: LocalDateTime? = null
        protected set

    /** When the task has been closed (completed, failed, or canceled), `null` if not closed */
    @Column(name = "completed_on", nullable = true)
    var completedOn: LocalDateTime? = null
        protected set

    /** Result value returned by the task handler as JSON, `null` unless completed (or if the result was `null`) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_data", nullable = true)
    var resultJson: String? = null
        protected set

    /**
     * Client-facing [BackgroundTaskError] as JSON, `null` unless failed (or waiting for its retry, then it is the error
     * of the failed attempt)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_data", nullable = true)
    var errorJson: String? = null
        protected set

    companion object {
        /**
         * Create a new task for the given request, serializing the [taskRequest] as JSON.
         * The creating user is recorded only if it is authenticated.
         */
        fun create(
            taskRequest: BackgroundTaskRequest,
            taskUser: BackgroundTaskUserDescriptor,
            objectMapper: ObjectMapper,
            clock: Clock,
        ) = DomainResult { record ->
            // Task handle *must* be generated as a secure, random UUID, and *must not* be guessable.
            // It must be secret, and knowing the handle enables a clients to access communication about the task!
            val taskHandle = UUID.randomUUID()
            val taskUserId = taskUser.userId?.takeIf { taskUser.authenticated }
            val taskUsername = taskUser.username.takeIf { taskUser.authenticated }
            val taskUserWriter = objectMapper.writerFor(BackgroundTaskUserDescriptor::class.java)
            val taskRequestWriter = objectMapper.writerFor(BackgroundTaskRequest::class.java)
            BackgroundTask(
                id = EntityId.generate(),
                taskHandle = taskHandle,
                taskRequestClassName = taskRequest.javaClass.name,
                taskRequestJson = taskRequestWriter.writeValueAsString(taskRequest),
                createdOn = LocalDateTime.now(clock),
                createdByUserId = taskUserId,
                createdByUsername = taskUsername,
                taskUserJson = taskUserWriter.writeValueAsString(taskUser),
            ).also { task ->
                task.markAsNew()
                record(BackgroundTaskCreated(task))
            }
        }
    }

    override fun getId() = super.id

    /** Create the [BackgroundTaskDescriptor] of this task, deserializing the request */
    fun toDescriptor(objectMapper: ObjectMapper): BackgroundTaskDescriptor {
        val request = toTaskRequest(objectMapper)
        return BackgroundTaskDescriptor(taskHandle = taskHandle, request = request)
    }

    /** Deserialize the [BackgroundTaskRequest] of this task */
    fun toTaskRequest(objectMapper: ObjectMapper): BackgroundTaskRequest {
        return objectMapper.readValue<BackgroundTaskRequest>(taskRequestJson)
    }

    /** Deserialize the [BackgroundTaskUserDescriptor] that launched the task */
    fun toTaskUser(objectMapper: ObjectMapper): BackgroundTaskUserDescriptor {
        return objectMapper.readValue<BackgroundTaskUserDescriptor>(taskUserJson)
    }

    /** Create the [BackgroundTaskDetails] of this task, without deserializing the request */
    fun toDetails(objectMapper: ObjectMapper) = BackgroundTaskDetails(
        taskHandle = taskHandle,
        requestClassName = taskRequestClassName,
        status = status,
        progress = getProgress(objectMapper),
        attempt = attempt,
        createdOn = createdOn,
        createdByUserId = createdByUserId,
        createdByUsername = createdByUsername,
        startedOn = startedOn,
        completedOn = completedOn,
        result = getResultData(objectMapper),
        error = getError(objectMapper),
        retryOn = retryOn,
    )

    /**
     * Mark the task as launched after it has been enqueued.
     * @return `true` if the status changed, `false` if the task has already left the [Created][BackgroundTaskStatus.Created] status,
     * e.g. because a worker has already started it
     */
    fun launched() = DomainResult {
        if (status == BackgroundTaskStatus.Created) {
            status = BackgroundTaskStatus.Launched
            true
        } else {
            false
        }
    }

    /**
     * Mark the task as running when a worker starts executing it, as a new execution [attempt],
     * taking the lease for the given worker.
     *
     * The task may be started before it has been marked as launched, as the worker may pick it up from the queue first.
     * A task waiting for its retry is started as its next attempt, discarding the error of the failed attempt.
     */
    fun started(
        workerName: String,
        leaseDuration: Duration,
        clock: Clock,
    ) = DomainResult { record ->
        requireIsPending()

        val now = LocalDateTime.now(clock)
        status = BackgroundTaskStatus.Running
        attempt += 1
        startedOn = now
        leaseWorkerName = workerName
        leaseExpiresOn = now + leaseDuration
        pendingSince = null
        retryOn = null
        errorJson = null

        record(BackgroundTaskStarted(this))
    }

    /** Renew the lease of the worker executing the running task */
    fun renewLease(leaseDuration: Duration, clock: Clock) {
        requireIsRunning()
        leaseExpiresOn = LocalDateTime.now(clock) + leaseDuration
    }

    /**
     * Mark the running task as waiting for its retry, as requested by the handler after the task failed with the given
     * [exception]: The task is pending again, and due to be started for its next attempt after the given [retryDelay].
     * Like a failure, it records the client-facing [error] (until the next attempt is started), and retains its
     * progress.
     * @return Evaluated timestamp when the task should be retried
     */
    fun retryScheduled(
        exception: Throwable,
        error: BackgroundTaskError,
        retryDelay: Duration,
        objectMapper: ObjectMapper,
        clock: Clock,
    ) = DomainResult<LocalDateTime> { record ->
        requireIsRunning()
        require(!retryDelay.isNegative) { "Negative retry delay: $retryDelay" }
        val now = LocalDateTime.now(clock)
        val nextRetryOn = now + retryDelay

        status = BackgroundTaskStatus.RetryScheduled
        startedOn = null
        leaseExpiresOn = null
        pendingSince = now
        retryOn = nextRetryOn
        errorJson = objectMapper.safeWriteErrorAsString(error)

        record(BackgroundTaskRetryScheduled(this, exception, retryDelay))
        nextRetryOn
    }

    /**
     * Prepare a failed task, a task waiting for its retry (retrying it now), or a running task with an expired lease,
     * for a new execution attempt:
     * The task returns to [BackgroundTaskStatus.Created], to be enqueued again.
     * Its progress is reset if and only if [discardState] restarts the task from a clean slate.
     */
    fun resumed(clock: Clock, discardState: Boolean = false) = DomainResult { record ->
        requireIsResumable(clock)

        val previousStatus = status
        status = BackgroundTaskStatus.Created
        pendingSince = LocalDateTime.now(clock)
        startedOn = null
        completedOn = null
        resultJson = null
        errorJson = null
        leaseExpiresOn = null
        retryOn = null
        if (discardState) {
            progressPercent = 0
            progressJson = null
        }

        record(BackgroundTaskResumed(this, previousStatus, discardState))
    }

    /**
     * Update the progress of the running task (including its stage).
     * @return `true` if the progress percentage changed, `false` if it is unchanged (even if the stage changed): Only
     * the percentage is compared, as the stored JSON may be normalized by the database (e.g. MySQL, PostgreSQL)
     */
    fun updateProgress(progress: BackgroundTaskProgress, objectMapper: ObjectMapper): Boolean {
        requireIsRunning()

        val changed = progressPercent != progress.percent
        progressPercent = progress.percent
        progressJson = objectMapper.writeValueAsString(progress)

        return changed
    }

    /**
     * Mark the running task as completed successfully, storing the handler's [result] value (if not `null`)
     */
    fun completed(result: JsonNode?, objectMapper: ObjectMapper, clock: Clock) = DomainResult { record ->
        requireIsRunning()

        status = BackgroundTaskStatus.Completed
        completedOn = LocalDateTime.now(clock)
        leaseExpiresOn = null
        progressPercent = 100
        // Keep the stage of the last reported progress, so clients see in which stage the task completed
        progressJson = objectMapper.writeValueAsString(getProgress(objectMapper).copy(percent = 100))
        resultJson = objectMapper.safeWriteJsonAsString(result)

        record(BackgroundTaskCompleted(this))
    }

    /**
     * Mark the task as failed with the given [exception], during execution or because it could not be enqueued.
     * Stores the client-facing [error] as well as the (internal) exception class and message.
     */
    fun failed(
        exception: Throwable,
        error: BackgroundTaskError,
        objectMapper: ObjectMapper,
        clock: Clock,
    ) = DomainResult { record ->
        requireNotAlreadyClosed()

        status = BackgroundTaskStatus.Failed
        completedOn = LocalDateTime.now(clock)
        leaseExpiresOn = null
        pendingSince = null
        retryOn = null
        errorJson = objectMapper.safeWriteErrorAsString(error)

        record(BackgroundTaskFailed(this, exception))
    }

    /**
     * Mark the task as canceled.
     * @return `true` if the task has been canceled, `false` if it had already been canceled before
     */
    fun canceled(clock: Clock) = DomainResult { record ->
        if (isCanceled()) {
            false
        } else {
            requireNotAlreadyClosed()

            status = BackgroundTaskStatus.Canceled
            completedOn = LocalDateTime.now(clock)
            leaseExpiresOn = null
            pendingSince = null
            retryOn = null

            record(BackgroundTaskCanceled(this))
            true
        }
    }

    /** Last progress reported by the task handler */
    fun getProgress(objectMapper: ObjectMapper): BackgroundTaskProgress {
        return progressJson
            ?.let { objectMapper.readValue<BackgroundTaskProgress>(it) }
            ?: BackgroundTaskProgress(progressPercent)
    }

    /** `true` if the task status is pending execution (created, launched, or waiting for its retry) */
    fun isPending(): Boolean {
        return status.isPending
    }

    /** `true` if the task has reached a final status (completed, failed, or canceled) */
    fun isClosed(): Boolean {
        return status.isClosed
    }

    /** `true` if the task has been canceled */
    fun isCanceled(): Boolean {
        return status == BackgroundTaskStatus.Canceled
    }

    /** `true` if the task is running, but the lease of its worker has expired (e.g. the worker crashed) */
    fun isLeaseExpired(clock: Clock): Boolean {
        val expiresOn = leaseExpiresOn
        val now = LocalDateTime.now(clock)
        return status.isRunning && (expiresOn == null || !expiresOn.isAfter(now))
    }

    /**
     * `true` if the task may be [resumed]:
     * It failed, it is pending (e.g. its launch message may have been lost), or it is running with an expired lease.
     *
     * Resuming a pending task whose launch message still exists enqueues it twice: It is executed once, the other
     * delivery is skipped (or starts its next attempt early, if it waits for a retry by then).
     */
    fun isResumable(clock: Clock): Boolean = when (status) {
        BackgroundTaskStatus.Failed -> true // May be restarted after failure
        BackgroundTaskStatus.Created, BackgroundTaskStatus.Launched -> true // Its launch may have been lost
        BackgroundTaskStatus.RetryScheduled -> true // Awaiting its scheduled retry, which may have been lost
        else -> isLeaseExpired(clock) // Worker died and lease expired, making it eligible to resume
    }

    /**
     * `true` if the task doesn't progress, i.e. it is presumably orphaned (and can be [resumed]):
     * It has been pending (created or launched) for longer than [stalledAfter], its retry is overdue by more than
     * [stalledAfter], or it is running with an expired lease
     */
    fun isStalled(clock: Clock, stalledAfter: Duration): Boolean {
        val stalledBefore = LocalDateTime.now(clock) - stalledAfter
        return when (status) {
            BackgroundTaskStatus.Created, BackgroundTaskStatus.Launched -> pendingSince?.isBefore(stalledBefore) ?: true
            BackgroundTaskStatus.RetryScheduled -> retryOn?.isBefore(stalledBefore) ?: true
            else -> isLeaseExpired(clock)
        }
    }

    /** `true` if the task has been canceled while it was pending, i.e. not running (in any attempt, or since resumed) */
    fun isCanceledWhilePending(): Boolean {
        return isCanceled() && startedOn == null
    }

    /** Result value returned by the task handler, `null` unless completed (or if the result was `null`) */
    fun getResultData(objectMapper: ObjectMapper): JsonNode? {
        return resultJson?.let { objectMapper.readTree(it) }
    }

    /** Client-facing error of the task, `null` unless failed or waiting for its retry */
    fun getError(objectMapper: ObjectMapper): BackgroundTaskError? {
        return errorJson?.let { objectMapper.readValue<BackgroundTaskError>(it) }
    }

    private fun requireIsPending() = requireThatStatus(BackgroundTaskStatus::isPending) { "task-not-pending" }
    private fun requireIsRunning() = requireThatStatus(BackgroundTaskStatus::isRunning) { "task-not-running" }
    private fun requireIsResumable(clock: Clock) = requireThatStatus({ isResumable(clock) }) { "task-not-resumable" }
    private fun requireNotAlreadyClosed() = requireThatStatus({ !it.isClosed }) { "task-already-closed" }

    private inline fun requireThatStatus(check: (BackgroundTaskStatus) -> Boolean, getReason: () -> String) {
        val current = status
        if (!check(current)) {
            val reason = getReason()
            throw InvalidBackgroundTaskStatus(current, reason)
        }
    }
}

private fun ObjectMapper.safeWriteErrorAsString(error: BackgroundTaskError): String? {
    return try {
        writeValueAsString(error)
    } catch (_: Exception) {
        // Arguments of the exception may not be serializable
        val simplifiedError = error.copy(arguments = emptyMap<String, Nothing>())
        writeValueAsString(simplifiedError)
    }
}

private fun ObjectMapper.safeWriteJsonAsString(json: JsonNode?): String? {
    return if (json == null || json.isNull || json.isMissingNode) null
    else writeValueAsString(json)
}

/** Thrown if a status transition is not allowed in the task's current status */
data class InvalidBackgroundTaskStatus(val taskStatus: BackgroundTaskStatus, val reason: String) : DomainException(
    status = Status.InternalError,
    code = "invalid-background-task-status",
    args = mapOf("reason" to reason, "status" to taskStatus.name),
)
