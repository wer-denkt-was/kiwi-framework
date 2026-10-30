package de.kiwi.api.domain

import de.kiwi.api.util.slf4j
import java.util.concurrent.atomic.AtomicReference

/**
 * Abstract class for objects representing a holder of a singleton instance
 * of some interface or service that should be available globally from a static context.
 * @see de.kiwi.api.application.GlobalSingletonBean
 */
abstract class GlobalSingleton<T : Any> {
    private val log = slf4j(this::class)

    private val globalInstance = AtomicReference<T?>(null)

    abstract val singletonName: String

    val instance: T
        get() = checkNotNull(globalInstance.get()) { "Global instance of $singletonName has not been initialized" }

    fun provideInstance(instance: T) {
        log.info("Initializing singleton $singletonName with instance of: ${instance.javaClass.name}")
        globalInstance.set(instance)
    }

    /**
     * Clear the global instance, but only if it is still the given [instance].
     * This prevents a provider from clearing an instance which has been provided by someone else in the meantime,
     * e.g. when multiple application contexts are started and closed in an interleaved order.
     * @return `true` if the global instance was cleared
     */
    fun cleanupInstance(instance: T): Boolean {
        val cleared = globalInstance.compareAndSet(instance, null)
        if (cleared) logCleanup(instance)
        return cleared
    }

    /**
     * Unconditionally clear the global instance.
     * Prefer [cleanupInstance] with the instance to clear, if the provided instance is known.
     */
    fun cleanupInstance() {
        val instance = globalInstance.getAndSet(null)
        if (instance != null) logCleanup(instance)
    }

    private fun logCleanup(instance: T) {
        log.info("Cleaned up singleton $singletonName instance of: ${instance.javaClass.name}")
    }
}
