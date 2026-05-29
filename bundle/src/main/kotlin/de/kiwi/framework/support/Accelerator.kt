package de.kiwi.framework.support

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import de.kiwi.api.generic.PageResult
import de.kiwi.api.util.buildJsonArray
import de.kiwi.api.util.buildJsonObject

/**
 * Data structure representing *accelerated* data with references.
 * The data contained in this object has redundancies removed and represented as references.
 * @see Accelerator
 */
data class Accelerated<T>(
    val content: T,
    val references: List<*>,
    val accelerated: Boolean,
)

fun Accelerated<PageResult<*>>.deceleratePage(): PageResult<*> {
    // Special case for decelerating a PageResult<*>,
    // unwrapping its items and then returning a PageResult with decelerated items.
    if (!accelerated) return content
    val items = dereferenceValue(content.items, references) as List<*>
    return content.withItems(items)
}

/** Decelerate the content of an Accelerated  */
fun Accelerated<*>.decelerate(): Any? {
    if (!accelerated) return content
    return dereferenceValue(content, references)
}

/**
 * Helper class transforming responses with redundant payloads
 * into neatly organized hierarchical references.
 *
 * Redundant objects within the accelerated value will be replaced with references (`{"@": "<index>"}`),
 * and the original objects attached as a "references" array.
 */
object Accelerator {
    /**
     * Special case for accelerating pages:
     * This will wrap the page within an `Accelerated<PageResult<*>>` object,
     * and replace the item list within the `PageResult<*>` with accelerated items.
     */
    fun acceleratePage(page: PageResult<*>): Accelerated<PageResult<*>> {
        val result = accelerate(page.items)
        return Accelerated(
            content = page.withItems(result.content),
            references = result.references,
            accelerated = result.accelerated
        )
    }

    /**
     * Accelerate a list of values by merging duplicate objects into a reference list.
     */
    fun accelerate(items: List<*>): Accelerated<List<*>> {
        return if (!canAccelerateList(items)) {
            notAccelerated(items)
        } else {
            @Suppress("UNCHECKED_CAST")
            process(items) as Accelerated<List<*>>
        }
    }

    private fun canAccelerateList(items: List<*>): Boolean {
        // Assumption: Lists of maps can reasonably be accelerated
        return items.all { it is Map<*, *> || it is JsonNode }
    }

    /**
     * Accelerate a value by merging duplicate objects into a reference list.
     */
    fun accelerate(value: Any?): Accelerated<Any?> {
        return if (!canAccelerateValue(value)) {
            notAccelerated(value)
        } else {
            process(value)
        }
    }

    private fun canAccelerateValue(value: Any?): Boolean {
        // Assumption: Non-null maps can reasonably be accelerated
        return value != null && (value is Map<*, *> || value is JsonNode)
    }

    private fun process(value: Any?): Accelerated<Any?> {
        if (value == null) {
            return notAccelerated(null)
        }

        val context = AccelerateContext()
        val processedValue = context.processRoot(value)
        return accelerated(processedValue, context.referencedObjectList)
    }

    private fun <T> notAccelerated(content: T) = Accelerated(
        content = content,
        references = emptyList<Unit>(),
        accelerated = false,
    )

    private fun <T> accelerated(content: T, references: List<*>) = Accelerated(
        content = content,
        references = references,
        accelerated = true,
    )
}

private class AccelerateContext {
    var referenceCounter = 0
    val referencedObjects = LinkedHashMap<Any, Int>()

    // This relies on the linked order of children within referencedObjects
    // lining up with the referenceCounter sequence!
    val referencedObjectList get() = referencedObjects.keys.toList()

    fun processRoot(root: Any?): Any? {
        // Do not turn root into a reference, just process their children
        return when (root) {
            null -> null
            is RefValue -> root
            is ArrayNode -> root.arrayMap { item -> processRoot(item) }
            is ObjectNode -> root.entriesMap { item -> processChild(item) }
            is Collection<*> -> root.map { item -> processRoot(item) }
            is Map<*, *> -> root.mapValues { (_, item) -> processChild(item) }
            else -> root // Terminate
        }
    }

