package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.BackgroundTaskProgress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime

class TaskSupervisorTest {
    private val longInterval = Duration.ofSeconds(30)
    private val syncQueue = LinkedBlockingQueue<Optional<BackgroundTaskProgress>>()
    private val supervisors = mutableListOf<TaskSupervisor>()

    @AfterEach
    fun closeSupervisors() {
        supervisors.forEach { it.close() }
    }

    @Test
    fun firstProgressIsSyncedImmediately() {
        val supervisor = startSupervisor(progressInterval = longInterval)

        supervisor.reportProgress(progressOf(10))

        assertEquals(progressOf(10), awaitSync())
    }

    @Test
    fun progressWithinIntervalIsConflatedUntilClosed() {
        val supervisor = startSupervisor(progressInterval = longInterval)
        supervisor.reportProgress(progressOf(10))
        awaitSync()

        supervisor.reportProgress(progressOf(20))
        supervisor.reportProgress(progressOf(30))

        assertNull(syncQueue.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(progressOf(30), supervisor.close())
        assertTrue(syncQueue.isEmpty())
    }

    @Test
    fun latestProgressIsSyncedAfterInterval() {
        val shortInterval = Duration.ofMillis(300)
        val supervisor = startSupervisor(progressInterval = shortInterval)
        supervisor.reportProgress(progressOf(10))
        awaitSync()

        val elapsedNanos = measureNanoTime {
            supervisor.reportProgress(progressOf(20))
            supervisor.reportProgress(progressOf(30))

            assertEquals(progressOf(30), awaitSync())
        }

        val elapsed = Duration.ofNanos(elapsedNanos)
        assertTrue(elapsed >= shortInterval.minusMillis(50)) { "Synced again after $elapsed" }
        assertNull(supervisor.close())
    }

    @Test
    fun unchangedProgressIsSkipped() {
        val supervisor = startSupervisor(progressInterval = Duration.ZERO)
        supervisor.reportProgress(progressOf(10))
        assertEquals(progressOf(10), awaitSync())

        // Unchanged since the last sync, or changed back before the next sync
        supervisor.reportProgress(progressOf(10))
        supervisor.reportProgress(progressOf(20))
        supervisor.reportProgress(progressOf(10))

        assertNull(syncQueue.poll(200, TimeUnit.MILLISECONDS))
        assertNull(supervisor.close())
    }

    @Test
    fun leaseIsRenewedWithoutProgress() {
        startSupervisor(
            progressInterval = longInterval,
            leaseRenewalInterval = Duration.ofMillis(100),
        )

        // Lease renewals don't include progress
        assertNull(awaitSync())
        assertNull(awaitSync())
    }

    @Test
    fun closeWaitsForSyncInProgress() {
        val syncStarted = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val supervisor = startSupervisor(progressInterval = Duration.ZERO) { progress ->
            syncStarted.countDown()
            releaseSync.await()
            syncQueue.add(Optional.ofNullable(progress))
        }
        supervisor.reportProgress(progressOf(10))
        assertTrue(syncStarted.await(5, TimeUnit.SECONDS))
        supervisor.reportProgress(progressOf(20))

        val closing = Thread.ofVirtual().start { assertEquals(progressOf(20), supervisor.close()) }
        assertFalse(closing.join(Duration.ofMillis(200))) // assert that thread terminates

        // The sync in progress finishes before close returns, the pending progress is returned
        releaseSync.countDown()
        assertTrue(closing.join(Duration.ofSeconds(5)))
        assertEquals(progressOf(10), awaitSync())
        assertTrue(syncQueue.isEmpty())
    }

    @Test
    fun failedSyncDoesNotStopSupervisor() {
        // This will get "failNext" as `false` once, and `true` next time
        val failNext = AtomicBoolean(true)
        val supervisor = startSupervisor(progressInterval = Duration.ZERO) { progress ->
            if (failNext.compareAndSet(true, false)) throw IllegalStateException("database unavailable")
            syncQueue.add(Optional.ofNullable(progress))
        }

        supervisor.reportProgress(progressOf(10))
        while (failNext.get()) Thread.sleep(10)
        supervisor.reportProgress(progressOf(20))

        // The supervisor keeps syncing: The failed progress may be retried before the newer one is synced
        val synced = generateSequence { awaitSync() }
            .takeWhileInclusive { it != progressOf(20) }
            .toList()

        assertEquals(progressOf(20), synced.last())
        assertTrue(synced.dropLast(1).all { it == progressOf(10) }) { "$synced" }
    }

    private fun <T> Sequence<T>.takeWhileInclusive(predicate: (T) -> Boolean) = sequence {
        for (item in this@takeWhileInclusive) {
            yield(item)
            if (!predicate(item)) break
        }
    }

    @Test
    fun progressBeforeStartIsReturnedOnClose() {
        val supervisor = TaskSupervisor(
            name = "unstarted",
            progressInterval = Duration.ZERO,
            leaseRenewalInterval = longInterval,
        ) { progress ->
            syncQueue.add(Optional.ofNullable(progress))
        }

        supervisor.reportProgress(progressOf(10))
        assertEquals(progressOf(10), supervisor.close())
        assertTrue(syncQueue.isEmpty())
    }

    @Test
    fun progressOfFailedSyncIsRetried() {
        val failNext = AtomicBoolean(true)
        val supervisor = startSupervisor(progressInterval = Duration.ofMillis(50)) { progress ->
            if (progress != null && failNext.getAndSet(false)) throw IllegalStateException("database unavailable")
            syncQueue.add(Optional.ofNullable(progress))
        }

        // The first sync fails, the progress is synced again on the next attempt (reporting it again is skipped)
        supervisor.reportProgress(progressOf(10))
        supervisor.reportProgress(progressOf(10))
        assertEquals(progressOf(10), awaitSync())
        assertNull(syncQueue.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun progressOfFailedSyncIsReturnedOnClose() {
        val synced = CountDownLatch(1)
        val supervisor = startSupervisor(progressInterval = longInterval) { progress ->
            synced.countDown()
            if (progress != null) throw IllegalStateException("database unavailable")
        }

        supervisor.reportProgress(progressOf(20))
        assertTrue(synced.await(5, TimeUnit.SECONDS))
        assertEquals(progressOf(20), supervisor.close())
    }

    private fun startSupervisor(
        progressInterval: Duration,
        leaseRenewalInterval: Duration = longInterval,
        sync: (BackgroundTaskProgress?) -> Unit = ::addProgressToSyncQueue,
    ): TaskSupervisor {
        return TaskSupervisor(
            name = "test-supervisor",
            progressInterval = progressInterval,
            leaseRenewalInterval = leaseRenewalInterval,
            sync = sync,
        ).also { supervisor ->
            supervisors.add(supervisor)
            supervisor.start()
        }
    }

    private fun addProgressToSyncQueue(progress: BackgroundTaskProgress?) {
        syncQueue.add(Optional.ofNullable(progress))
    }

    /** Await the next sync, returning its progress (`null` for a lease renewal) */
    private fun awaitSync(): BackgroundTaskProgress? {
        val sync = syncQueue.poll(/* timeout = */ 5, /* unit = */ TimeUnit.SECONDS)
        assertNotNull(sync) { "No sync" }
        return sync!!.orElse(null)
    }

    private fun progressOf(percent: Int) = BackgroundTaskProgress(percent)
}
