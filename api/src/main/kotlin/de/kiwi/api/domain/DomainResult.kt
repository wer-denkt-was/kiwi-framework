package de.kiwi.api.domain

/**
 * @see DomainResult
 */
@Deprecated("Renamed to DomainResult", replaceWith = ReplaceWith("DomainResult"), level = DeprecationLevel.WARNING)
typealias DomainEvents<T> = DomainResult<T>

/**
 * A collection of [DomainEvent] items, wrapping an additional [value] of type [T].
 * This class can be used to generate and collate events in the scope of a domain function.
 * @see DomainResult.Companion.invoke
 * @see DomainResult.Companion.merge
 */
data class DomainResult<out T>(
    val value: T,
    val events: List<DomainEvent>,
) : Iterable<DomainEvent> by events {
    companion object {
        /**
         * Create a [DomainResult] by running a callback which may record [DomainEvent] items
         * during its execution using the [Recorder] parameter and returns a result value of type [T].
         */
        inline operator fun <T> invoke(action: (record: Recorder) -> T): DomainResult<T> {
            val recorder = Recorder(ArrayList())
            val value = action(recorder)
            return DomainResult(value = value, events = recorder.getEvents())
        }

        /**
         * Merge all given [DomainResult] into a single [DomainResult] instance,
         * discarding the wrapped value of each [DomainResult] collection.
         */
        fun merge(vararg items: DomainResult<*>): DomainResult<Unit> =
            merge(items.toList())

        /**
         * Merge a collection of [DomainResult] into a single [DomainResult] instance,
         * discarding the wrapped value of each [DomainResult] collection.
         */
        fun merge(items: Iterable<DomainResult<*>>): DomainResult<Unit> =
            DomainResult(value = Unit, events = items.flatMap { it.events })

        /**
         * Wrap a value in a [DomainResult] collection without any events.
         */
        fun <T> of(value: T): DomainResult<T> =
            DomainResult(value = value, events = emptyList())

        /**
         * Empty [DomainResult] without a value or events.
         */
        val empty = of(Unit)

        /**
         * Create a [DomainResult] collection containing a single event and no value.
         */
        fun events(event: DomainEvent) = DomainResult(value = Unit, events = listOf(event))

        /**
         * Create a [DomainResult] collection containing all given events and no value.
         */
        fun events(vararg event: DomainEvent) = DomainResult(value = Unit, events = event.toList())

        /**
         * Create a [DomainResult] collection containing the given list of events and no value.
         */
        fun events(events: Iterable<DomainEvent>) = DomainResult(value = Unit, events = events.toList())
    }

    /**
     * Find the first [DomainEvent] of type [E] within the contained events.
     */
    inline fun <reified E> findByType(): E? = events.firstNotNullOfOrNull { it as? E }

    /**
     * Find all [DomainEvent] items of type [E] within the contained events.
     */
    inline fun <reified E> findAllByType(): List<E> = events.mapNotNull { it as? E }

    /**
     * Combine two [DomainResult] containers, combining their respective values using a [combine] function.
     */
    fun <R, U> plus(other: DomainResult<R>, combine: (T, R) -> U): DomainResult<U> =
        DomainResult(value = combine(value, other.value), events = events + other.events)

    /**
     * Add two [DomainResult] containers, combining their respective values into a [Pair] of values.
     */
    operator fun <R> plus(other: DomainResult<R>): DomainResult<Pair<T, R>> =
        plus(other, ::Pair)

    /**
     * Add a number of [DomainEvent] items to a [DomainResult] collection, keeping the value as-is
     */
    operator fun plus(events: Collection<DomainEvent>): DomainResult<T> =
        copy(events = this.events + events)

    /**
     * Add a single [DomainEvent] item to a [DomainResult] collection, keeping the value as-is
     */
    operator fun plus(event: DomainEvent): DomainResult<T> =
        copy(events = this.events + listOf(event))

    /**
     * Map the [value] of this [DomainResult] using the given mapper function
     */
    inline fun <R> map(mapper: (T) -> R): DomainResult<R> {
        return DomainResult(value = mapper(value), events = events)
    }

    /**
     * Flat-map the [value] and concatenate the [events] of this [DomainResult] using the given mapper
     */
    inline fun <R> flatMap(mapper: (T) -> DomainResult<R>): DomainResult<R> {
        val (mappedValue, mappedEvents) = mapper(value)
        return DomainResult(mappedValue, events + mappedEvents)
    }

    @JvmInline
    value class Recorder(private val events: MutableList<DomainEvent>) : Iterable<DomainEvent> by events {
        /**
         * Record a new [DomainEvent].
         */
        operator fun invoke(event: DomainEvent) {
            events.add(event)
        }

        /**
         * Record all given new [DomainEvent] items.
         */
        operator fun invoke(vararg event: DomainEvent) {
            events.addAll(event)
        }

        /**
         * Record all events contained in the given [DomainEvent] collection.
         */
        operator fun invoke(events: Iterable<DomainEvent>) {
            this.events.addAll(events)
        }

        /**
         * Record all events contained within the given [DomainResult] collection,
         * and return the value that was wrapped in [events].
         */
        operator fun <T> invoke(events: DomainResult<T>): T {
            this.events.addAll(events.events)
            return events.value
        }

        fun getEvents() = events.toList()
    }
}
