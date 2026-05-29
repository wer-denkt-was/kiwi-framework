package de.kiwi.api.util

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Helper function for building a JSON [ObjectNode].
 */
inline fun buildJsonObject(action: ObjectNode.() -> Unit): ObjectNode {
    val node = JsonNodeFactory.instance.objectNode()
    node.action()
    return node
}

/**
 * Helper function for building a JSON [ArrayNode].
 */
inline fun buildJsonArray(capacity: Int? = null, action: ArrayNode.() -> Unit): ArrayNode {
    val node = capacity
        ?.let { JsonNodeFactory.instance.arrayNode(it) }
        ?: JsonNodeFactory.instance.arrayNode()

    node.action()
    return node
}
