package de.kiwi.api.domain

import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable

/**
 * [BaseEntity] that also implements [org.springframework.data.domain.Persistable] to optimize persisting new entities
 * (allowing `INSERT` without checking for `SELECT`/`UPDATE`).
 * Use [markAsNew] to set [isNewPersistable] for new unmanaged entity instances.
 */
@MappedSuperclass
abstract class PersistableBaseEntity<ID : EntityId>(id: ID) : BaseEntity<ID>(id), Persistable<ID> {
    @Transient
    private var isNewPersistable: Boolean = false

    override fun isNew(): Boolean = isNewPersistable

    protected fun markAsNew() {
        isNewPersistable = true
    }
}
