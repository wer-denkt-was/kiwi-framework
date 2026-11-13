package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.BackgroundTaskProgress
import de.kiwi.api.util.slf4j
import java.time.Duration
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val log = slf4j<TaskSupervisor>()

/** Time [TaskSupervisor.close] waits for a sync in progress to finish */
private val SYNC_COMPLETION_TIMEOUT: Duration = Duration.ofSeconds(10)

/**
 * Supervises a running task execution in a virtual thread, invoking [sync] in the background:
 * - When progress has been reported, at most once per [progressInterval] (the first report after an idle interval
 *   immediately), with the latest progress. Progress equal to the last synced progress is skipped.
 * - Otherwise every [leaseRenewalInterval], without progress, so the execution renews its lease and learns about
 *   status changes (e.g. a cancellation).
 *
 * [sync] is never invoked concurrently, nor after [close] returned; exceptions thrown by it are logged.
 * The latest progress not synced yet is returned by [close], so it can be stored when the task is closed.
 */
internal class TaskSupervisor(
    private val name: String,
    private val progressInterval: Duration,
    private val leaseRenewalInterval: Duration,
    private val sync: (BackgroundTaskProgress?) -> Unit,
) {
    init {
        require(!progressInterval.isNegative) { "Invalid progress interval: $progressInterval" }
        require(leaseRenewalInterval.isPositive) { "Invalid lease renewal interval: $leaseRenewalInterval" }
    }

    private val lock: ReentrantLock = ReentrantLock()
    private val stateChanged: Condition = lock.newCondition()

    // Mutable state guarded by the lock
    private var pendingProgress: BackgroundTaskProgress? = null
    private var syncedProgress: BackgroundTaskProgress? = null
    private var inFlightProgress: BackgroundTaskProgress? = null
    private var lastProgressSync: Long? = null
    private var lastSync = System.nanoTime()
    private var thread: Thread? = null
    private var closed = false

    /** Start the supervising thread */
    fun start() = lock.withLock {
        check(thread == null && !closed) { "$name: Already started" }
        thread = Thread.ofVirtual().name(name).start(::runSupervisor)
    }

    /** Report the latest progress, synced in the background (replacing progress not synced yet) */
    fun reportProgress(progress: BackgroundTaskProgress) = lock.withLock {
        if (!closed) {
            // Skip progress that has been synced already, or is being synced (restored as pending if that sync fails)
            pendingProgress = progress.takeIf { it != syncedProgress && it != inFlightProgress }
            stateChanged.signalAll()
        }
    }

    /**
     * Stop the supervising thread (waiting for a sync in progress to finish),
     * and return the latest progress that hasn't been synced yet
     */
    fun close(): BackgroundTaskProgress? {
        val supervisingThread = lock.withLock {
            closed = true
            stateChanged.signalAll()
            thread
        }
        if (supervisingThread != null) {
            try {
                if (!supervisingThread.join(SYNC_COMPLETION_TIMEOUT)) {
                    log.warn("{}: Sync did not finish within {}", name, SYNC_COMPLETION_TIMEOUT)
                }
            } catch (_: InterruptedException) {
                // Keep the interrupt for the caller, return the pending progress regardless
                Thread.currentThread().interrupt()
                log.warn("{}: Interrupted while waiting for sync to finish", name)
            }
        }
        return lock.withLock {
            // Fetch latest pending (not yet synchronized) progress, then erase state
            val lastPendingProgress = pendingProgress
            pendingProgress = null
            lastPendingProgress
        }
    }

    //#region Runs in supervising thread

    private fun runSupervisor() {
        try {
            while (true) {
                val next = awaitNextSync() ?: return // Finish when no sync remains
                try {
                    sync(next.progress)
                    lock.withLock {
                        if (next.progress != null) syncedProgress = next.progress
                        inFlightProgress = null
                    }
                } catch (ex: Exception) {
                    log.warn("{}: Sync failed: {}", name, ex.message, ex)
                    lock.withLock {
                        // Restore the progress as pending (unless newer progress has been reported), so it is synced
                        // on the next attempt, or returned by close()
                        if (pendingProgress == null) pendingProgress = next.progress
                        inFlightProgress = null
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Terminate, the pending progress is returned by close()
        }
    }

    private data class NextSync(val progress: BackgroundTaskProgress?)

    /** Wait for the next sync, returning its progress (or no progress for a lease renewal), or `null` if closed */
    private fun awaitNextSync(): NextSync? = lock.withLock {
        while (!closed) {
            val now = System.nanoTime()
            val progressIntervalNanos = progressInterval.toNanos()
            val leaseIntervalNanos = leaseRenewalInterval.toNanos()

            // Nanoseconds until the progress/lease renewal is due, the first progress immediately
            val leaseDue = lastSync + leaseIntervalNanos - now
            val progressDue = pendingProgress?.let {
                val lastProgressNanos = lastProgressSync ?: return@let 0
                lastProgressNanos + progressIntervalNanos - now
            }

            when {
                // Next sync with progress
                progressDue != null && progressDue <= 0 -> {
                    val progress = pendingProgress!!
                    pendingProgress = null
                    inFlightProgress = progress
                    lastProgressSync = now
                    lastSync = now
                    return NextSync(progress = progress)
                }
                // Next sync with lease-renewal only
                leaseDue <= 0 -> {
                    lastSync = now
                    return NextSync(progress = null)
                }
                // Go to sleep until next sync is due
                else -> {
                    val waitForNanos = minOf(leaseDue, progressDue ?: Long.MAX_VALUE)
                    stateChanged.awaitNanos(waitForNanos)
                }
            }
        }
        // Supervisor has been closed, no next sync -> Signal the supervisor thread to terminate
        return null
    }

    //#endregion
}
