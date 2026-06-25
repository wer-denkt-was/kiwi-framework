package de.kiwi.api.domain

import de.kiwi.api.util.slf4j

/**
 * Abstract class for objects representing a holder of a singleton instance
 * of some interface or service that should be available globally from a static context.
 * @see de.kiwi.api.application.GlobalSingletonBean
 */
abstract class GlobalSingleton<T : Any> {
    private val log = slf4j(this::class)

    @field:Volatile
    private var globalInstance: T? = null

    abstract val singletonName: String

    val instance: T
        get() = checkNotNull(globalInstance) { "Global instance of $singletonName has not been initialized" }

    fun provideInstance(instance: T) {
        log.info("Initializing singleton $singletonName with instance of: ${instance.javaClass.name}")
        globalInstance = instance
    }

    fun cleanupInstance() {
        val instance = globalInstance
        if (instance != null) {
            globalInstance = null
            log.info("Cleaned up singleton $singletonName instance of: ${instance.javaClass.name}")
        }
    }
}
