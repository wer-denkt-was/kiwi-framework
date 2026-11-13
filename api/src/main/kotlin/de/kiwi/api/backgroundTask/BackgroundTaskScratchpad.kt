package de.kiwi.api.backgroundTask

import tools.jackson.core.type.TypeReference

/**
 * Scratchpad of a background task: Key-value store for data that survives a failed or interrupted execution,
 * e.g. checkpoints and intermediate results, so a resumed task can skip work that has been done before.
 * Values are stored as JSON.
 *
 * Writing a value is durable when [put] returns, so a checkpoint should be written right after the side effect it
 * records (e.g. after sending an e-mail). If the execution is interrupted between the side effect and storing the
 * checkpoint, that single side effect is repeated when the task is resumed (*at-least-once*).
 *
 * The scratchpad is retained while the task may be resumed (i.e. if it failed), and deleted when the task is completed
 * successfully or canceled. By convention, the stage checkpoints of [BackgroundTaskStages] are stored with keys
 * starting with `kiwi:stage:` (see [BackgroundTaskStages.stageResultKey] and [BackgroundTaskStages.stageItemKey]);
 * handlers shouldn't use keys starting with `kiwi:` for their own values.
 */
abstract class BackgroundTaskScratchpad {
    /** Keys of the stored values */
    abstract val keys: Set<String>

    /** `true` if a value (including possibly `null`) is stored for the given [key] */
    abstract operator fun contains(key: String): Boolean

    /** Read the value stored for the given [key] as the given [type], `null` if absent */
    abstract fun <T> get(key: String, type: TypeReference<T>): T?

    /** Read the value stored for the given [key] as type [T], `null` if absent */
    inline operator fun <reified T> get(key: String): T? {
        val typeRef = object : TypeReference<T>() {}
        return get(key, typeRef)
    }

    /**
     * Store the given [value] (which *must* be serializable as JSON) for the given [key], replacing a previous value.
     * The value is stored durably when this method returns.
     * @throws IllegalArgumentException if the key is invalid (see [requireValidKey])
     */
    abstract fun put(key: String, value: Any?)

    /**
     * Remove the value stored for the given [key], if any
     * @throws IllegalArgumentException if the key is invalid (see [requireValidKey])
     */
    abstract fun remove(key: String)

    companion object {
        /** Maximum length of a key */
        const val MAX_KEY_LENGTH = 255

        /**
         * Validate the given [key]: It must not be blank, and must have at most [MAX_KEY_LENGTH] characters.
         * Throws an [IllegalArgumentException] if it is invalid.
         */
        fun requireValidKey(key: String) {
            require(key.isNotBlank()) { "Scratchpad key must not be blank" }
            require(key.length <= MAX_KEY_LENGTH) { "Scratchpad key exceeds $MAX_KEY_LENGTH characters: $key" }
        }
    }
}
