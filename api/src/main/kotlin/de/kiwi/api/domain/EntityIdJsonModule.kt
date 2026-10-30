package de.kiwi.api.domain

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import com.fasterxml.jackson.annotation.JsonFormat
import tools.jackson.databind.*
import tools.jackson.databind.deser.KeyDeserializers
import tools.jackson.databind.module.SimpleDeserializers
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.module.SimpleSerializers

/**
 * Jackson [JacksonModule] support for [EntityId] serialization and deserialization.
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
        beanDescRef: BeanDescription.Supplier,
        formatOverrides: JsonFormat.Value?,
    ): ValueSerializer<*>? {
        return forEntityIdClass(type) { EntityIdSerializer(fieldName = fieldName) }
    }
}

private class EntityIdSerializer(private val fieldName: Boolean) : ValueSerializer<EntityId>() {
    override fun serialize(value: EntityId, gen: JsonGenerator, ctxt: SerializationContext) {
        // Always serialize EntityId as strings (either text or field-name tokens)
        val stringValue = value.toString()
        if (fieldName) gen.writeName(stringValue)
        else gen.writeString(stringValue)
    }
}

private class EntityIdDeserializers : SimpleDeserializers(), KeyDeserializers {
    override fun findBeanDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDescRef: BeanDescription.Supplier,
    ): ValueDeserializer<*>? {
        return forEntityIdClass(type) { EntityIdDeserializer(it) }
    }

    override fun findKeyDeserializer(
        type: JavaType,
        config: DeserializationConfig,
        beanDescRef: BeanDescription.Supplier,
    ): KeyDeserializer? {
        return forEntityIdClass(type) { EntityIdKeyDeserializer(it) }
    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <T> forEntityIdClass(type: JavaType, then: (Class<out EntityId>) -> T): T? = when {
    type.isTypeOrSubTypeOf(EntityId::class.java) -> then(type.rawClass as Class<out EntityId>)
    else -> null
}

private class EntityIdDeserializer(private val entityIdClass: Class<out EntityId>) : ValueDeserializer<EntityId>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): EntityId {
        val token = p.currentToken()
        return when {
            token.isNumeric -> EntityId.fromValue(p.longValue, entityIdClass)
            else -> EntityId.fromString(p.string, entityIdClass)
        }
    }
}

private class EntityIdKeyDeserializer(private val entityIdClass: Class<out EntityId>) : KeyDeserializer() {
    override fun deserializeKey(key: String, ctxt: DeserializationContext): EntityId {
        return EntityId.fromString(key, entityIdClass)
    }
}
