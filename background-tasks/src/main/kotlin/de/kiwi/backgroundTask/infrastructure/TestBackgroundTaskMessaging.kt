package de.kiwi.backgroundTask.infrastructure

import de.kiwi.api.util.slf4j
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessage
import de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private val log = slf4j<TestBackgroundTaskMessaging>()

/**
 * [BackgroundTaskMessaging] recording broadcast messages, allowing to await specific messages.
 * Intended for use in testing and debugging, using messaging to proceed with test logic.
 */
class TestBackgroundTaskMessaging : BackgroundTaskMessaging {
    private val broadcastMessages = mutableListOf<BackgroundTaskMessage>()
    private val awaitingMessages = CopyOnWriteArrayList<AwaitMessage>()

    private fun interface AwaitMessage {
        fun resolve(message: BackgroundTaskMessage)
    }

    fun interface AwaitMessagePredicate<M : BackgroundTaskMessage> {
        fun test(message: M): Boolean
    }

    override fun broadcastMessage(message: BackgroundTaskMessage) {
        log.info("-> Broadcast BackgroundTaskMessage: {}", message)
        synchronized(this) {
            broadcastMessages += message
            awaitingMessages.forEach { it.resolve(message) }
        }
    }

    fun messagesFor(taskHandle: UUID): List<BackgroundTaskMessage> {
        return synchronized(this) {
            broadcastMessages.filter { it.taskHandle == taskHandle }
        }
    }

    fun clear() {
        synchronized(this) {
            broadcastMessages.clear()
            awaitingMessages.clear()
        }
    }

    inline fun <reified M : BackgroundTaskMessage> awaitMessage(
        timeout: Duration,
        taskHandle: UUID? = null,
        predicate: AwaitMessagePredicate<M> = { true },
    ): CompletableFuture<M> {
        return awaitMessage(M::class.java, timeout, taskHandle, predicate)
    }

    fun <M : BackgroundTaskMessage> awaitMessage(
        messageClass: Class<M>,
        timeout: Duration,
        forTaskHandle: UUID? = null,
        predicate: AwaitMessagePredicate<M> = { true },
    ): CompletableFuture<M> {
        // Create new completable future and awaitable
        val future = CompletableFuture<M>()
        val awaiting = AwaitMessage { message ->
            // Check preconditions
            if ((forTaskHandle == null || message.taskHandle == forTaskHandle) && messageClass.isInstance(message)) {
                // Check message predicate
                val typedMessage = messageClass.cast(message)
                if (predicate.test(typedMessage)) {
                    future.complete(typedMessage)
                }
            }
        }

        // Check if message has already been resolved, otherwise add to backlog
        synchronized(this) {
            broadcastMessages.forEach { awaiting.resolve(it) }
            if (!future.isDone) {
                awaitingMessages.add(awaiting)
            }
        }

        // Return completable future with timeout and cleanup
        return future
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete { _, _ -> awaitingMessages.remove(awaiting) }
    }

    fun awaitClosed(
        timeout: Duration,
        forTaskHandle: UUID? = null,
    ): CompletableFuture<BackgroundTaskMessage.StatusChanged> {
        return awaitMessage<BackgroundTaskMessage.StatusChanged>(timeout, forTaskHandle) { message ->
            message.status.isClosed
        }
    }
}
