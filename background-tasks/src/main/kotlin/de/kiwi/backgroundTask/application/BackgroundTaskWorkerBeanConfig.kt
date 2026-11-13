package de.kiwi.backgroundTask.application

import de.kiwi.backgroundTask.KiwiBackgroundTaskProperties.TaskWorker.ThreadMode
import org.springframework.util.PatternMatchUtils
import java.time.Duration
import java.util.concurrent.*

/**
 * Configuration of a [BackgroundTaskWorkerBean], combining the configuration of the worker instance
 * (`kiwi.background-task.worker.instances[n]`) and the common worker configuration (`kiwi.background-task.worker`)
 */
data class BackgroundTaskWorkerBeanConfig(
    /** Threading mode of the worker's executor */
    val threadMode: ThreadMode,
    /** Maximum number of concurrently executed tasks (at least `1`), ignored by the single thread mode */
    val maxConcurrentTasks: Int,
    /** Category name patterns accepted by the worker (`*` wildcards, case-insensitive), all if empty */
    val includeCategories: Set<String>,
    /** Category name patterns rejected by the worker (`*` wildcards, case-insensitive) */
    val excludeCategories: Set<String>,
    /** Minimum time between two syncs of the progress of a running task */
    val progressInterval: Duration,
    /** Duration of the lease of a running task, renewed every third of the duration */
    val leaseDuration: Duration,
    /** Maximum delay of a retry requested by a task handler, longer delays are capped */
    val maxRetryDelay: Duration = Duration.ofDays(1),
) {
    init {
        require(maxConcurrentTasks >= 1) { "Invalid maximum number of concurrent tasks: $maxConcurrentTasks" }
        require(!maxRetryDelay.isNegative) { "Invalid maximum retry delay: $maxRetryDelay" }
    }

    /** Maximum number of tasks executed concurrently by a worker with this configuration */
    fun effectiveMaxConcurrentTasks(): Int = when (threadMode) {
        ThreadMode.SingleThread -> 1
        else -> maxConcurrentTasks
    }

    /** Create an [java.util.concurrent.ExecutorService] based on this configuration */
    fun createExecutor(): ExecutorService {
        return when (threadMode) {
            ThreadMode.SingleThread -> Executors.newSingleThreadExecutor()
            ThreadMode.FixedThreadPool -> Executors.newFixedThreadPool(effectiveMaxConcurrentTasks())
            ThreadMode.CachedThreadPool -> newBoundedCachedThreadPool()
            ThreadMode.VirtualThreads -> newBoundedVirtualThreadPool()
        }
    }

    /** `true` if the category is not excluded, and included (or no inclusions are configured) */
    fun acceptsCategory(category: String): Boolean = when {
        isCategoryExcluded(category) -> false
        includeCategories.isEmpty() -> true
        else -> isCategoryIncluded(category)
    }

    private fun isCategoryExcluded(category: String): Boolean = excludeCategories.any { pattern ->
        PatternMatchUtils.simpleMatchIgnoreCase(pattern, category)
    }

    private fun isCategoryIncluded(category: String): Boolean = includeCategories.any { pattern ->
        PatternMatchUtils.simpleMatchIgnoreCase(pattern, category)
    }

    private fun newBoundedCachedThreadPool(): ExecutorService {
        return newBoundedExecutor(
            keepAlive = Duration.ofMinutes(1), // cache OS Threads for one minute
            threadFactory = Executors.defaultThreadFactory(), // use OS Threads
        )
    }

    private fun newBoundedVirtualThreadPool(): ExecutorService {
        return newBoundedExecutor(
            keepAlive = Duration.ofSeconds(1), // don't keep Virtual Threads around
            threadFactory = Thread.ofVirtual().factory(), // use Virtual Threads
        )
    }

    /**
     * Executor with up to [effectiveMaxConcurrentTasks] threads created on demand by the [threadFactory],
     * terminated after being idle for the [keepAlive] duration; submitted tasks wait in an unbounded queue while all
     * threads are busy (so they are returned by [ExecutorService.shutdownNow] if not started yet)
     */
    private fun newBoundedExecutor(
        keepAlive: Duration,
        threadFactory: ThreadFactory,
    ): ExecutorService {
        val size = effectiveMaxConcurrentTasks()
        val executor = ThreadPoolExecutor(
            /* corePoolSize = */ size,
            /* maximumPoolSize = */ size,
            /* keepAliveTime = */ keepAlive.toMillis(),
            /* unit = */ TimeUnit.MILLISECONDS,
            /* workQueue = */ LinkedBlockingQueue(),
            /* threadFactory = */ threadFactory,
        )
        executor.allowCoreThreadTimeOut(true)
        return executor
    }
}
