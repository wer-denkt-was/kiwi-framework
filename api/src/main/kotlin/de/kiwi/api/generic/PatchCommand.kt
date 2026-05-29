package de.kiwi.api.generic

import de.kiwi.api.domain.Command
import de.kiwi.api.domain.EntityFieldMissingValue
import kotlin.reflect.KProperty

/**
 * Abstract command containing patched properties.
 * - Declare "``var <property> by patch<Type>()``" properties to hook a property into the patch-field registry.
 * - Use "``ifPatched(::<property>) { value -> ... }``" to execute a block if the given property has been patched.
 * - Use "``ifPatchedNotNull(::<property>) { value -> ... }``" to execute a block if the given property has been patched with a non-null value.
 */
abstract class PatchCommand<out R> : Command<R>() {
    /**
     * Record patched fields by hooking properties using `var <property>: <Type>? by patch`.
     *
     * Use [ifPatched] or [ifPatchedNotNull] to check which properties have been assigned a value.
     */
    @Transient
    private val patch: MutableMap<String, Any?> = LinkedHashMap()

    @Suppress("UNCHECKED_CAST")
    protected fun <T : Any> patch(): MutableMap<String, T?> = patch as MutableMap<String, T?>

    /**
     * Execute the given block if the indicated property has been patched in [patch].
     */
    fun <T> ifPatched(property: KProperty<T?>, then: (T?) -> Unit) {
        if (patch.containsKey(property.name)) {
            @Suppress("UNCHECKED_CAST")
            then(patch[property.name]?.let { it as T })
        }
    }

    /**
     * Execute the given block if the indicated property has been patched with a non-null value in [patch].
     */
    fun <T> ifPatchedNotNull(
        property: KProperty<T?>,
        ignoreNull: Boolean = false,
        orElse: () -> T = { throw EntityFieldMissingValue(javaClass, property.name) },
        then: (T) -> Unit,
    ) {
        if (patch.containsKey(property.name)) {
            @Suppress("UNCHECKED_CAST") val valueOrNull = patch[property.name] as T?
            val value = valueOrNull ?: if (ignoreNull) return else orElse()
            then(value)
        }
    }
}
