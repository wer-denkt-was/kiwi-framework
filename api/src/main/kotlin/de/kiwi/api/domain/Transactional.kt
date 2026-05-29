package de.kiwi.api.domain

/**
 * Interface for declaring commands/queries as transactional,
 * and provide transaction handling information
 */
interface Transactional<out R> {
    /**
     * If `true`, the operation represented by this object should be executed within a transaction.
     */
    val transactional: Boolean get() = true

    /**
     * If `true`, the transaction for this operation can be optimized as a read-only transaction.
     * By default, transactions are read-and-write.
     */
    val readOnly: Boolean get() = false

    /**
     * Marker interface for declaring commands/queries as __read-only__ transactional.
     */
    interface ReadOnly<out R> : Transactional<R> {
        override val readOnly: Boolean get() = true
    }
}
