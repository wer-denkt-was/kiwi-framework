package de.kiwi.api.application

import de.kiwi.api.domain.GlobalSingleton
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton

private val log = slf4j<GlobalSingletonBean<*>>()

/**
 * Bean class that uses its Spring Bean lifecycle to provide/cleanup
 * a [GlobalSingleton] using the given [ObjectProvider] instance.
 * @see de.kiwi.api.domain.GlobalSingleton
 * @see SmartInitializingSingleton
 * @see DisposableBean
 */
class GlobalSingletonBean<T : Any>(
    private val singleton: GlobalSingleton<T>,
    private val singletonProvider: ObjectProvider<T>,
) : SmartInitializingSingleton, DisposableBean {
    @Volatile
    private var providedInstance: T? = null

    override fun afterSingletonsInstantiated() {
        if (log.isDebugEnabled) {
            val availableClassNames = singletonProvider.map { it.javaClass.name }
            log.debug("Beans for singleton ${singleton.singletonName} available: $availableClassNames")
        }
        val instance = singletonProvider.getIfUnique {
            throw IllegalStateException("No unique bean available for global singleton: ${singleton.singletonName}")
        }
        singleton.provideInstance(instance)
        providedInstance = instance
    }

    override fun destroy() {
        if (log.isDebugEnabled) {
            log.debug("Destroying global singleton ${singleton.singletonName} bean")
        }

        // Only clear the global singleton if it still holds the instance provided by this bean,
        // as another application context may have provided its own instance in the meantime
        providedInstance?.let { instance ->
            providedInstance = null
            singleton.cleanupInstance(instance)
        }
    }
}
