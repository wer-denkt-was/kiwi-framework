package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.backgroundTask.BackgroundTaskDescriptor
import de.kiwi.api.backgroundTask.BackgroundTaskError
import de.kiwi.api.backgroundTask.BackgroundTaskUserDescriptor
import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.BackgroundTaskWorker
import de.kiwi.backgroundTask.application.BackgroundTaskWorker.ExecutionOutcome
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import tools.jackson.databind.node.NullNode
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private val log = slf4j<TestBackgroundTaskWorker>()

/**
 * [BackgroundTaskWorker] recording submissions and cancellations, for testing
 * [de.kiwi.backgroundTask.application.BackgroundTaskQueue] implementations.
 *
 * Submitted tasks keep running until their [Submission.future] is completed (e.g. with [ExecutionOutcome.Retry] to
 * request a retry), or they are canceled.
 *
 * @param failSubmit Predicate for descriptors whose submission throws an exception
 */
class TestBackgroundTaskWorker(
    private val name: String,
    private val acceptedCategories: Set<String>,
    private val messaging: BackgroundTaskMessaging,
    override val maxConcurrentTasks: Int = 1,
    private val failSubmit: (BackgroundTaskDescriptor) -> Boolean = { false },
) : BackgroundTaskWorker {
    private val submissions = mutableListOf<Submission>()
    private val cancellations = mutableListOf<Cancellation>()
    private val submitAttempts = mutableListOf<UUID>()
    private val awaitingSubmissions = mutableMapOf<UUID, CompletableFuture<Submission>>()
    private val awaitingCancellations = mutableMapOf<UUID, CompletableFuture<Cancellation>>()

    override val workerHandle: UUID = UUID.randomUUID()

    fun getSubmissions() = synchronized(this) { submissions.toList() }

    fun getCancellations() = synchronized(this) { cancellations.toList() }

    /** Number of times the task has been submitted to this worker, including failed submissions */
    fun submitAttempts(taskHandle: UUID) = synchronized(this) { submitAttempts.count { it == taskHandle } }

    fun awaitSubmission(taskHandle: UUID, timeout: Duration): CompletableFuture<Submission> = synchronized(this) {
        log.info("[{}] Awaiting submission for {} ...", name, taskHandle)

        val future = CompletableFuture<Submission>()
        submissions
            .find { it.descriptor.taskHandle == taskHandle }
            ?.let { future.complete(it) }
            ?: awaitingSubmissions.put(taskHandle, future)

        future.orTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    /** Wait until the task has been submitted to this worker the given number of times (including failed submissions) */
    fun awaitSubmitAttempts(taskHandle: UUID, count: Int, timeout: Duration) {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        while (submitAttempts(taskHandle) < count) {
            check(System.nanoTime() < deadline) {
                "[$name] Task $taskHandle not submitted $count times within $timeout: ${submitAttempts(taskHandle)}"
            }
            Thread.sleep(20)
        }
    }

    fun awaitCancellation(taskHandle: UUID, timeout: Duration): CompletableFuture<Cancellation> = synchronized(this) {
        log.info("[{}] Awaiting cancellation for {} ...", name, taskHandle)
        val future = CompletableFuture<Cancellation>()
        cancellations.find { it.taskHandle == taskHandle }?.let { future.complete(it) }
            ?: awaitingCancellations.put(taskHandle, future)
        future.orTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    override fun acceptsCategory(categoryName: String): Boolean = categoryName in acceptedCategories

    override fun submit(
        descriptor: BackgroundTaskDescriptor,
        user: BackgroundTaskUserDescriptor,
    ): CompletableFuture<ExecutionOutcome> {
        val taskHandle = descriptor.taskHandle
        synchronized(this) {
            submitAttempts.add(taskHandle)
        }

        if (failSubmit(descriptor)) {
            log.info("[{}] Failing submission of {}", name, taskHandle)
            throw IllegalStateException("Submission of $taskHandle failed")
        }

        val submission = Submission(descriptor, user, CompletableFuture())
        synchronized(this) {
            log.info("[{}] Submitted {}", name, descriptor)
            submissions.add(submission)
            awaitingSubmissions.remove(taskHandle)?.complete(submission)
        }

        messaging.broadcastMessage(BackgroundTaskMessage.StatusChanged.Started(taskHandle, attempt = 1))

        return submission.future.whenComplete { _, ex ->
            messaging.broadcastMessage(
                if (ex == null) BackgroundTaskMessage.StatusChanged.Completed(taskHandle, NullNode.instance)
                else BackgroundTaskMessage.StatusChanged.Failed(taskHandle, BackgroundTaskError.of(ex)),
            )
        }
    }

    override fun cancel(taskHandle: UUID, user: BackgroundTaskUserDescriptor): Boolean {
        val (submission, cancellation) = synchronized(this) {
            // The latest delivery still running (e.g. a retry), the latest delivery otherwise
            val taskSubmissions = submissions.filter { it.descriptor.taskHandle == taskHandle }
            val submission = taskSubmissions.lastOrNull { !it.future.isDone } ?: taskSubmissions.lastOrNull()
            val cancellation = Cancellation(taskHandle, user, submission)
            cancellations.add(cancellation)
            awaitingCancellations.remove(taskHandle)?.complete(cancellation)
            submission to cancellation
        }
        log.info("[{}] Canceled {}", name, cancellation)

        if (submission == null || submission.future.isDone) return false
        messaging.broadcastMessage(BackgroundTaskMessage.StatusChanged.Canceled(taskHandle))
        submission.future.cancel(true)
        return true
    }

    /** Rejected tasks, by handle */
    val rejections = java.util.concurrent.ConcurrentHashMap<UUID, Exception>()

    override fun reject(taskHandle: UUID, exception: Exception) {
        log.info("[{}] Rejected {}: {}", name, taskHandle, exception.message)
        rejections[taskHandle] = exception
    }

    override fun shutdown(timeout: java.time.Duration) {
        close()
    }

    override fun close() {
        synchronized(this) {
            submissions.forEach { it.future.cancel(true) }
            awaitingSubmissions.values.forEach { it.cancel(true) }
            awaitingCancellations.values.forEach { it.cancel(true) }
        }
    }

    override fun toString() = "TestBackgroundTaskWorker($name)"

    data class Submission(
        val descriptor: BackgroundTaskDescriptor,
        val user: BackgroundTaskUserDescriptor,
        val future: CompletableFuture<ExecutionOutcome>,
    ) {
        /** Complete the execution of the task with the given [outcome] */
        fun complete(outcome: ExecutionOutcome = ExecutionOutcome.Executed) = future.complete(outcome)
    }

    data class Cancellation(
        val taskHandle: UUID,
        val user: BackgroundTaskUserDescriptor,
        val submission: Submission?,
    )
}
