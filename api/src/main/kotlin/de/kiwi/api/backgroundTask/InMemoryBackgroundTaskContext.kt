package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.UserDescriptor
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory [BackgroundTaskContext] for unit tests of [BackgroundTaskHandlers][BackgroundTaskHandler], without the
 * `kiwi-background-tasks` module, workers, messaging, or a database. Invoke the handler with this context directly,
 * and assert its result and the recorded progress, messages and events:
 *
 * ```kotlin
 * val request = ExportTodoList(todoListId)
 * val context = InMemoryBackgroundTaskContext(request, user = testUser)
 * val result = handler.handle(request, context)
 * assertEquals(100, context.lastProgress?.percent)
 * ```
 *
 * The context behaves like the context of a task executed by a worker, except that everything happens synchronously
 * in the calling thread:
 * - [Progress][reportProgress], [notifications][sendNotification] and [custom messages][sendMessage] are recorded
 *   (unthrottled, in the order they were reported) in [progress], [notifications], and [messages].
 * - The [scratchpad] stores values as JSON, see [InMemoryBackgroundTaskScratchpad].
 * - [Stages][stages] are executed by the [BackgroundTaskStagesRunner], like on a worker: Their checkpoints are stored
 *   in the [scratchpad], and the events recorded by completed stages are collected in [publishedEvents].
 * - [cancel] and [abort] change the [status], so the handler stops at its next [checkActive] (e.g. call them from a
 *   test double invoked by the handler, to cancel the task midway).
 *
 * To test resuming a task, run the handler with a context until it fails, then run it again with the [resumed]
 * context of the next attempt, which shares the scratchpad.
 *
 * Instances are thread-safe, e.g. for handlers processing items in parallel.
 */
class InMemoryBackgroundTaskContext(
    /**
     * Request of the task
     */
    override val request: BackgroundTaskRequest,
    /**
     * User that launched the task, [UserDescriptor.SYSTEM] by default. Users not implementing
     * [BackgroundTaskUserDescriptor] are wrapped with [BackgroundTaskUserDescriptor.delegateOf] (not serialized).
     */
    user: UserDescriptor = UserDescriptor.SYSTEM,
    /**
     * Number of the execution attempt, `1` for the first execution
     */
    override val attempt: Int = 1,
    /**
     * In-memory scratchpad of the task, shared with the context of the next attempt.
     */
    override val scratchpad: InMemoryBackgroundTaskScratchpad = InMemoryBackgroundTaskScratchpad(),
    /**
     * Initial progress of the task when the execution started (of a previous attempt), the minimum progress of stages
     */
    val initialProgress: BackgroundTaskProgress = BackgroundTaskProgress.NONE,
    /**
     * Secret handle of the task, random by default
     */
    override val taskHandle: UUID = UUID.randomUUID(),
) : BackgroundTaskContext {

    /** Notification sent by the handler via [sendNotification] */
    data class Notification(
        /** Message, e.g. a message code */
        val message: String,
        /** Arguments of the message */
        val arguments: Map<String, Any?>,
    )

    /** Custom message sent by the handler via [sendMessage] */
    data class Message(
        /** Type of the message */
        val messageType: String,
        /** Details of the message */
        val details: Any?,
    )

    init {
        require(attempt >= 1) { "Invalid attempt: $attempt" }
    }

    override val user: BackgroundTaskUserDescriptor =
        user as? BackgroundTaskUserDescriptor ?: BackgroundTaskUserDescriptor.delegateOf(user)

    @Volatile
    override var status: BackgroundTaskExecutionStatus = BackgroundTaskExecutionStatus.Running
        private set

    private val reportedProgress = CopyOnWriteArrayList<BackgroundTaskProgress>()
    private val sentNotifications = CopyOnWriteArrayList<Notification>()
    private val sentMessages = CopyOnWriteArrayList<Message>()
    private val stageEvents = CopyOnWriteArrayList<DomainEvent>()
    private val stagesCompleted = CopyOnWriteArrayList<String>()

    /** Progress reported by the handler (or its stages), in the order reported */
    val progress: List<BackgroundTaskProgress>
        get() = reportedProgress.toList()

    /** Percentages of the reported [progress] */
    val progressPercents: List<Int>
        get() = reportedProgress.map { it.percent }

    /** Latest reported progress, `null` if none has been reported */
    val lastProgress: BackgroundTaskProgress?
        get() = reportedProgress.lastOrNull()

    /** Notifications sent by the handler */
    val notifications: List<Notification>
        get() = sentNotifications.toList()

    /** Custom messages sent by the handler */
    val messages: List<Message>
        get() = sentMessages.toList()

    /** Events recorded by the completed [stages], published when each stage was completed */
    val publishedEvents: List<DomainEvent>
        get() = stageEvents.toList()

    /** Names of the [stages] executed and completed in this attempt (excluding stages skipped on resume) */
    val completedStages: List<String>
        get() = stagesCompleted.toList()

    /** Request the task to be canceled: The [status] becomes [BackgroundTaskExecutionStatus.CancelRequested] */
    fun cancel() {
        status = BackgroundTaskExecutionStatus.CancelRequested
    }

    /**
     * Request the execution to be aborted (e.g. the worker shuts down):
     * The [status] becomes [BackgroundTaskExecutionStatus.AbortRequested]
     */
    fun abort() {
        status = BackgroundTaskExecutionStatus.AbortRequested
    }

    /**
     * Context of the next attempt of the task, as if it has been resumed: Same request, user, handle and scratchpad,
     * the [attempt] incremented, and the latest progress of this attempt as its [initialProgress]
     */
    fun resumed() = InMemoryBackgroundTaskContext(
        request = request,
        user = user,
        attempt = attempt + 1,
        scratchpad = scratchpad,
        initialProgress = lastProgress ?: initialProgress,
        taskHandle = taskHandle,
    )

    override fun checkActive() {
        val currentStatus = status
        if (!currentStatus.isActive || Thread.currentThread().isInterrupted) {
            throw InterruptedException("BackgroundTask is not active anymore: $currentStatus")
        }
    }

    override fun reportProgress(progress: BackgroundTaskProgress) {
        reportedProgress.add(progress)
    }

    override fun sendNotification(message: String, arguments: Map<String, Any?>) {
        sentNotifications.add(Notification(message, arguments))
    }

    override fun sendMessage(messageType: String, details: Any?) {
        sentMessages.add(Message(messageType, details))
    }

    override fun <T> stages(declaration: BackgroundTaskStages.Declaration<T>): DomainResult<T> {
        val runner = BackgroundTaskStagesRunner(
            context = this,
            initialProgress = initialProgress,
            completion = ::completeStage,
        )
        // No events, as the events have been published with the stages already (like on a worker)
        return DomainResult.of(runner.runStages(declaration))
    }

    private fun completeStage(stageName: String, resultKey: String?, resultValue: Any?, events: List<DomainEvent>) {
        if (resultKey != null) scratchpad.put(resultKey, resultValue)
        stageEvents.addAll(events)
        stagesCompleted.add(stageName)
    }

    override fun toString() = "InMemoryBackgroundTaskContext(status=$status, attempt=$attempt, request=$request)"
}
