package de.kiwi.api.backgroundTask

import de.kiwi.api.backgroundTask.BackgroundTaskHandler.Companion.noRetry
import de.kiwi.api.backgroundTask.BackgroundTaskHandler.Companion.retry
import de.kiwi.api.backgroundTask.BackgroundTaskHandler.FailureResponse
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.util.lookupSupertypeArgument
import java.time.Duration
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Handler for processing [BackgroundTaskRequests][BackgroundTaskRequest] of a given type [R],
 * running them as background tasks. Exactly one [BackgroundTaskHandler] bean must exist for any
 * given [BackgroundTaskRequest] type.
 *
 * Handlers are executed by a background task worker, which may run in a different process than the server that
 * launched the task. The [DomainEvents][de.kiwi.api.domain.DomainEvent] recorded in the [DomainResult] returned by
 * any of the handler methods are published on the worker's event bus, on behalf of the user that launched the task.
 *
 * @param R Type of [BackgroundTaskRequest] handled by this instance
 * @see BackgroundTaskContext
 */
interface BackgroundTaskHandler<R : BackgroundTaskRequest> {

    /**
     * Run the background task for the given [BackgroundTaskRequest] of type [R].
     * Use given [BackgroundTaskContext] for communicating with the worker, client, and underlying task entity.
     *
     * The task is completed when this method returns, and the result value is sent to the client, serialized as
     * JSON (`Unit` and `null` are sent as JSON `null`). If the method throws an exception, the task fails and
     * [onFailure] is invoked. When the task is canceled while running, the executing thread is interrupted:
     * The handler should let the resulting [InterruptedException] propagate (or call
     * [BackgroundTaskContext.checkActive] in long computations), after which [onCancel] is invoked.
     *
     * A failed task may be retried (see [onFailure]), and a failed task (or a task whose worker crashed) may be
     * resumed by an administrator: Both execute this method again with an incremented
     * [BackgroundTaskContext.attempt]. Handlers with side effects should record their progress in the
     * [BackgroundTaskContext.scratchpad] (or use [stages][BackgroundTaskContext.stages]), so a retried or resumed task
     * doesn't repeat them.
     */
    fun handle(
        request: R,
        context: BackgroundTaskContext,
    ): DomainResult<*>

    /**
     * Callback on task failure, invoked with the [exception] thrown in [handle] (or a `BackgroundTaskWorkerShutdown`
     * exception, if the worker aborted the task when it was shut down).
     *
     * The [FailureResponse] returned by this handler determines whether the task is retried:
     * - [noRetry]: The task fails. It may still be resumed by an administrator.
     * - [retry] (or [retryWithBackoff]): The task waits as [BackgroundTaskStatus.RetryScheduled] for the given delay,
     *   then a worker executes [handle] again as the next [attempt][BackgroundTaskContext.attempt], with the
     *   [scratchpad][BackgroundTaskContext.scratchpad] and stage checkpoints of the failed attempt retained.
     *
     * Retries are at-least-once, and the delay is a minimum: A due retry waits for a free worker, or it may start
     * earlier if a worker crashed after the failure. If this method throws, the task fails.
     */
    fun onFailure(
        request: R,
        exception: Exception,
        context: BackgroundTaskContext,
    ): DomainResult<FailureResponse>

    /**
     * Callback on task cancellation, invoked if the worker receives a signal
     * to cancel the currently running background task, after [handle] has been interrupted.
     * It is also invoked if the task was canceled before a worker started it, in which case [handle] is never invoked.
     */
    fun onCancel(
        request: R,
        context: BackgroundTaskContext,
    ): DomainResult<Unit>

    /**
     * Returns `true` if this BackgroundTaskHandler is able to handle the given request instance,
     * by default if it is an instance of the type implemented by the handler class.
     */
    fun canHandle(request: BackgroundTaskRequest): Boolean {
        val handledType = javaClass.lookupSupertypeArgument<BackgroundTaskHandler<*>>(0)
        return handledType.isInstance(request)
    }

