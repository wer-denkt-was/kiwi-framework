package de.kiwi.backgroundTask.application

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskError
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.domain.DomainException
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import de.kiwi.backgroundTask.application.messaging.broadcastSafely
import de.kiwi.backgroundTask.application.worker.TaskExecution
import de.kiwi.backgroundTask.application.worker.TaskExecutionControl
import de.kiwi.backgroundTask.application.worker.TaskExecutionEnvironment
import org.springframework.beans.factory.InitializingBean
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.util.*
import java.util.concurrent.*

private val log = slf4j<BackgroundTaskWorkerBean>()

/** Time to wait for aborted tasks to terminate on [BackgroundTaskWorkerBean.shutdown] */
private val EXECUTOR_ABORT_TIMEOUT = Duration.ofSeconds(10)

/**
 * Default component implementation of [BackgroundTaskWorker], executing tasks in an [ExecutorService] created from
 * the [workerConfig]. Each submitted task runs as a [TaskExecution].
 *
 * The worker requires a proper bean lifecycle: [afterPropertiesSet] creates the executor, [shutdown] stops it
 * gracefully (invoked by the worker lifecycle when the application context stops), [close] terminates it.
 */
class BackgroundTaskWorkerBean(
    /**
     * Name of the worker, uniquely identifying it for monitoring (e.g. in the task's lease): `<pid>@<host>/<beanName>`.
     * Unlike the [workerHandle], the name is not secret.
     */
    val workerName: String,
    /**
     * Configuration of the worker's executor, accepted categories, and task supervision
     */
    private val workerConfig: BackgroundTaskWorkerBeanConfig,
    private val taskLifecycle: BackgroundTaskLifecycleManager,
    private val taskMessaging: BackgroundTaskMessaging,
    taskResolver: BackgroundTaskResolver,
    objectMapper: ObjectMapper,
) : BackgroundTaskWorker, InitializingBean {

    private lateinit var executor: ExecutorService
    private val runningTasks = ConcurrentHashMap<UUID, TaskExecution>()

    private val environment = TaskExecutionEnvironment(
        workerName = workerName,
        lifecycle = taskLifecycle,
        messaging = taskMessaging,
        resolver = taskResolver,
        objectMapper = objectMapper,
        progressInterval = workerConfig.progressInterval,
        leaseDuration = workerConfig.leaseDuration,
        maxRetryDelay = workerConfig.maxRetryDelay,
    )

    override lateinit var workerHandle: UUID
        private set

    override val maxConcurrentTasks: Int
        get() = workerConfig.effectiveMaxConcurrentTasks()

    override fun afterPropertiesSet() {
        // Secure Random UUID must be used, as handle must be unguessable to keep worker-communication secret
        workerHandle = UUID.randomUUID()
        executor = workerConfig.createExecutor()
    }

    override fun shutdown(timeout: Duration) {
        // Stop accepting new tasks, let submitted tasks finish
        executor.shutdown()
        if (executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return // Executor terminated successfully
        }

        // Abort tasks still running after the timeout, and drop tasks that haven't been started yet
        log.warn(
            "BackgroundTaskWorker [{}]: {} task(s) still running after {}, aborting",
            workerName, runningTasks.size, timeout,
        )
        runningTasks.values.forEach { execution ->
            execution.requestAbort(TaskExecutionControl.AbortReason.Shutdown)
        }
        executor.shutdownNow().forEach { dropped ->
            if (dropped is TaskExecution) {
                // Fail the dropped task (before completing its execution, e.g. dead-lettering its message),
                // so it can be resumed
                reject(dropped.descriptor.taskHandle, BackgroundTaskQueue.TaskLaunchRejected("worker-shutdown"))
                dropped.dropNotStarted(workerHandle)
            }
        }

        // Check again if executor terminated after aborting tasks, log a warning if any tasks still persist
        if (!executor.awaitTermination(EXECUTOR_ABORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            log.error("BackgroundTaskWorker [{}]: Aborted tasks did not terminate in time", workerName)
        }
    }

    override fun close() {
        // Terminate immediately, if the worker hasn't been shut down gracefully before
        if (!executor.isTerminated) {
            executor.shutdownNow()
        }
    }

    override fun acceptsCategory(categoryName: String): Boolean = when {
        executor.isShutdown -> false // don't accept any new tasks when shutdown
        else -> workerConfig.acceptsCategory(categoryName)
    }

    override fun submit(
        descriptor: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
    ): CompletableFuture<BackgroundTaskWorker.ExecutionOutcome> {
        // Instantiate task execution
        val taskHandle = descriptor.taskHandle
        val execution = TaskExecution(descriptor, user, environment) { finishedExecution ->
            runningTasks.remove(taskHandle, finishedExecution) // on finished
        }

        // Ensure task has only been submitted once
        if (runningTasks.putIfAbsent(taskHandle, execution) != null) {
            log.warn("BackgroundTask [{}] has already been submitted to worker [{}], skipped", taskHandle, workerName)
            return CompletableFuture.completedFuture(BackgroundTaskWorker.ExecutionOutcome.Executed)
        }

        // Submit task execution to executor
        try {
            executor.execute(execution)
        } catch (ex: RejectedExecutionException) {
            runningTasks.remove(taskHandle, execution)
            reject(taskHandle, BackgroundTaskQueue.TaskLaunchRejected("worker-rejected", ex))
            execution.completion.completeExceptionally(ex)
        }

        return execution.completion
    }

    override fun cancel(
        taskHandle: UUID,
        user: BackgroundTaskUserDescriptor,
    ): Boolean {
        val execution = runningTasks[taskHandle] ?: return false
        execution.requestCancel()
        return true
    }

    override fun reject(taskHandle: UUID, exception: Exception) {
        try {
            val result = taskLifecycle.failUnstartedTask(taskHandle, exception)
            if (result != null && result.isClosedByCall) {
                log.warn("BackgroundTask [{}] rejected by worker [{}]: {}", taskHandle, workerName, exception.message)
                val error = BackgroundTaskError.of(exception)
                taskMessaging.broadcastSafely(BackgroundTaskMessage.StatusChanged.Failed(taskHandle, error))
            }
        } catch (ex: Exception) {
            log.error("Failing rejected BackgroundTask [{}] failed: {}", taskHandle, ex.message, ex)
        }
    }

    override fun equals(other: Any?): Boolean = when {
        this === other -> true
        other !is BackgroundTaskWorkerBean -> false
        else -> workerHandle == other.workerHandle
    }

    override fun hashCode(): Int = workerHandle.hashCode()
}

/**
 * A running task has been aborted, as the worker executing it has been shut down. The task may be resumed.
 * Has no arguments, as they are sent to clients, and the worker handle must stay secret.
 */
class BackgroundTaskWorkerShutdown : DomainException(
    status = Status.InternalError,
    code = "background-task-worker-shutdown",
)
