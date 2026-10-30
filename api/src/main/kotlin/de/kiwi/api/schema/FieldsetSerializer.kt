package de.kiwi.api.schema

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeCreator
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Interface for serializing fieldset data into a structured tree.
 * @param N Serialized node type
 */
interface FieldsetSerializer<N> {
    /** The serialized value representing null */
    val serializeNull: N

    /** Serialize an arbitrary atomic value */
    fun serializeValue(value: Any?): N

    /** Serialize a list of values using a list-context, within which items can be added */
    fun serializeList(capacity: Int? = null, build: ListContext.() -> Unit): N

    /** Serialize a collection of items into a serialized list by serializing all collection items */
    fun serializeList(items: Collection<*>): N =
        serializeList(capacity = items.size) { items.forEach { add(it) } }

    /**
     * Serialize key-value pairs of values into a serialized object using an object-context,
     * within which keys and values can be added to the object
     */
    fun serializeObject(capacity: Int? = null, build: ObjectContext.() -> Unit): N

    /**
     * Serialize a map of keys and values into a serialized object by serializing
     * all values associated with the respective keys
     */
    fun serializeObject(map: Map<String, *>): N =
        serializeObject(capacity = map.size) { map.forEach { (k, v) -> set(k, v) } }

    /** Serialize a tuple of two arbitrary values */
    fun serializePair(first: Any?, second: Any?): N

    /** Check if the given serialized value represents null */
    fun isNull(value: N): Boolean

    /** Check if the given serialized value represents null, or an object that only contains null values */
    fun isAllNullsObject(value: N): Boolean

    /**
     * Given a serialized value, if this value is an object, extend the object using an
     * object-context within which further keys and values can be added to the existing serialized object
     */
    fun extendObject(value: N, extend: ObjectContext.() -> Unit): N

    interface ListContext {
        fun add(item: Any?)
    }

    interface ObjectContext {
        fun set(key: String, value: Any?)
    }
}

/**
 * Fieldset serializer passing values as-is, using [ArrayList] for lists, and [LinkedHashMap] for objects.
 */
object BasicFieldsetSerializer : FieldsetSerializer<Any?> {
    override val serializeNull = null

    override fun serializeValue(value: Any?) = value

    override fun serializeList(capacity: Int?, build: FieldsetSerializer.ListContext.() -> Unit): List<*> {
        val context = MutableListContext(capacity)
        context.build()
        return context.list
    }

    override fun serializeList(items: Collection<*>) = items.toList()

    override fun serializeObject(capacity: Int?, build: FieldsetSerializer.ObjectContext.() -> Unit): Map<String, *> {
        val context = MutableMapContext(capacity)
        context.build()
        return context.map
    }

    override fun serializeObject(map: Map<String, *>) = map.toMap()

    override fun serializePair(first: Any?, second: Any?) = listOf(first, second)

    override fun isNull(value: Any?) = value == null

    override fun isAllNullsObject(value: Any?): Boolean = when (value) {
        null -> true
        is Map<*, *> -> value.isEmpty() || value.values.all { isAllNullsObject(it) }
        is List<*> -> value.isEmpty() || value.all { isAllNullsObject(it) }
        else -> false
    }

    override fun extendObject(value: Any?, extend: FieldsetSerializer.ObjectContext.() -> Unit): Any? {
        val map = when (value) {
            is HashMap<*, *> -> value
            is Map<*, *> -> value.toMutableMap()
            else -> return value // Not an object after all, skip extension completely
        }

        @Suppress("UNCHECKED_CAST")
        val context = MutableMapContext(map as MutableMap<String, Any?>)
        context.extend()
        return context.map
    }

    private class MutableListContext(val list: MutableList<Any?>) : FieldsetSerializer.ListContext {
        constructor(capacity: Int?) : this(list = capacity?.let { ArrayList(it) } ?: ArrayList())

        override fun add(item: Any?) {
            list.add(item)
        }
    }

    private class MutableMapContext(val map: MutableMap<String, Any?>) : FieldsetSerializer.ObjectContext {
        constructor(capacity: Int?) : this(map = capacity?.let { LinkedHashMap(it) } ?: LinkedHashMap())

        override fun set(key: String, value: Any?) {
            map[key] = value
        }
    }
}

/**
 * Fieldset serializer using a [JsonNodeCreator] to wrap values as [JsonNode]s,
 * using [ObjectNode] for objects, and [ArrayNode] for lists.
 */
object JsonFieldsetSerializer : FieldsetSerializer<JsonNode> {
    private val nodes = JsonNodeFactory.instance!!

    override val serializeNull: JsonNode = nodes.nullNode()

    override fun serializeValue(value: Any?): JsonNode = when (value) {
        // If the value is already a JsonNode, keep it as-is
        is JsonNode -> value
        // Otherwise, wrap value into the most appropriate JsonNode
        null -> nodes.nullNode()
        is String -> nodes.stringNode(value)
        is Boolean -> nodes.booleanNode(value)
        is Int -> nodes.numberNode(value)
        is Long -> nodes.numberNode(value)
        is Double -> nodes.numberNode(value)
        is BigInteger -> nodes.numberNode(value)
        is BigDecimal -> nodes.numberNode(value)
        is Byte -> nodes.numberNode(value)
        is Short -> nodes.numberNode(value)
        is Float -> nodes.numberNode(value)
        is ByteArray -> nodes.binaryNode(value)
        // If no appropriate JsonNode exists for the value, wrap it as a generic POJO node
        else -> nodes.pojoNode(value)
    }

    override fun serializeList(capacity: Int?, build: FieldsetSerializer.ListContext.() -> Unit): JsonNode {
        val context = JsonArrayContext(capacity)
        context.build()
        return context.node
    }

    override fun serializeObject(capacity: Int?, build: FieldsetSerializer.ObjectContext.() -> Unit): JsonNode {
        val context = JsonObjectContext()
        context.build()
        return context.node
    }

    override fun serializePair(first: Any?, second: Any?): JsonNode {
        // Serialize a pair as a 2-item JsonArray
        val node = nodes.arrayNode(2)
        node.add(serializeValue(first))
        node.add(serializeValue(second))
        return node
    }

    override fun isNull(value: JsonNode): Boolean = value.isNull

    override fun isAllNullsObject(value: JsonNode): Boolean = value.isNull || when (value) {
        is ObjectNode -> value.isEmpty || value.all { isAllNullsObject(it) }
        is ArrayNode -> value.isEmpty || value.all { isAllNullsObject(it) }
        else -> false
    }

    override fun extendObject(value: JsonNode, extend: FieldsetSerializer.ObjectContext.() -> Unit): JsonNode {
        val base = (value as? ObjectNode) ?: return value // Not an object, skip extension completely
        val context = JsonObjectContext(base)
        context.extend()
        return context.node
    }

    private class JsonArrayContext(val node: ArrayNode) : FieldsetSerializer.ListContext {
        constructor(capacity: Int?) : this(capacity?.let { nodes.arrayNode(it) } ?: nodes.arrayNode())

        override fun add(item: Any?) {
            node.add(serializeValue(item))
        }
    }

    private class JsonObjectContext(
        val node: ObjectNode = nodes.objectNode(),
    ) : FieldsetSerializer.ObjectContext {
        override fun set(key: String, value: Any?) {
            node.replace(key, serializeValue(value))
        }
    }
}
