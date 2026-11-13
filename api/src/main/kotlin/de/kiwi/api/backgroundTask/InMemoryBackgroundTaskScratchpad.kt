package de.kiwi.api.backgroundTask

import de.kiwi.api.domain.EntityIdJsonModule
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [BackgroundTaskScratchpad], e.g. for unit tests of [BackgroundTaskHandlers][BackgroundTaskHandler] with an
 * [InMemoryBackgroundTaskContext], without the `kiwi-background-tasks` module and a database.
 *
 * Behaves like the scratchpad of a task executed by a worker.
 * Values are stored as JSON, i.e. values which can't be serialized fail when stored, and values are read as copies
 * (deserialized as the requested type).
 *
 * Unlike the scratchpad of a worker, values are never deleted when the task is closed: Pass the scratchpad to the
 * context of the next attempt (see [InMemoryBackgroundTaskContext.resumed]) to test resuming a task.
 *
 * @param objectMapper Serializes the values; by default, a [JsonMapper] with the modules found on the classpath (e.g.
 * the Kotlin module) and the [EntityIdJsonModule]. Pass the application's object mapper if the values require its
 * configuration.
 */
class InMemoryBackgroundTaskScratchpad(
    private val objectMapper: ObjectMapper = defaultObjectMapper,
) : BackgroundTaskScratchpad() {

    /** Stored values as JSON, using [NullValue] for `null` (not supported by [ConcurrentHashMap]) */
    private val values = ConcurrentHashMap<String, Any>()

    override val keys: Set<String>
        get() = values.keys.toSet()

    override fun contains(key: String) = values.containsKey(key)

    override fun <T> get(key: String, type: TypeReference<T>): T? {
        val json = values[key] as? String ?: return null // also for NullValue
        return objectMapper.readValue(json, type)
    }

    override fun put(key: String, value: Any?) {
        requireValidKey(key)
        store(key, value)
    }

    override fun remove(key: String) {
        requireValidKey(key)
        values.remove(key)
    }

    /** Stored value of the given [key] as JSON, `"null"` for a `null` value, or `null` if absent */
    fun getJson(key: String): String? = when (val value = values[key]) {
        null -> null
        NullValue -> "null"
        else -> value as String
    }

    private fun store(key: String, value: Any?) {
        values[key] = value
            ?.let { objectMapper.writeValueAsString(it) }
            ?: NullValue
    }

    override fun toString() = "InMemoryBackgroundTaskScratchpad(keys=${values.keys.sorted()})"

    /** Sentinel object used in place of a `null` value */
    private object NullValue

    private companion object {
        val defaultObjectMapper: ObjectMapper by lazy {
            JsonMapper.builder()
                .findAndAddModules()
                .addModule(EntityIdJsonModule())
                .build()
        }
    }
}
