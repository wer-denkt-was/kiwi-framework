package de.kiwi.api.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Transient
import java.io.Serializable

/**
 * Abstract parent for Entity ID types used with [BaseEntity] child classes,
 * defining a type-safe wrapper for the underlying numeric [value].
 *
 * Every Entity ID class **must** have **only** a single-parameter constructor accepting the
 * underlying ID value.
 * Classes inheriting from EntityId **must** be annotated as [@Embeddable][Embeddable] to be compatible
 * with the JPA requirements when used as an [@EmbeddedId][jakarta.persistence.EmbeddedId] by the corresponding entity class.
 *
 * __Example__:
 * ```kt
 * @Embeddable
 * class SampleId(value: Long) : EntityId(value)
 * ```
 * @see BaseEntity
 */
@MappedSuperclass
abstract class EntityId(value: Long) : Serializable, Comparable<EntityId> {
    /**
     * Underlying numeric [Long] value of this entity ID.
     */
    @Column(name = "id", nullable = false, updatable = false)
    private val idValue: Long = value

    @get:Transient
    val value: Long get() = idValue

    /**
     * Cast into an EntityId of type [ID] with the same [idValue].
     */
    final inline fun <reified ID : EntityId> cast(): ID = fromValue<ID>(value)

    final fun <X : EntityId> cast(target: Class<X>) = fromValue(value, target)

    /**
     * Encodes a base32 representation of this entity ID using the [Snowflake] encoding.
     */
    final override fun toString() = valueToString(idValue)

    final override fun hashCode() = idValue.hashCode()

    final override fun compareTo(other: EntityId) = idValue.compareTo(other.idValue)

    final override fun equals(other: Any?) = when {
        this === other -> true
        other == null -> false
        other !is EntityId -> false
        this.javaClass != other.javaClass -> false
        else -> idValue == other.idValue
    }

    companion object {
        /**
         * Generate a new unique entity ID of the given generic [ID] type using the [Snowflake] algorithm.
         */
        inline fun <reified ID : EntityId> generate(): ID = generate(ID::class.java)

        /**
         * Wrap an entity ID of the given generic type [ID] from a numeric [Long] value.
         */
        inline fun <reified ID : EntityId> fromValue(value: Long) = fromValue(value, ID::class.java)

        /**
         * Wrap an entity ID of the given generic type [ID] from a numeric or base32-encoded [String] value.
         */
        inline fun <reified ID : EntityId> fromString(string: String) = fromString(string, ID::class.java)

        /**
         * Generate a new unique entity ID of the given [idType] class using the [Snowflake] algorithm.
         */
        @JvmStatic
        fun <ID : EntityId> generate(idType: Class<ID>): ID {
            val value = Snowflake.generate()
            return fromValue(value, idType)
        }

        /**
         * Wrap an entity ID of the given [idType] class from a numeric or base32-encoded [String] value.
         */
        @JvmStatic
        fun <ID : EntityId> fromString(string: String, idType: Class<ID>): ID {
            val value = Snowflake.decode(string) ?: string.toLongOrNull() ?: throw IllegalArgumentException(string)
            return fromValue(value, idType)
        }

        /**
         * Wrap an entity ID of the given [idType] class from a numeric [Long] value.
         */
        @JvmStatic
        fun <ID : EntityId> fromValue(value: Long, idType: Class<ID>): ID {
            val constructor = idType.getConstructor(Long::class.java)
            return constructor.newInstance(value)
        }

        /**
         * Wrap an anonymous [EntityId] class from a numeric or base32-encoded [String] value.
         */
        @JvmStatic
        fun fromStringAnonymous(string: String): EntityId = fromString<AnonymousEntityId>(string)

        /**
         * Wrap an anonymous [EntityId] class from a numeric [Long] value.
         */
        @JvmStatic
        fun fromValueAnonymous(value: Long): EntityId = fromValue<AnonymousEntityId>(value)

        /**
         * Convert the [idValue] of an [EntityId] into the equivalent base-32 string representation
         * using the [Snowflake] encoding.
         */
        @JvmStatic
        fun valueToString(idValue: Long): String = Snowflake.encode(idValue)
    }
}

/**
 * Anonymous EntityId implementation used when no specific EntityId subclass is known for decoding.
 */
private class AnonymousEntityId(value: Long) : EntityId(value)