    fun processChild(node: Any?): Any? {
        // Turn child objects into references
        return when (node) {
            null -> null
            is RefValue -> node // Terminate early to avoid referencing RefValues
            is ArrayNode -> node.arrayMap { item -> processChild(item) }
            is ObjectNode -> {
                val value = node.entriesMap { item -> processChild(item) }
                val index = referencedObjects.computeIfAbsent(value) { referenceCounter++ }
                refJsonNode(index)
            }

            is Collection<*> -> node.map { item -> processChild(item) }
            is Map<*, *> -> {
                // Iterate children first, then replace self with reference.
                // Emplace value into referenced objects. Duplicate objects will not receive a new index!
                val value = node.mapValues { (_, item) -> processChild(item) }
                val index = referencedObjects.computeIfAbsent(value) { referenceCounter++ }
                RefValue(index)
            }

            else -> node // Terminate
        }
    }
}

/** Magic key used in RefValues pointing to a reference index */
private const val REF_KEY = "@"

/** Reference pointing to a specific index, represented as an Object containing `{ "@": <index> }` */
private class RefValue(val index: Int) : AbstractMap<String, Int>() {
    override val entries: Set<Map.Entry<String, Int>> = setOf(RefEntry(index))

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is RefValue -> false
        else -> index == other.index
    }

    override fun hashCode() = index
    override fun toString() = "{@=$index}"

    private class RefEntry(index: Int) : Map.Entry<String, Int> {
        override val key = REF_KEY
        override val value = index
    }
}

/** Try to interpret a given value as a RefValue instance */
private fun refValueFrom(value: Any?): RefValue? = when (value) {
    null -> null
    is RefValue -> value // Instance of RefValue class

    is JsonNode -> when {
        !value.isObject -> null // Must be a JSON object
        value.size() != 1 -> null // Must only have a single key "@"
        else -> value
            .get(REF_KEY)
            .takeIf { it.isIntegralNumber }
            ?.let { RefValue(it.intValue()) }
    }

    is Map<*, *> -> when {
        value.size != 1 -> null // Must only have a single key "@"
        else -> when (val index = value[REF_KEY]) {
            null -> null
            !is Number -> null
            else -> RefValue(index.toInt())
        }
    }

    else -> null
}

/** Create a JSON object `{ "@": <index> }`, representing a RefValue */
private fun refJsonNode(index: Int): JsonNode {
    return buildJsonObject { put(REF_KEY, index) }
}

/** Recursive helper function for processing values during deceleration */
private fun dereferenceValue(value: Any?, references: List<*>): Any? {
    // Replace and process references
    refValueFrom(value)?.let { refValue ->
        val target = references[refValue.index]
        return dereferenceValue(target, references)
    }

    // Process items of lists/maps, otherwise use values verbatim
    return when (value) {
        null -> null
        is ArrayNode -> value.arrayMap { item -> dereferenceValue(item, references) }
        is ObjectNode -> value.entriesMap { item -> dereferenceValue(item, references) }
        is Collection<*> -> value.map { item -> dereferenceValue(item, references) }
        is Map<*, *> -> value.mapValues { (_, item) -> dereferenceValue(item, references) }
        else -> value
    }
}

private fun ArrayNode.arrayMap(mapper: (JsonNode) -> Any?): JsonNode {
    return buildJsonArray(size()) {
        this@arrayMap.forEach { item -> add(mapper(item) as? JsonNode) }
    }
}

private fun ObjectNode.entriesMap(mapper: (JsonNode) -> Any?): JsonNode {
    return buildJsonObject {
        this@entriesMap.fieldNames().forEach { key ->
            val value = mapper(this@entriesMap.get(key)) as? JsonNode
            replace(key, value)
        }
    }
}
