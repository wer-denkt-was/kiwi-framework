package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.*
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.BackgroundTaskLifecycleManager
import de.kiwi.backgroundTask.application.BackgroundTaskLifecycleManager.CloseResult
import de.kiwi.backgroundTask.application.BackgroundTaskQueue
import de.kiwi.backgroundTask.application.BackgroundTaskResolver
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import de.kiwi.backgroundTask.application.BackgroundTaskWorkerShutdown
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import de.kiwi.backgroundTask.application.messaging.broadcastSafely
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.NullNode
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KFunction

private val log = slf4j<TaskExecution>()

/**
 * Dependencies and configuration of the task executions of a worker
 */
internal class TaskExecutionEnvironment(
    val workerName: String,
    val lifecycle: BackgroundTaskLifecycleManager,
    val messaging: BackgroundTaskMessaging,
    val resolver: BackgroundTaskResolver,
    val objectMapper: ObjectMapper,
    /** Minimum interval between progress-update sync and messages */
    val progressInterval: Duration,
    /** Duration a worker holds a lease on a task, before its lease expires */
    val leaseDuration: Duration,
    /** Maximum delay of a retry requested by a handler, longer delays are capped */
    val maxRetryDelay: Duration,
) {
    /** Interval of renewing the lease of a running task, a third of the lease duration */
    val leaseRenewalInterval: Duration = leaseDuration.dividedBy(3)
}

/** Control of a task execution, by the worker and the execution's context */
internal interface TaskExecutionControl {
    /** Reason for aborting an execution */
    enum class AbortReason {
        /** The worker is shutting down: The task is marked as failed, and may be resumed */
        Shutdown,

        /** The task has been resumed by another execution: The task is left as it is */
        Superseded,
    }

    /** Current status of the execution: Either running, or cancel/abort requested */
    val status: BackgroundTaskExecutionStatus

    /** Request cancellation, interrupting the executing thread */
    fun requestCancel()

    /** Request abortion for the given reason, interrupting the executing thread */
    fun requestAbort(reason: AbortReason)
}

/**
 * Execution of a single task by a worker, run by the worker's executor:
 * 1. Start: The task is marked as running as a new attempt of the worker, unless it has been canceled before
 *    (then [BackgroundTaskHandler.onCancel] is invoked), or it is not in a runnable status anymore
 *    (e.g. redelivered, then it is skipped).
 * 2. Handle: [BackgroundTaskHandler.handle] is invoked outside a transaction, supervised by the [TaskExecutionContext].
 * 3. Close: The task is marked as completed, canceled (if cancellation was requested, or the thread interrupted), or
 *    failed (if the handler threw, or the worker shut down). If the handler's [BackgroundTaskHandler.onFailure]
 *    requested a retry, the failed task waits for its retry instead, and the execution completes with
 *    [ExecutionOutcome.Retry], so the queue enqueues it again. A superseded execution leaves the task as it is.
 *
 * Status messages are broadcast after the respective transaction has been committed.
 *
 * The [completion] distinguishes failures to *launch* the task (before step 1 has been completed, e.g. the task is
 * unknown, or no handler can be resolved), which complete it exceptionally (e.g. dead-lettering the launch message),
 * from failures during the *execution* (steps 2 and 3), which are recorded with the task (or detected by its expired
 * lease), and complete it normally (with the [ExecutionOutcome]).
 */