    /**
     * Response of [onFailure], determining whether the failed task should be retried after [retryDelay].
     * @see noRetry
     * @see retry
     * @see retryWithBackoff
     * @see limitRetries
     */
    data class FailureResponse(
        /**
         * `true` if the failed task should be retried, `false` if it fails
         */
        val shouldRetry: Boolean,
        /**
         * Minimum delay before retrying the failed task (not negative), capped by the worker's maximum retry delay
         * (`kiwi.background-task.worker.max-retry-delay`)
         */
        val retryDelay: Duration,
    ) {
        init {
            require(!retryDelay.isNegative) { "Negative retry delay: $retryDelay" }
        }
    }

    companion object {

        /** [FailureResponse] dropping the failed task */
        fun noRetry() = FailureResponse(shouldRetry = false, retryDelay = Duration.ZERO)

        /** [FailureResponse] retrying the failed task after the given [delay] */
        fun retry(delay: Duration = Duration.ZERO) = FailureResponse(shouldRetry = true, retryDelay = delay)

        /** [FailureResponse] retrying the failed task after the given number of [seconds] */
        fun retryInSeconds(seconds: Long) = retry(delay = Duration.ofSeconds(seconds))
    }
}

/**
 * [BackgroundTaskHandler.FailureResponse] with a limited number of attempts:
 *
 * The failed attempt `n` (see [BackgroundTaskContext.attempt]) is retried according to [doRetry]
 * unless it was the last of [maxAttempts]. For example, `maxAttempts = 4`, the task will be retried according to
 * [doRetry] 3 times before `shouldRetry = false` is returned.
 *
 * Note: Resuming a task (by an administrator) increments its attempt as well, so the attempts of a resumed task
 * count towards [maxAttempts].
 *
 * @param maxAttempts Maximum number of attempts (at least `1`)
 */
inline fun BackgroundTaskContext.limitRetries(
    maxAttempts: Int,
    doRetry: BackgroundTaskContext.() -> FailureResponse,
): FailureResponse {
    require(maxAttempts >= 1) { "Invalid maximum number of attempts: $maxAttempts" }

    // Only evaluate retry until max attempts are exhausted
    return if (attempt >= maxAttempts) noRetry()
    else doRetry()
}

/**
 * [FailureResponse] based on exponential backoff:
 *
 * The failed attempt `n` (see [BackgroundTaskContext.attempt]) is retried after `baseDelay * exponent^(n - 1)`,
 * capped by [maxDelay]. For example, with `baseDelay = 10s` and the default exponent `2.0`, the task is retried
 * after 10s, 20s, 40s, and so on.
 *
 * Note: Resuming a task (by an administrator) increments its attempt as well, so the delays of a resumed task
 * continue to grow.
 *
 * @param baseDelay Delay after the first attempt (not negative)
 * @param exponent Growth factor of the delay per attempt (at least `1.0`)
 * @param maxDelay Maximum delay (not negative), `null` for no maximum (the worker caps the delay)
 */
fun BackgroundTaskContext.retryWithBackoff(
    baseDelay: Duration,
    exponent: Double = 2.0,
    maxDelay: Duration? = null,
): FailureResponse {
    require(!baseDelay.isNegative) { "Negative base delay: $baseDelay" }
    require(exponent >= 1.0) { "Invalid exponent: $exponent" }
    require(maxDelay == null || !maxDelay.isNegative) { "Negative maximum delay: $maxDelay" }

    // Without base delay, the delay stays zero (avoids 0 * Infinity = NaN for very high attempts)
    if (baseDelay.isZero) return retry(Duration.ZERO)

    // Calculate delay with exponential backoff (saturated at Long.MAX_VALUE milliseconds, if not capped)
    val retries = (attempt - 1).coerceAtLeast(0)
    val exponentialMillis = baseDelay.toMillis() * exponent.pow(retries)
    val delayMillis = maxDelay?.let { min(exponentialMillis, it.toMillis().toDouble()) } ?: exponentialMillis
    return retry(Duration.ofMillis(delayMillis.roundToLong()))
}
