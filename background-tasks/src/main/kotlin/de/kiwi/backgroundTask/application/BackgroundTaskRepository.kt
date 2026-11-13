package de.kiwi.backgroundTask.application

import de.kiwi.api.backgroundTask.BackgroundTaskStatus
import de.kiwi.backgroundTask.domain.BackgroundTask
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import jakarta.persistence.LockModeType
import org.intellij.lang.annotations.Language
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.*

@Language("JPAQL")
private const val FIND_BY_ID = "select t from BackgroundTask t where t.id = ?1"

@Language("JPAQL")
private const val FIND_BY_HANDLE = "select t from BackgroundTask t where t.taskHandle = ?1"

private const val TASK_STATUS = "de.kiwi.api.backgroundTask.BackgroundTaskStatus"
private const val REPOSITORY_CLASS = "de.kiwi.backgroundTask.application.BackgroundTaskRepository"
private const val STATUS_COUNT = $$"$$REPOSITORY_CLASS$StatusCount"
private const val REQUEST_CLASS_COUNT = $$"$$REPOSITORY_CLASS$RequestClassCount"

/**
 * JPQL condition of stalled tasks (`t`) with `:stalledBefore` and `:now`
 * @see BackgroundTask.isStalled
 * @see BackgroundTaskRepository.countStalled
 */
private const val STALLED_CONDITION = """
    CASE t.status
        WHEN $TASK_STATUS.Created THEN (t.pendingSince < :stalledBefore)
        WHEN $TASK_STATUS.Launched THEN (t.pendingSince < :stalledBefore)
        WHEN $TASK_STATUS.RetryScheduled THEN (t.retryOn < :stalledBefore)
        WHEN $TASK_STATUS.Running THEN (t.leaseExpiresOn is null or t.leaseExpiresOn <= :now)
        ELSE FALSE
    END
"""

/**
 * Repository of [BackgroundTask] entities.
 * The task status is guarded by pessimistic write locks, so concurrent status changes by the service and the worker
 * executing the task are serialized.
 */
@Repository
interface BackgroundTaskRepository : JpaRepository<BackgroundTask, BackgroundTaskId> {

    data class StatusCount(val status: BackgroundTaskStatus, val count: Long)

    data class RequestClassCount(val requestClassName: String, val count: Long)

    /** Find a task by its ID with a read lock (a locking read, seeing the latest committed version) */
    @Query(FIND_BY_ID)
    @Lock(LockModeType.PESSIMISTIC_READ)
    fun findReadLockedById(id: BackgroundTaskId): BackgroundTask?

    /** Find a task by its ID with a write lock, serializing changes of its status */
    @Query(FIND_BY_ID)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWriteLockedById(id: BackgroundTaskId): BackgroundTask?

    /** Find a task by its handle, without locking it */
    @Query(FIND_BY_HANDLE)
    fun findByHandle(taskHandle: UUID): BackgroundTask?

    /** Find a task by its handle with a read lock (a locking read, seeing the latest committed version) */
    @Query(FIND_BY_HANDLE)
    @Lock(LockModeType.PESSIMISTIC_READ)
    fun findReadLockedByHandle(taskHandle: UUID): BackgroundTask?

    /** Find a task by its handle with a write lock, serializing changes of its status */
    @Query(FIND_BY_HANDLE)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWriteLockedByHandle(taskHandle: UUID): BackgroundTask?

    /** Find the ID of a task by its handle */
    @Query("select t.id from BackgroundTask t where t.taskHandle = ?1")
    fun findIdByTaskHandle(taskHandle: UUID): BackgroundTaskId?

    /** Find tasks in the given statuses, the latest first */
    @Query("select t from BackgroundTask t where t.status in :statuses order by t.createdOn desc")
    fun findAllByStatus(
        @Param("statuses") statuses: Collection<BackgroundTaskStatus>,
        pageable: Pageable,
    ): List<BackgroundTask>

    /** Count the tasks per status */
    @Query("""select new $STATUS_COUNT(t.status, count(t)) from BackgroundTask t group by t.status""")
    fun countByStatus(): List<StatusCount>

    /** Count the tasks in the given (pending) statuses that have been pending since before the given time */
    @Query(
        """
        select count(t) from BackgroundTask t
        where t.status in :statuses
        and t.pendingSince < :before
        """,
    )
    fun countByStatusPendingSinceBefore(
        @Param("statuses") statuses: Collection<BackgroundTaskStatus>,
        @Param("before") before: LocalDateTime,
    ): Long

    /** Earliest due retry of the tasks waiting for their retry, `null` if there is none */
    @Query(
        """
        select min(t.retryOn) from BackgroundTask t
        where t.status = $TASK_STATUS.RetryScheduled
        """,
    )
    fun findEarliestRetryOn(): LocalDateTime?

    /** Since when the longest pending task in the given (pending) statuses is pending, `null` if there is none */
    @Query("select min(t.pendingSince) from BackgroundTask t where t.status in :statuses")
    fun findOldestPendingSince(
        @Param("statuses") statuses: Collection<BackgroundTaskStatus>,
    ): LocalDateTime?

    /** Count the tasks created since the given time */
    @Query("select count(t) from BackgroundTask t where t.createdOn >= ?1")
    fun countCreatedSince(since: LocalDateTime): Long

    /** Count the tasks whose latest attempt has been started since the given time */
    @Query("select count(t) from BackgroundTask t where t.startedOn >= ?1")
    fun countStartedSince(since: LocalDateTime): Long

    /** Count the tasks closed since the given time per status */
    @Query(
        """
        select new $STATUS_COUNT(t.status, count(t))
        from BackgroundTask t
        where t.completedOn >= ?1
        group by t.status
        """,
    )
    fun countClosedSinceByStatus(since: LocalDateTime): List<StatusCount>

    /**
     * Count the tasks in the [BackgroundTaskStatus.Failed] status closed since the given time, without having been
     * started (in their latest attempt)
     */
    @Query(
        """
        select count(t)
        from BackgroundTask t
        where t.status = $TASK_STATUS.Failed
        and t.completedOn >= ?1
        and t.startedOn is null
        """,
    )
    fun countFailedUnstartedSince(since: LocalDateTime): Long

    /**
     * Count the tasks in the [BackgroundTaskStatus.Failed] status closed since the given time per request class
     */
    @Query(
        """
        select new $REQUEST_CLASS_COUNT(t.taskRequestClassName, count(t))
        from BackgroundTask t
        where t.status = $TASK_STATUS.Failed
        and t.completedOn >= ?1
        group by t.taskRequestClassName
        """,
    )
    fun countFailedSinceByRequestClass(since: LocalDateTime): List<RequestClassCount>

    /** Find the stalled tasks, the latest first (see [countStalled]) */
    @Query("select t from BackgroundTask t where $STALLED_CONDITION order by t.createdOn desc")
    fun findAllStalled(
        @Param("stalledBefore") stalledBefore: LocalDateTime,
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<BackgroundTask>

    /**
     * Count the stalled tasks (see [de.kiwi.backgroundTask.domain.BackgroundTask.isStalled]): Pending since before
     * [stalledBefore], waiting for a retry due before [stalledBefore], or running with a lease expired at [now]
     */
    @Query("select count(t) from BackgroundTask t where $STALLED_CONDITION")
    fun countStalled(
        @Param("stalledBefore") stalledBefore: LocalDateTime,
        @Param("now") now: LocalDateTime,
    ): Long
}
