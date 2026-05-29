package de.kiwi.api.domain

import java.time.Clock
import java.time.LocalDateTime

/**
 * Base class for application domain exceptions.
 * This is an [Auditable] class, which can appear in an audit log.
 */
abstract class DomainException(
    val status: Status,
    val code: String,
    val args: Map<String, *> = emptyMap<String, Nothing>(),
    clock: Clock = Clock.systemUTC(),
) : RuntimeException(formatMessage(code, args)), Auditable {

    sealed class Status(val value: Int) {
        data object BadRequest : Status(400)
        data object Unauthorized : Status(401)
        data object Forbidden : Status(403)
        data object NotFound : Status(404)
        data object MethodNotAllowed : Status(405)
        data object ImATeapot : Status(418)
        data object InternalError : Status(500)
        class Other(value: Int) : Status(value)
    }

    override val eventName: String = javaClass.name
    override val occurredOn: LocalDateTime = LocalDateTime.now(clock)
    override val severity = Auditable.Severity.Error
    override val details = mapOf(
        "status" to status.value,
        "code" to code,
        "args" to args,
    )

    /**
     * DomainException wrapper for unknown Exceptions.
     */
    class Unknown(
        cause: Throwable,
        clock: Clock = Clock.systemUTC(),
    ) : DomainException(
        status = Status.InternalError,
        code = "unknown",
        args = mapOf("exception" to cause.javaClass.name, "message" to cause.message),
        clock = clock,
    ) {
        override val severity = Auditable.Severity.Unknown
    }
}

private fun formatMessage(code: String, args: Map<String, *>): String {
    val argList = args.entries.joinToString(", ") { (key, value) -> "$key = $value" }
    return "$code($argList)"
}
