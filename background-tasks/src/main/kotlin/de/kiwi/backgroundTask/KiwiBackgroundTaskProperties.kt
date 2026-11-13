package de.kiwi.backgroundTask

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration properties (prefix `kiwi.background-task`).
 * Properties are `var` to be included in the configuration metadata,
 * see `KiwiFrameworkProperties` of the kiwi-framework module.
 */
@ConfigurationProperties(KiwiBackgroundTaskProperties.PREFIX)
data class KiwiBackgroundTaskProperties(
    /** Enable the background task module */
    var enabled: Boolean = true,
    /** Task queue, distributing launched tasks to the workers */
    val queue: TaskQueue = TaskQueue(),
    /** Task workers of this application instance, executing tasks */
    val worker: TaskWorker = TaskWorker(),
    /** Resolution of the task handlers */
    val handlers: TaskHandlers = TaskHandlers(),
    /** Task messaging, broadcasting messages of running tasks to their subscribed clients */
    val messaging: TaskMessaging = TaskMessaging(),
    /** Management of the tasks by administrators (management endpoints) */
    val management: TaskManagement = TaskManagement(),
    /**
     * Task categories by name, each with its own queue. If empty, only the `default` category is declared.
     * Only used by the RabbitMQ queue provider, the local queue accepts all categories of its local workers.
     */
    var categories: Map<String, TaskCategory> = emptyMap(),
) {
    companion object {
        const val PREFIX = "kiwi.background-task"
        const val ENABLED = "$PREFIX.enabled"
        const val WORKER_ENABLED = "$PREFIX.worker.enabled"
        const val QUEUE_PROVIDER = "$PREFIX.queue.provider"
        const val MESSAGING_PROVIDER = "$PREFIX.messaging.provider"
    }

    /** Provider of the [de.kiwi.backgroundTask.application.BackgroundTaskQueue] */
    enum class TaskQueueProvider {
        /** Local, in-process queue, executing tasks on the workers of the same application instance */
        Local,

        /** RabbitMQ queue, distributing tasks to workers in any application instance connected to the broker */
        RabbitMq,
    }

    /** Provider of the [de.kiwi.backgroundTask.application.messaging.BackgroundTaskMessaging] */
    enum class TaskMessagingProvider {
        /** Spring's simple messaging protocols, e.g. STOMP over WebSockets, using the `SimpMessagingTemplate` */
        Simp,
    }

    /** Configuration of the task queue */
    data class TaskQueue(
        /**
         * Task queue provider, required unless the application declares its own `BackgroundTaskQueue` bean
         */
        var provider: TaskQueueProvider? = null,
        /** Configuration of the RabbitMQ queue provider */
        val rabbitMq: RabbitMq = RabbitMq(),
    ) {
        /** Configuration of the RabbitMQ queue provider: Names of the exchanges and queues, and timeouts */
        data class RabbitMq(
            /** Name of the direct exchange for launching and controlling tasks */
            var exchange: String = "background_tasks",
            /**
             * Name prefix of the task-launch quorum queues, followed by `.<category>`:
             * Retries of failed tasks are published to the category's task-launch queue as delayed messages
             */
            var taskLaunchQueue: String = "launch_task",
            /** Name prefix of the exclusive worker-control queue of each application instance */
            var workerControlQueue: String = "worker_control",
            /** Routing key prefix of task-control messages, followed by `.<taskHandle>` */
            var taskControlBinding: String = "task_control",
            /** Name of the dead-letter exchange, receiving rejected task-launch messages */
            var deadLetterExchange: String = "background_tasks_dlx",
            /** Name of the dead-letter queue retaining rejected task-launch messages of all categories */
            var deadLetterTaskLaunchQueue: String = "launch_task_dlq",
            /** Timeout for a worker to confirm a task-control message, like a cancellation */
            var waitForReplyTimeout: Duration = Duration.ofSeconds(10),
            /**
             * Timeout for the broker to confirm a task-launch message,
             * if publisher confirms are enabled (`spring.rabbitmq.publisher-confirm-type=correlated`)
             */
            var publishConfirmTimeout: Duration = Duration.ofSeconds(10),
        )
    }

    /** Configuration of the task workers of this application instance */
    data class TaskWorker(
        /**
         * Enable the background task workers of this application instance (opt-in).
         * Without workers, the instance only launches tasks, which are executed by other instances' workers.
         * The `local` queue provider requires workers, as it only dispatches tasks to workers of the same instance.
         */
        var enabled: Boolean = false,
        /** Worker instances to create, a single default worker if empty */
        var instances: List<Instance> = emptyList(),
        /**
         * Time to wait for running tasks to finish when the application stops,
         * before they are aborted (interrupted and marked as failed, so they can be resumed)
         */
        var shutdownTimeout: Duration = Duration.ofSeconds(30),
        /**
         * Minimum time between two syncs of the progress of a running task: The latest progress is stored on the task
         * and broadcast at most once per interval, and when the task is closed. Zero disables throttling.
         */
        var progressInterval: Duration = Duration.ofSeconds(1),
        /**
         * Duration of the lease of a worker executing a task, renewed every third of the duration. A running task
         * whose lease expired (e.g. its worker crashed) can be resumed via the `backgroundTasks` management endpoint.
         */
        var leaseDuration: Duration = Duration.ofMinutes(1),
        /**
         * Maximum delay of a retry requested by a task handler after a failure (see `BackgroundTaskHandler.onFailure`),
         * longer delays are capped
         */
        var maxRetryDelay: Duration = Duration.ofDays(1),
    ) {
        /** Configuration of a single worker instance: Its executor, and the task categories it accepts */
        data class Instance(
            /** Threading mode of the worker's executor */
            var threadMode: ThreadMode = ThreadMode.SingleThread,
            /**
             * Maximum number of tasks executed concurrently by the worker (at least `1`), for all thread modes except
             * `SingleThread` (which executes one task at a time). Further tasks assigned to the worker wait in its
             * executor; the RabbitMQ queue assigns at most this number of tasks per category to the worker at once.
             */
            var maxConcurrentTasks: Int = 1,
            /** Category name patterns accepted by the worker (`*` wildcards, case-insensitive), all if empty */
            var includeCategories: Set<String> = emptySet(),
            /** Category name patterns rejected by the worker (`*` wildcards, case-insensitive) */
            var excludeCategories: Set<String> = emptySet(),
        ) {
            override fun toString() = sequence {
                yield("threadMode = ${threadMode.name}")
                if (threadMode != ThreadMode.SingleThread) yield("maxConcurrentTasks = $maxConcurrentTasks")
                yield("include = ${includeCategories.ifEmpty { "*" }}")
                if (excludeCategories.isNotEmpty()) yield("excludes = $excludeCategories")
            }.joinToString(", ")
        }

        /**
         * Threading mode of a worker's executor. Each mode executes at most `maxConcurrentTasks` tasks at once
         * (except [SingleThread]); further submitted tasks wait in the executor's queue until a task is finished.
         */
        enum class ThreadMode {
            /** A single thread, executing one task at a time */
            SingleThread,

            /** A fixed pool of `maxConcurrentTasks` threads, which are kept while the worker runs */
            FixedThreadPool,

            /**
             * A pool creating threads on demand, up to `maxConcurrentTasks` threads,
             * which are terminated after being idle for a minute
             */
            CachedThreadPool,

            /** A virtual thread per running task, up to `maxConcurrentTasks` tasks at once */
            VirtualThreads,
        }
    }

    /** Configuration of the resolution of task handlers */
    data class TaskHandlers(
        /** Cache the resolved `BackgroundTaskHandler` per request class */
        var enableCaching: Boolean = true,
    )

    /** Configuration of the management of the tasks by administrators (management endpoints and statistics) */
    data class TaskManagement(
        /**
         * Time after which a task that doesn't progress is considered stalled, i.e. presumably orphaned (listed by the
         * `backgroundTasks` endpoint with `stalled=true`, counted in the statistics): Pending (created or launched) for
         * longer, or its retry overdue by more. Running tasks are stalled as soon as their worker's lease expired.
         */
        var stalledAfter: Duration = Duration.ofMinutes(10),
    )

    /** Configuration of the task messaging */
    data class TaskMessaging(
        /**
         * Task messaging provider, required for workers unless the application declares its own
         * `BackgroundTaskMessaging` bean
         */
        var provider: TaskMessagingProvider? = null,
        /** Configuration of the Simp messaging provider */
        val simp: Simp = Simp(),
    ) {
        /** Configuration of the Simp messaging provider */
        data class Simp(
            /** Topic name prefix, messages are sent to `/topic/<messageTopic>.<taskHandle>` */
            var messageTopic: String = "backgroundTask",
        )
    }

    /** Configuration of a task category, declaring its RabbitMQ task-launch queue (a durable quorum queue) */
    data class TaskCategory(
        /**
         * Maximum number of pending tasks in the category's queue (`x-max-length`), unlimited if not set.
         * Launching a task fails if the queue is full (`x-overflow: reject-publish`), provided publisher confirms are
         * enabled (`spring.rabbitmq.publisher-confirm-type=correlated`).
         */
        var maxQueueLength: Int? = null,
    )
}
