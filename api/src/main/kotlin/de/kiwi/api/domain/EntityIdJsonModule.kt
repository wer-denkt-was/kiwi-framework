package de.kiwi.api.domain

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.deser.KeyDeserializers
import com.fasterxml.jackson.databind.module.SimpleDeserializers
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.module.SimpleSerializers

/**
 * Jackson [ObjectMapper] support for [EntityId] serialization and deserialization.
 */
class EntityIdJsonModule : SimpleModule("kiwi-entity-id") {
    override fun setupModule(context: SetupContext) {
        context.addSerializers(EntityIdSerializers(fieldName = false))
        context.addKeySerializers(EntityIdSerializers(fieldName = true))
        context.addDeserializers(EntityIdDeserializers())
        context.addKeyDeserializers(EntityIdDeserializers())
    }
}

private class EntityIdSerializers(private val fieldName: Boolean) : SimpleSerializers() {
    override fun findSerializer(
        config: SerializationConfig,
        type: JavaType,
        beanDesc: BeanDescription,
    ): JsonSerializer<*>? {
        return forEntityIdClass(type) { EntityIdSerializer(fieldName = fieldName) }
    }
}

private class EntityIdSerializer(private val fieldName: Boolean) : JsonSerializer<EntityId>() {
    override fun serialize(value: EntityId, gen: JsonGenerator, serializers: SerializerProvider) {
        // Always serialize EntityId as strings (either text or field-name tokens)
        val stringValue = value.toString()
        if (fieldName) gen.writeFieldName(stringValue)
        else gen.writeString(stringValue)
    }
}

private class EntityIdDeserializers : SimpleDeserializers(), KeyDeserializers {
    override fun findBeanDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription,
    ): JsonDeserializer<*>? {
        return forEntityIdClass(type) { EntityIdDeserializer(it) }
    }

    override fun findKeyDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDesc: BeanDescription,
    ): KeyDeserializer? {
        return forEntityIdClass(type) { EntityIdKeyDeserializer(it) }
    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <T> forEntityIdClass(type: JavaType, then: (Class<out EntityId>) -> T): T? = when {
    type.isTypeOrSubTypeOf(EntityId::class.java) -> then(type.rawClass as Class<out EntityId>)
    else -> null
}

private class EntityIdDeserializer(private val entityIdClass: Class<out EntityId>) : JsonDeserializer<EntityId>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): EntityId {
        val token = p.currentToken
        return when {
            token.isNumeric -> EntityId.fromValue(p.longValue, entityIdClass)
            else -> EntityId.fromString(p.text, entityIdClass)
        }
    }
}

private class EntityIdKeyDeserializer(private val entityIdClass: Class<out EntityId>) : KeyDeserializer() {
    override fun deserializeKey(key: String, ctxt: DeserializationContext): EntityId {
        return EntityId.fromString(key, entityIdClass)
    }
}
