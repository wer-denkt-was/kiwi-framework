package de.kiwi.backgroundTask.application.worker

import de.kiwi.api.backgroundTask.BackgroundTaskScratchpad
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * [BackgroundTaskScratchpad] of a task execution: Loads the stored values on first access, caches them, and writes
 * through to the [persistentStore] (durably, before updating the cache).
 */
internal class TaskExecutionScratchpad(
    private val objectMapper: ObjectMapper,
    private val persistentStore: PersistentStore,
) : BackgroundTaskScratchpad() {

    /** Persistence of the scratchpad values as JSON (`null` for a `null` value) */
    interface PersistentStore {
        fun load(): Map<String, String?>
        fun put(key: String, valueJson: String?)
        fun remove(key: String)
    }

    /** Cached values as JSON, [NullSentinel] for `null` (not supported by [ConcurrentHashMap]) */
    private val values: ConcurrentHashMap<String, Any> by lazy {
        ConcurrentHashMap<String, Any>().also { values ->
            // On lazy initialization, retrieve values from persistent store
            persistentStore.load().forEach { (key, json) ->
                values[key] = json ?: NullSentinel
            }
        }
    }

    override val keys: Set<String>
        get() = values.keys.toSet()

    override fun contains(key: String) = values.containsKey(key)

    override fun <T> get(key: String, type: TypeReference<T>): T? {
        val json = values[key] as? String ?: return null // catches NullSentinel
        return objectMapper.readValue(json, type)
    }

    override fun put(key: String, value: Any?) {
        putWith(key, value) { json ->
            // write through to underlying store
            persistentStore.put(key, json)
        }
    }

    /**
     * Store the given [value] like [put], but persist its JSON with the given [persist] function instead of the
     * [persistentStore] (e.g. together with other changes in one transaction); the value is cached once persisted
     */
    inline fun putWith(key: String, value: Any?, persist: (valueJson: String?) -> Unit) {
        requireValidKey(key)
        val json = value?.let { objectMapper.writeValueAsString(it) }
        persist(json)
        values[key] = json ?: NullSentinel // cache value locally
    }

    override fun remove(key: String) {
        requireValidKey(key)
        removeKey(key)
    }

    private fun removeKey(key: String) {
        if (!contains(key)) return
        persistentStore.remove(key) // write through to underlying store
        values.remove(key)
    }

    /** Sentinel object used in place of a `null` value */
    internal object NullSentinel
}
