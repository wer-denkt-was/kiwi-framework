package de.kiwi.api.domain

import java.time.Clock
import java.time.LocalDateTime

/**
 * Base class for events produced by handling commands.
 * This is an [Auditable] class, which can appear in an audit log.
 */
abstract class DomainEvent(
    clock: Clock = Clock.systemUTC(),
) : Auditable {
    final override val eventName: String = javaClass.name
    final override val occurredOn: LocalDateTime = LocalDateTime.now(clock)
    final override val severity = Auditable.Severity.Event
}

/**
 * Specialized base class for events produced by handling commands that revolve around one core subject.
 * This is an [Auditable] class extending [DomainEvent], which can appear in an audit log.
 * @see DomainEvent
 */
abstract class SubjectDomainEvent<T : Any>(
    val subject: T,
    clock: Clock = Clock.systemUTC(),
) : DomainEvent(clock) {
    abstract val detailsProvider: DetailsProvider<T>
    override val details by lazy { detailsProvider.toDetails(subject) }
}
