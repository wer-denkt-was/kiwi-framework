package de.kiwi.api.domain

import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.converter.ConverterFactory

/**
 * Converting strings to [EntityId].
 */
object StringToEntityIdConverterFactory : ConverterFactory<String, EntityId> {
    override fun <T : EntityId> getConverter(targetType: Class<T>): Converter<String, T> = when (targetType) {
        // Anonymous EntityID
        EntityId::class.java -> Converter {
            @Suppress("UNCHECKED_CAST")
            EntityId.fromStringAnonymous(it) as T
        }

        else -> Converter {
            EntityId.fromString(it, targetType)
        }
    }
}

/**
 * Converting numbers to [EntityId].
 */
object NumberToEntityIdConverterFactory : ConverterFactory<Number, EntityId> {
    override fun <T : EntityId> getConverter(targetType: Class<T>): Converter<Number, T> = when (targetType) {
        // Anonymous EntityID
        EntityId::class.java -> Converter {
            @Suppress("UNCHECKED_CAST")
            EntityId.fromValueAnonymous(it.toLong()) as T
        }

        else -> Converter {
            EntityId.fromValue(it.toLong(), targetType)
        }
    }
}

/**
 * Converting [EntityId] to strings.
 */
object EntityIdToStringConverter : Converter<EntityId, String> {
    override fun convert(source: EntityId) = source.toString()
}

/**
 * Converting [EntityId] to numbers.
 */
object EntityIdToNumberConverter : Converter<EntityId, Number> {
    override fun convert(source: EntityId) = source.value
}
