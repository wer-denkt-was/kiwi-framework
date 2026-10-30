package de.kiwi.framework.infrastructure.bus

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Internal utility class for caching resolved command/query-handlers by subject class.
 * The cache must be [invalidated][invalidate] whenever the set of available handlers changes.
 */
internal class HandlerCache<K : Any, H : Any>(
    private val findHandler: (K) -> H,
) {
    @Volatile
    private var cache = ConcurrentHashMap<Class<*>, H>()

    private val count = AtomicLong(0)
    private val misses = AtomicLong(0)

    fun compute(key: K): H {
        count.incrementAndGet()
        return cache.computeIfAbsent(key.javaClass) {
            misses.incrementAndGet()
            findHandler(key)
        }
    }

    /**
     * Discard all cached handlers.
     * The cache map is replaced instead of cleared, such that a lookup running concurrently to the invalidation
     * cannot store a stale handler in the new cache map.
     */
    fun invalidate() {
        cache = ConcurrentHashMap()
    }

    /** Ratio of cache hits to all lookups, or `0.0` if there were no lookups yet */
    fun hitRate(): Double {
        val currentCount = count.get()
        val currentMisses = misses.get()
        return if (currentCount == 0L) 0.0 else (currentCount - currentMisses) / currentCount.toDouble()
    }
}
