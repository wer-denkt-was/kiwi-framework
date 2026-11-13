package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskWorker
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import org.springframework.beans.factory.DisposableBean
import java.security.SecureRandom
import java.time.Duration
import java.util.*
import java.util.concurrent.*
import kotlin.random.asKotlinRandom

private val log = slf4j<LocalBackgroundTaskQueue>()

/**
 * Local [BackgroundTaskQueue] implementation, directly submitting tasks to the registered [BackgroundTaskWorker]
 * instances in the same process instance, picking a random worker accepting the task's category.
 *
 * A task's category is known if a registered worker accepts it.
 * Tasks are not persisted in the queue: pending tasks are lost if the process stops. Tasks queue up in the executor
 * of the selected worker, regardless of its [maximum concurrency][BackgroundTaskWorker.maxConcurrentTasks].
 *
 * Retries of failed tasks ([ExecutionOutcome.Retry]) are scheduled by a scheduled executor, which enqueues the task
 * again after the retry delay, to a random worker accepting its category (like a launch). If that fails (e.g. no worker
 * accepts the category anymore), the task is rejected by the worker that executed it. Retries that are still scheduled
 * when the queue is destroyed are lost, their tasks keep waiting for the retry (and can be resumed).
 */
class LocalBackgroundTaskQueue : BackgroundTaskQueue, DisposableBean {
    private val random = SecureRandom().asKotlinRandom()
    private val registeredWorkers = CopyOnWriteArrayList<BackgroundTaskWorker>()
    private val taskWorkerAssignments = ConcurrentHashMap<UUID, BackgroundTaskWorker>()

    /** Scheduler of the retries, a single daemon thread (enqueueing a retry only submits it to a worker) */
    private val retryScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().name("background-task-retry").daemon().factory(),
    )

    override fun enqueueLaunch(descriptor: BackgroundTaskDescriptor, user: BackgroundTaskUserDescriptor) {
        // Filter available workers that may accept the request
        val availableWorkers = registeredWorkers.filter { worker ->
            worker.acceptsCategory(descriptor.request.category)
        }

        // Pick one random worker that accepts the request
        val selectedWorker = if (availableWorkers.isNotEmpty()) availableWorkers.random(random)
        else throw BackgroundTaskQueue.UnknownTaskCategory(descriptor.request.category)

        // Submit task-request and register cleanup
        val taskHandle = descriptor.taskHandle
        val future = synchronized(taskWorkerAssignments) {
            taskWorkerAssignments[taskHandle] = selectedWorker
            try {
                selectedWorker.submit(descriptor, user)
            } catch (ex: Exception) {
                taskWorkerAssignments.remove(taskHandle, selectedWorker)
                throw BackgroundTaskQueue.TaskEnqueueFailed(descriptor, reason = ex.message)
            }
        }

        future.whenComplete { outcome, _ ->
            // Release the assignment before scheduling a retry, which may be assigned to the same worker
            taskWorkerAssignments.remove(taskHandle, selectedWorker)
            when (outcome) {
                null, ExecutionOutcome.Executed -> Unit // done
                is ExecutionOutcome.Retry -> scheduleRetry(
                    task = descriptor,
                    user = user,
                    retryDelay = outcome.delay,
                    previousWorker = selectedWorker,
                )
            }
        }
    }

    /**
     * Schedule enqueueing the task again after the given [retryDelay],
     * rejecting it via the [previousWorker] if that fails
     */
    private fun scheduleRetry(
        task: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
        retryDelay: Duration,
        previousWorker: BackgroundTaskWorker,
    ) {
        val enqueueRetry = Runnable {
            try {
                enqueueLaunch(task, user)
            } catch (ex: Exception) {
                rejectRetry(task, previousWorker, ex)
            }
        }

        try {
            retryScheduler.schedule(
                /* command = */ enqueueRetry,
                /* delay = */ retryDelay.toMillis(),
                /* unit = */ TimeUnit.MILLISECONDS,
            )
        } catch (ex: Exception) {
            // Rejected by the scheduler if it has been shut down
            rejectRetry(task, previousWorker, ex)
        }
    }

    private fun rejectRetry(descriptor: BackgroundTaskDescriptor, worker: BackgroundTaskWorker, cause: Exception) {
        log.error("Retry of BackgroundTask [{}] failed: {}", descriptor.taskHandle, cause.message, cause)
        worker.reject(
            taskHandle = descriptor.taskHandle,
            exception = BackgroundTaskQueue.TaskLaunchRejected("retry-failed", cause),
        )
    }

    override fun enqueueCancel(taskHandle: UUID, user: BackgroundTaskUserDescriptor): Boolean {
        // Task may not be assigned (anymore), e.g. if it has been completed in the meantime
        val assignedWorker = taskWorkerAssignments[taskHandle] ?: return false
        return assignedWorker.cancel(taskHandle, user)
    }

    override fun checkAvailable(): Boolean {
        return true // always available
    }

    override fun isKnownCategory(categoryName: String): Boolean {
        return registeredWorkers.any { worker -> worker.acceptsCategory(categoryName) }
    }

    override fun registerWorker(worker: BackgroundTaskWorker) {
        log.debug("Registered BackgroundTaskWorker [{}] in local BackgroundTaskQueue", worker.workerHandle)
        registeredWorkers.addIfAbsent(worker)
    }

    override fun unregisterWorker(worker: BackgroundTaskWorker) {
        // Assignments are kept until the submitted tasks have finished, so they can still be canceled
        log.debug("Unregistered BackgroundTaskWorker [{}] from local BackgroundTaskQueue", worker.workerHandle)
        registeredWorkers.remove(worker)
    }

    override fun destroy() {
        val droppedRetries = retryScheduler.shutdownNow()
        if (droppedRetries.isNotEmpty()) {
            log.warn(
                "Local BackgroundTaskQueue destroyed with {} scheduled retries: " +
                        "Their tasks keep waiting for the retry, resume to retry them",
                droppedRetries.size,
            )
        }
    }
}
