package de.kiwi.backgroundTask.application

import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.SmartLifecycle
import java.time.Duration

private val log = slf4j<BackgroundTaskWorkerLifecycle>()

/**
 * Lifecycle of the [BackgroundTaskWorker] beans of an application instance:
 * - On start, the workers are registered in the [BackgroundTaskQueue], so they receive tasks only once the
 *   application context has been refreshed.
 * - On stop, the workers are unregistered from the queue first, so they don't receive new tasks, and are then shut
 *   down concurrently. Running tasks may finish within the [shutdownTimeout], before they are aborted.
 *
 * The lifecycle runs in [DEFAULT_PHASE - 4096][TASK_WORKER_PHASE], after the web server has been stopped
 * (Spring Boot's web server lifecycles have higher phases), so requests still being processed can launch tasks.
 */
class BackgroundTaskWorkerLifecycle(
    private val taskQueueProvider: ObjectProvider<BackgroundTaskQueue>,
    private val taskWorkerProvider: ObjectProvider<BackgroundTaskWorker>,
    private val shutdownTimeout: Duration,
) : SmartLifecycle {
    companion object {
        /** Lifecycle phase, below the phases of Spring Boot's web server lifecycles */
        const val TASK_WORKER_PHASE = SmartLifecycle.DEFAULT_PHASE - 4096
    }

    @Volatile
    private var running = false

    private var startedWorkers = emptyList<BackgroundTaskWorker>()

    override fun start() {
        val queue = requireTaskQueue()
        startedWorkers = listTaskWorkers()

        log.info("Registering {} BackgroundTaskWorker(s) in BackgroundTaskQueue", startedWorkers.size)
        startedWorkers.forEach { worker -> queue.registerWorker(worker) }
        running = true
    }

    override fun stop() {
        running = false

        val queue = requireTaskQueue()
        val stoppingWorkers = startedWorkers
        startedWorkers = emptyList()

        // Stop receiving new tasks first
        stoppingWorkers.forEach { worker ->
            try {
                queue.unregisterWorker(worker)
            } catch (ex: Exception) {
                log.warn("Unregistering BackgroundTaskWorker [{}] failed: {}", worker.workerHandle, ex.message, ex)
            }
        }

        // Shut down workers concurrently, so the timeout applies to all of them at once
        log.info("Shutting down {} BackgroundTaskWorker(s), timeout {}", stoppingWorkers.size, shutdownTimeout)
        stoppingWorkers
            .map { worker -> shutdownWorkerAsyncWithTimeout(worker) }
            .forEach { shutdown -> shutdown.join() }
    }

    override fun isRunning() = running

    override fun getPhase() = TASK_WORKER_PHASE

    private fun requireTaskQueue(): BackgroundTaskQueue {
        return requireNotNull(taskQueueProvider.ifUnique) {
            "Missing ${BackgroundTaskQueue::class.simpleName} for Kiwi BackgroundTaskWorker lifecycle"
        }
    }

    private fun listTaskWorkers(): List<BackgroundTaskWorker> {
        return taskWorkerProvider.orderedStream().toList()
    }

    private fun shutdownWorkerAsyncWithTimeout(worker: BackgroundTaskWorker): Thread {
        return Thread.ofVirtual().name("task-worker-shutdown-${worker.workerHandle}").start {
            try {
                worker.shutdown(shutdownTimeout)
            } catch (ex: Exception) {
                log.warn(
                    "Shutdown of BackgroundTaskWorker [{}] failed: {}",
                    worker.workerHandle,
                    ex.message,
                    ex,
                )
            }
        }
    }
}
