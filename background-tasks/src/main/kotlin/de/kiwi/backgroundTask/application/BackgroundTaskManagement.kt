package de.kiwi.backgroundTask.application

import de.kiwi.api.application.service.BackgroundTaskService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.backgroundTask.BackgroundTaskError
import de.kiwi.api.backgroundTask.BackgroundTaskProgress
import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.api.domain.EntityId
import de.kiwi.backgroundTask.domain.BackgroundTask
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * Monitoring and management of background tasks by administrators, e.g. via the `backgroundTasks` management endpoint:
 * Lists tasks (including stalled tasks, see [stalledAfter]), shows their details, resumes failed or stalled tasks,
 * and provides [statistics][getStats] for monitoring.
 *
 * Orphaned tasks are not recovered automatically: Monitoring detects them by the statistics (e.g. [TaskStats.stalled]),
 * administrators list and resume them.
 *
 * Tasks are identified by their [id][BackgroundTask.id], as the task handle is a secret of the launching client.
 */
class BackgroundTaskManagement(
    private val taskService: BackgroundTaskService,
    private val taskRepository: BackgroundTaskRepository,
    private val dataRepository: BackgroundTaskDataRepository,
    private val transactions: TransactionService,
    private val clockProvider: ClockProvider,
    private val objectMapper: ObjectMapper,
    /**
     * Time after which a task that doesn't progress is considered [stalled][BackgroundTask.isStalled] (presumably
     * orphaned): Pending for longer, or its retry overdue by more (running tasks: as soon as their lease expired)
     */
    private val stalledAfter: Duration = DEFAULT_STALLED_AFTER,
) {
    /** Summary of a task for monitoring */
    data class TaskInfo(
        /** ID of the task, identifying it for management operations */
        val id: String,
        /** Class name of the task's request */
        val requestClassName: String,
        /** Current status */
        val status: BackgroundTaskStatus,
        /** Number of the latest execution attempt */
        val attempt: Int,
        /** Last stored progress */
        val progress: BackgroundTaskProgress,
        /** Name of the worker executing (or last executing) the task */
        val leaseWorkerName: String?,
        /** Expiry of the worker's lease, if running */
        val leaseExpiresOn: LocalDateTime?,
        /** `true` if the task is running, but the worker's lease expired (e.g. the worker crashed) */
        val leaseExpired: Boolean,
        /**
         * `true` if the task doesn't progress, i.e. it is presumably orphaned: Pending or its retry overdue for longer
         * than the management's stalled-after threshold, or running with an expired lease
         */
        val stalled: Boolean,
        /** `true` if the task can be resumed (failed, pending, waiting for its retry, or lease expired) */
        val resumable: Boolean,
        /** Name of the user that launched the task, if authenticated */
        val createdByUsername: String?,
        /** When the task has been launched */
        val createdOn: LocalDateTime,
        /** Since when the task is pending (created, resumed, or its retry scheduled), `null` unless pending */
        val pendingSince: LocalDateTime?,
        /** When a worker started executing the task (the latest attempt) */
        val startedOn: LocalDateTime?,
        /** When the task has been closed (completed, failed, or canceled) */
        val completedOn: LocalDateTime?,
        /** Error of a failed task, or of the failed attempt of a task waiting for its retry */
        val error: BackgroundTaskError?,
        /** When the retry of a task waiting for it is due */
        val retryOn: LocalDateTime?,
        /** Keys of the task's scratchpad, only included in the details of a single task */
        val scratchpadKeys: List<String>? = null,
    )

    /**
     * Statistics of the background tasks for monitoring and alerting, e.g. to detect a growing number of failed tasks,
     * tasks failing to launch, tasks waiting for a worker, or stalled (orphaned) tasks.
     */
    data class TaskStats(
        /** Time the statistics have been collected */
        val now: LocalDateTime,
        /** Number of tasks per current status (all statuses, including `0` counts) */
        val status: Map<BackgroundTaskStatus, Long>,
        /** Number of tasks waiting for a worker (created or launched, i.e. not started yet) */
        val pending: Long,
        /** Since when the longest pending task is pending (created or resumed), `null` if no task is pending */
        val oldestPendingSince: LocalDateTime?,
        /**
         * Earliest due retry of the tasks waiting for their retry, `null` if none is waiting:
         * Far in the past, retries are not delivered (e.g. lost)
         */
        val oldestRetryOn: LocalDateTime?,
        /**
         * Number of stalled tasks, presumably orphaned (see [TaskInfo.stalled]): Pending or waiting for an overdue
         * retry for longer than the stalled-after threshold, or running with an expired lease (e.g. the worker crashed)
         */
        val stalled: Long,
    )

    /** Statistics of the tasks in the recent period, from [PeriodStats.since] until [PeriodStats.now] */
    data class PeriodStats(
        /** Time the period statistics have been collected */
        val now: LocalDateTime,
        /** Duration of the period */
        val duration: Duration,
        /** Start of the period */
        val since: LocalDateTime,
        /** Number of tasks created (launched) in the period */
        val created: Long,
        /** Number of tasks started by a worker in the period (their latest attempt) */
        val started: Long,
        /** Number of tasks completed in the period */
        val completed: Long,
        /** Number of tasks failed in the period, including [failedUnstarted] */
        val failed: Long,
        /**
         * Number of tasks failed in the period without having been started (in their latest attempt),
         * i.e. they failed to launch: Enqueueing them failed, or they were rejected by the queue or worker
         */
        val failedUnstarted: Long,
        /** Number of tasks canceled in the period */
        val canceled: Long,
        /** Number of tasks failed in the period per request class name */
        val failedByRequestClass: Map<String, Long>,
        /** Number of tasks pending since before the period, i.e. waiting for a worker longer than the period */
        val pendingSincePeriodStart: Long,
    )

    /**
     * Collect the [statistics][TaskStats] of all tasks
     */
    fun getStats(): TaskStats {
        return transactions.read { _ ->
            val now = LocalDateTime.now(clockProvider.clock())

            val emptyStatusCounts = BackgroundTaskStatus.entries.associateWith { 0L }
            val statusCounts = emptyStatusCounts + taskRepository.countByStatus()
                .associate { it.status to it.count }

            TaskStats(
                now = now,
                status = statusCounts,
                pending = PENDING_STATUSES.sumOf { statusCounts.getValue(it) },
                oldestPendingSince = taskRepository.findOldestPendingSince(PENDING_STATUSES),
                oldestRetryOn = taskRepository.findEarliestRetryOn(),
                stalled = taskRepository.countStalled(stalledBefore = now - stalledAfter, now = now),
            )
        }
    }

    /**
     * Collect the [statistics][PeriodStats] of the tasks of the recent [period] (e.g. the last hour)
     */
    fun getPeriodStats(period: Duration): PeriodStats {
        return transactions.read { _ ->
            require(!period.isNegative && !period.isZero && period <= MAX_STATS_PERIOD) { "Invalid period: $period" }
            val now = LocalDateTime.now(clockProvider.clock())
            val since = now - period

            val closedCounts = taskRepository.countClosedSinceByStatus(since)
                .associate { it.status to it.count }

            val failedByRequestClass = taskRepository.countFailedSinceByRequestClass(since)
                .associate { it.requestClassName to it.count }

            PeriodStats(
                now = now,
                duration = period,
                since = since,
                created = taskRepository.countCreatedSince(since),
                started = taskRepository.countStartedSince(since),
                completed = closedCounts[BackgroundTaskStatus.Completed] ?: 0,
                failed = closedCounts[BackgroundTaskStatus.Failed] ?: 0,
                failedUnstarted = taskRepository.countFailedUnstartedSince(since),
                canceled = closedCounts[BackgroundTaskStatus.Canceled] ?: 0,
                failedByRequestClass = failedByRequestClass,
                pendingSincePeriodStart = taskRepository.countByStatusPendingSinceBefore(PENDING_STATUSES, since),
            )
        }
    }

    /**
     * List tasks, the latest first: The [stalled] tasks (see [TaskInfo.stalled]), otherwise tasks in the given
     * [statuses] (by default the tasks that are not closed, and the failed tasks)
     */
    fun listTasks(
        statuses: Set<BackgroundTaskStatus>?,
        stalled: Boolean,
        limit: Int,
    ): List<TaskInfo> {
        require(limit in 1..MAX_LIMIT) { "Invalid limit: $limit" }

        val clock = clockProvider.clock()
        return transactions.read {
            val page = PageRequest.of(0, limit)
            val tasks = if (stalled) {
                val now = LocalDateTime.now(clock)
                val stalledBefore = now - stalledAfter
                taskRepository.findAllStalled(stalledBefore = stalledBefore, now = now, pageable = page)
            } else {
                val includeStatuses = statuses?.ifEmpty { null } ?: DEFAULT_INCLUDED_STATUSES
                taskRepository.findAllByStatus(includeStatuses, page)
            }

            tasks.map { it.toInfo(clock) }
        }
    }

    /** Find a task by its [id] (string representation of the [BackgroundTaskId]), including its scratchpad keys */
    fun findTask(id: String): TaskInfo? {
        val taskId = parseTaskId(id)
        val clock = clockProvider.clock()
        return transactions.read {
            val task = taskRepository.findByIdOrNull(taskId) ?: return@read null
            task.toInfo(clock).copy(scratchpadKeys = dataRepository.findKeysByTaskId(taskId))
        }
    }

    /**
     * Resume a task (failed, pending, waiting for its retry, or running with an expired lease) by its [id],
     * see [BackgroundTaskService.resume].
     * With [discardState], the task is restarted from a clean slate (its scratchpad is deleted, its progress
     * is reset).
     * @return Details of the resumed task, `null` if the task doesn't exist
     */
    fun resumeTask(id: String, discardState: Boolean = false): TaskInfo? {
        val taskId = parseTaskId(id)
        val taskHandle = transactions.read { taskRepository.findByIdOrNull(taskId)?.taskHandle } ?: return null
        taskService.resume(taskHandle, discardState)
        return checkNotNull(findTask(id)) { "BackgroundTask #$id not found after resuming it" }
    }

    private fun parseTaskId(id: String): BackgroundTaskId = try {
        EntityId.fromString<BackgroundTaskId>(id)
    } catch (ex: Exception) {
        throw IllegalArgumentException("Invalid BackgroundTask ID: $id", ex)
    }

    private fun BackgroundTask.toInfo(clock: Clock) = TaskInfo(
        id = id.toString(),
        requestClassName = taskRequestClassName,
        status = status,
        attempt = attempt,
        progress = getProgress(objectMapper),
        leaseWorkerName = leaseWorkerName,
        leaseExpiresOn = leaseExpiresOn,
        leaseExpired = isLeaseExpired(clock),
        stalled = isStalled(clock, stalledAfter),
        resumable = isResumable(clock),
        createdByUsername = createdByUsername,
        createdOn = createdOn,
        pendingSince = pendingSince,
        startedOn = startedOn,
        completedOn = completedOn,
        error = getError(objectMapper),
        retryOn = retryOn,
    )

    companion object {
        /** Maximum number of listed tasks */
        const val MAX_LIMIT = 1000

        /** Maximum period of the [period statistics][BackgroundTaskManagement.getPeriodStats] */
        val MAX_STATS_PERIOD: Duration = Duration.ofDays(31)

        /** Default time after which a task that doesn't progress is considered stalled */
        val DEFAULT_STALLED_AFTER: Duration = Duration.ofMinutes(10)

        /** Statuses of tasks waiting for a worker (not for a retry, which is due later) */
        val PENDING_STATUSES = setOf(BackgroundTaskStatus.Created, BackgroundTaskStatus.Launched)

        /** Statuses of the listed tasks by default: Not closed, i.e. created, launched, running, or retry-scheduled */
        val DEFAULT_INCLUDED_STATUSES = BackgroundTaskStatus.entries.filterNot(BackgroundTaskStatus::isClosed).toSet()
    }
}