internal class TaskExecution(
    val descriptor: BackgroundTaskDescriptor,
    val user: BackgroundTaskUserDescriptor,
    private val env: TaskExecutionEnvironment,
    private val onFinished: (TaskExecution) -> Unit,
) : Runnable, TaskExecutionControl {

    /**
     * Completes when the execution has finished:
     * Exceptionally if the task couldn't be launched, normally otherwise
     * (also if the task failed during its execution, as the failure has been recorded with the task),
     * with [ExecutionOutcome.Retry] if the task waits for its retry
     */
    val completion = CompletableFuture<ExecutionOutcome>()

    private val taskHandle get() = descriptor.taskHandle
    private val state = AtomicReference(BackgroundTaskExecutionStatus.Running)

    /** Outcome of the execution, [ExecutionOutcome.Retry] once the task's retry has been scheduled */
    @Volatile
    private var outcome: ExecutionOutcome = ExecutionOutcome.Executed

    /** Set if task execution is canceled or aborted by [requestCancel]/[requestAbort] */
    @Volatile
    private var abortReason: TaskExecutionControl.AbortReason? = null

    private val threadLock = Any()
    private var thread: Thread? = null

    override val status: BackgroundTaskExecutionStatus
        get() = state.get()

    override fun requestCancel() {
        // Interrupt thread if status atomically changes from "running" to "cancel requested"
        if (state.compareAndSet(BackgroundTaskExecutionStatus.Running, BackgroundTaskExecutionStatus.CancelRequested)) {
            interruptThread()
        }
    }

    override fun requestAbort(reason: TaskExecutionControl.AbortReason) {
        // Atomically set the indicated abort-reason, unless another reason has already been assigned
        synchronized(threadLock) {
            if (abortReason == null) abortReason = reason
        }
        // Always force the task to be aborted and interrupted
        state.set(BackgroundTaskExecutionStatus.AbortRequested)
        interruptThread()
    }

    /** Complete an execution that has been dropped from the executor without being started */
    fun dropNotStarted(workerHandle: UUID) {
        completion.completeExceptionally(RejectedExecutionException("Worker [$workerHandle] has been shut down"))
        onFinished(this)
    }

    //#region Runs in worker ExecutorService thread

    override fun run() {
        // Assign currently executing thread at start of execution
        assignThread(Thread.currentThread())

        // Local mutable state, indicating the launch/execution state of the task
        var launched = false
        var launchFailure: Throwable? = null
        try {
            // Exceptions in preparation will lead to exceptional task completion
            val prepared = prepare()
            // Exceptions in execution will lead to task failure, but regular completion (i.e. no dead-lettering)
            launched = true
            execute(prepared)
        } catch (ex: Throwable) {
            if (launched) {
                // Failure during the execution, e.g. closing the task failed: The task is left to its lease
                log.error(
                    "Execution of BackgroundTask [{}] by worker [{}] failed: {}",
                    taskHandle, env.workerName, ex.message, ex,
                )
            } else {
                // Failure during launch, i.e. task has not been running yet before Exception was thrown
                launchFailure = ex
            }
            // Re-throw underlying JVM errors
            if (ex is VirtualMachineError) {
                throw ex
            }
        } finally {
            // Unregister thread, and clear an interrupt that may have been requested in the meantime,
            // so it neither leaks into the completion callbacks (e.g. acknowledging the launch message),
            // nor into the next task executed by this thread
            assignThread(null)
            Thread.interrupted()

            // Report exceptional completion if task did not launch, regular completion otherwise
            if (launchFailure == null) completion.complete(outcome)
            else completion.completeExceptionally(launchFailure)

            // Callback that task finished, invoked *after* reporting completion for cleanup
            onFinished(this)
        }
    }

    private data class TaskExecutionPrepared(
        val taskId: BackgroundTaskId,
        val started: BackgroundTaskLifecycleManager.StartResult,
        val handler: BackgroundTaskHandler<BackgroundTaskRequest>,
    )

    private fun prepare(): TaskExecutionPrepared {
        // Lookup internal task ID from handle UUID
        val taskId = env.lifecycle.findTaskId(taskHandle)
            ?: throw BackgroundTaskQueue.UnknownTaskHandle(taskHandle)

        // Resolve task-handler from request,
        // throw Exception *before* marking task launched if request could not be resolved
        val handler = try {
            env.resolver.resolve(descriptor.request)
        } catch (ex: Exception) {
            log.error("No handler for BackgroundTask #{} [{}]: {}", taskId, taskHandle, ex.message, ex)
            val result = env.lifecycle.failUnstartedTask(taskHandle, ex)
            if (result != null && result.isClosedByCall) {
                broadcast(BackgroundTaskMessage.StatusChanged.Failed(taskHandle, BackgroundTaskError.of(ex)))
            }
            throw ex
        }

        // Start task, marking the execution as launched
        val started = env.lifecycle.startTask(
            taskId = taskId,
            workerName = env.workerName,
            leaseDuration = env.leaseDuration,
            user = user,
        )
        return TaskExecutionPrepared(taskId, started, handler)
    }

    private fun execute(prepared: TaskExecutionPrepared) {
        val (taskId, started, handler) = prepared

        // Instantiate context connecting task handler and control, then begin execution
        val context = TaskExecutionContext(
            taskId = taskId,
            attempt = started.attempt,
            initialProgress = started.progress,
            descriptor = descriptor,
            user = user,
            control = this,
            env = env,
        )
        when (started.status) {
            // Running: Execute handler
            BackgroundTaskStatus.Running -> {
                executeHandler(handler, context)
            }
            // Canceled before it was ever started: Invoke the handler's cancellation callback
            BackgroundTaskStatus.Canceled if started.attempt == 0 -> {
                executeCancel(handler, context, attempt = null)
            }
            // Canceled while waiting for its retry (or since it has been resumed): Broadcast the cancellation, which
            // couldn't be broadcast before, but don't invoke the cancellation callback for a task started before
            BackgroundTaskStatus.Canceled if started.isCanceledWhilePending -> {
                log.info(
                    "BackgroundTask #{} [{}] has been canceled while waiting for attempt {}",
                    taskId, taskHandle, started.attempt + 1,
                )
                broadcast(BackgroundTaskMessage.StatusChanged.Canceled(taskHandle))
            }
            // Otherwise not runnable (e.g. a stale message of a task closed already): Skip it
            else -> {
                log.warn(
                    "BackgroundTask #{} [{}] is not runnable in status {}, skipped by worker [{}]",
                    taskId, taskHandle, started.status, env.workerName,
                )
            }
        }
    }

    private fun executeHandler(
        handler: BackgroundTaskHandler<BackgroundTaskRequest>,
        context: TaskExecutionContext,
    ) {
        // Run supervisor thread and send started-message
        context.startSupervision()
        broadcast(BackgroundTaskMessage.StatusChanged.Started(taskHandle, context.attempt))

        val handlerResult = try {
            handler.handle(descriptor.request, context)
        } catch (ex: Exception) {
            // Thread.interrupted *must* be called first to clear the Thread interrupt flag.
            // If the Thread interrupt flag were set, cleanup operations using database sockets would fail!
            val interrupted = Thread.interrupted() || ex.isCausedByInterrupt()
            handleExecutionException(handler, context, ex, interrupted)
            return // halt execution, don't complete task
        } catch (err: Error) {
            // Errors bypass the handler's failure callback, but must still close the task if possible
            if (err !is VirtualMachineError) closeFailed(context, err, failureResult = null)
            throw err
        }

        // The handler completed successfully: Detach the thread, so a cancellation or abort requested from now on
        // (e.g. by a worker shutdown) doesn't interrupt closing the task, then clear an interrupt requested while the
        // handler returned, as closing the task requires (interruptible) database I/O.
        // Exceptions closing the task are not handler failures: They propagate, leaving the task to its lease.
        assignThread(null)
        Thread.interrupted()
        closeCompleted(context, handlerResult)
    }

    private fun handleExecutionException(
        handler: BackgroundTaskHandler<BackgroundTaskRequest>,
        context: TaskExecutionContext,
        exception: Exception,
        interrupted: Boolean,
    ) = when {
        // Task has been resumed by another execution: Leave it as it is
        abortReason == TaskExecutionControl.AbortReason.Superseded -> {
            closeSuperseded(context)
        }
        // Task was canceled
        status == BackgroundTaskExecutionStatus.CancelRequested -> {
            executeCancel(handler, context, context.attempt)
        }
        // Task was aborted by worker shutdown: Fail the task, so it may be resumed
        abortReason == TaskExecutionControl.AbortReason.Shutdown -> {
            log.warn(
                "BackgroundTask #{} [{}] aborted by shutdown of worker [{}]",
                context.taskId, taskHandle, env.workerName,
            )
            val shutdown = BackgroundTaskWorkerShutdown()
            shutdown.initCause(exception)
            executeFailure(handler, context, shutdown)
        }
        // Task was interrupted otherwise: Treat like a cancellation
        interrupted -> {
            executeCancel(handler, context, context.attempt)
        }
        // Task failed with a handler exception
        else -> {
            log.error(
                "Exception in BackgroundTask #{} [{}], run by worker [{}]: {}",
                context.taskId, taskHandle, env.workerName, exception.message, exception,
            )
            // The failure is recorded with the task, the execution completes normally
            executeFailure(handler, context, exception)
        }
    }

    private fun executeFailure(
        handler: BackgroundTaskHandler<BackgroundTaskRequest>,
        context: TaskExecutionContext,
        exception: Exception,
    ) {
        // Run failure-callback and close task as failed
        val failureResult = context.safeCallback(handler::onFailure) { it(descriptor.request, exception, context) }
        closeFailed(context, exception, failureResult)
    }

    private fun executeCancel(
        handler: BackgroundTaskHandler<BackgroundTaskRequest>,
        context: TaskExecutionContext,
        attempt: Int?,
    ) {
        val cancelResult = context.safeCallback(handler::onCancel) { it(descriptor.request, context) }

        val progress = context.close()
        val result = env.lifecycle.cancelTask(
            taskId = context.taskId,
            attempt = attempt,
            events = cancelResult?.events.orEmpty(),
            progress = progress,
            user = user,
        )
        broadcastClosed(context, result, progress) {
            BackgroundTaskMessage.StatusChanged.Canceled(taskHandle)
        }
    }

    private fun closeCompleted(context: TaskExecutionContext, handlerResult: DomainResult<*>) {
        val progress = context.close()
        val completionResult = completionResultJson(context, handlerResult.value)
        val result = env.lifecycle.completeTask(
            taskId = context.taskId,
            attempt = context.attempt,
            resultValue = completionResult,
            events = handlerResult.events,
            progress = progress,
            user = user,
        )
        broadcastClosed(context, result, progress) {
            BackgroundTaskMessage.StatusChanged.Completed(taskHandle, completionResult)
        }
    }

    private fun closeFailed(
        context: TaskExecutionContext,
        exception: Throwable,
        failureResult: DomainResult<BackgroundTaskHandler.FailureResponse>?,
    ) {
        // Check if the handler requested a retry
        val failureResponse = failureResult?.value
        if (failureResponse != null && failureResponse.shouldRetry) {
            val delay = retryDelay(context, failureResponse)
            closeForRetry(context, exception, failureResult.events, delay)
        } else {
            val events = failureResult?.events.orEmpty()
            closeFailedNoRetry(context, exception, events)
        }
    }

    // Note: This call may mutate the execution outcome to request a retry
    private fun closeForRetry(
        context: TaskExecutionContext,
        exception: Throwable,
        events: List<DomainEvent>,
        delay: Duration,
    ) {
        val progress = context.close()
        val result = env.lifecycle.scheduleRetry(
            taskId = context.taskId,
            attempt = context.attempt,
            exception = exception,
            retryDelay = delay,
            events = events,
            progress = progress,
            user = user,
        )
        if (result.isClosedByCall) {
            outcome = ExecutionOutcome.Retry(delay)
            log.info(
                "BackgroundTask #{} [{}]: Attempt {} failed, retry scheduled in {}",
                context.taskId, taskHandle, context.attempt, delay,
            )
        }
        broadcastClosed(context, result, progress) {
            BackgroundTaskMessage.StatusChanged.RetryScheduled(
                taskHandle = taskHandle,
                error = BackgroundTaskError.of(exception),
                attempt = context.attempt,
                retryOn = result.retryOn,
            )
        }
    }

    private fun closeFailedNoRetry(
        context: TaskExecutionContext,
        exception: Throwable,
        events: List<DomainEvent>,
    ) {
        val progress = context.close()
        val result = env.lifecycle.failTask(
            taskId = context.taskId,
            attempt = context.attempt,
            exception = exception,
            events = events,
            progress = progress,
            user = user,
        )
        broadcastClosed(context, result, progress) {
            BackgroundTaskMessage.StatusChanged.Failed(
                taskHandle = taskHandle,
                error = BackgroundTaskError.of(exception),
            )
        }
    }

    /** Delay of the retry requested by the handler, capped by the maximum retry delay */
    private fun retryDelay(
        context: TaskExecutionContext,
        response: BackgroundTaskHandler.FailureResponse,
    ): Duration {
        val requested = response.retryDelay
        if (requested > env.maxRetryDelay) {
            log.warn(
                "BackgroundTask #{} [{}] requested a retry in {}, capped to the maximum retry delay {}",
                context.taskId, taskHandle, requested, env.maxRetryDelay,
            )
            return env.maxRetryDelay
        }
        return requested.coerceAtLeast(Duration.ZERO)
    }

    private fun closeSuperseded(context: TaskExecutionContext) {
        context.close()
        log.warn(
            "BackgroundTask #{} [{}]: Attempt {} of worker [{}] has been superseded, left the task unchanged",
            context.taskId, taskHandle, context.attempt, env.workerName,
        )
    }

    /** Broadcast the final progress and status message after closing the task, as far as this execution closed it */
    private inline fun broadcastClosed(
        context: TaskExecutionContext,
        result: CloseResult,
        progress: BackgroundTaskProgress?,
        getStatusMessage: () -> BackgroundTaskMessage.StatusChanged,
    ) {
        when {
            result.isSuperseded -> log.warn(
                "BackgroundTask #{} [{}]: Attempt {} of worker [{}] has been superseded, left the task {}",
                context.taskId, taskHandle, context.attempt, env.workerName, result.status,
            )

            result.isClosedByCall -> {
                if (result.progressChanged && progress != null) {
                    broadcast(BackgroundTaskMessage.Progress(taskHandle, progress))
                }
                broadcast(getStatusMessage())
            }

            // Canceled concurrently (e.g. by the service, after the handler returned)
            result.status == BackgroundTaskStatus.Canceled -> {
                broadcast(BackgroundTaskMessage.StatusChanged.Canceled(taskHandle))
            }

            else -> log.warn(
                "BackgroundTask #{} [{}] has been closed as {} before",
                context.taskId, taskHandle, result.status,
            )
        }
    }

    private fun completionResultJson(context: TaskExecutionContext, value: Any?): JsonNode {
        if (value == null || value == Unit) {
            return NullNode.instance
        }
        return try {
            env.objectMapper.valueToTree(value)
        } catch (ex: Exception) {
            log.warn(
                "Failed to serialize result of BackgroundTask #{} [{}]: {}",
                context.taskId, taskHandle, ex.message, ex,
            )
            NullNode.instance
        }
    }

    private fun broadcast(message: BackgroundTaskMessage) {
        env.messaging.broadcastSafely(message)
    }

    private fun assignThread(thread: Thread?) {
        synchronized(threadLock) { this.thread = thread }
    }

    private fun interruptThread() {
        synchronized(threadLock) { thread?.interrupt() }
    }

    //#endregion
}

/** `true` if this exception has been caused by interrupting the executing thread */
private tailrec fun Throwable.isCausedByInterrupt(): Boolean = when (this) {
    // Exception types associated with interrupts
    is InterruptedException -> true
    is InterruptedIOException -> true
    is ClosedByInterruptException -> true
    // Check cause of throwable recursively
    else -> {
        val parent = cause ?: return false
        parent !== this && parent.isCausedByInterrupt()
    }
}

/** Invoke a handler callback, logging (instead of propagating) exceptions so the task is closed regardless */
private inline fun <F : KFunction<R>, R> TaskExecutionContext.safeCallback(function: F, invocation: (F) -> R): R? {
    return try {
        invocation(function)
    } catch (ex: Exception) {
        Thread.interrupted()
        log.error(
            "Exception in {} of BackgroundTask #{} [{}]: {}",
            function.name, taskId, taskHandle, ex.message, ex,
        )
        null
    }
}
