package de.kiwi.framework.infrastructure.bus

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Internal utility class for caching resolved command/query-handlers by subject class.
 */
internal class HandlerCache<K : Any, H : Any>(
    private val findHandler: (K) -> H,
) {
    private val cache = ConcurrentHashMap<Class<*>, H>()
    private val count = AtomicLong(0)
    private val misses = AtomicLong(0)

    fun compute(key: K): H {
        count.incrementAndGet()
        return cache.computeIfAbsent(key.javaClass) {
            misses.incrementAndGet()
            findHandler(key)
        }
    }

    fun hitRate(): Double {
        val currentCount = count.get()
        val currentMisses = misses.get()
        return (currentCount - currentMisses) / currentCount.toDouble()
    }
}
