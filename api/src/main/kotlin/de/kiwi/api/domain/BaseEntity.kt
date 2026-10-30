package de.kiwi.api.domain

import jakarta.persistence.Column
import jakarta.persistence.EmbeddedId
import jakarta.persistence.MappedSuperclass
import java.io.Serializable

/**
 * Abstract parent for Entities using a dedicated [EntityId] class to wrap their [id].
 * This should be used as a parent class for all aggregate entities using a single generated unique ID.
 *
 * __Example__:
 * ```kt
 * @Embeddable
 * class SampleId(value: Long) : EntityId(value)
 *
 * @Entity
 * class Sample(
 *   id: SampleId,
 *   // more attributes here ...
 * ) : BaseEntity<SampleId>(id) {
 *   // more attributes here ...
 *   // functions here ...
 * }
 * ```
 * @param ID Entity ID used for the inheriting entity class
 */
@MappedSuperclass
abstract class BaseEntity<ID : EntityId>(
    @EmbeddedId
    @Column(name = "id", updatable = false, nullable = false)
    val id: ID,
) : Serializable {

    override fun hashCode() = id.hashCode()

    override fun equals(other: Any?) = when {
        this === other -> true
        other !is BaseEntity<*> -> false
        else -> id == other.id
    }
}
