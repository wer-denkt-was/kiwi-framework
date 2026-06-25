package de.kiwi.framework.web

import de.kiwi.api.domain.DomainException
import de.kiwi.api.util.slf4j
import org.springframework.beans.factory.InitializingBean
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException

private val log = slf4j<ExceptionResponseManager>()

/**
 * Manager for resolving [Exceptions][Exception] in an HTTP request context.
 * Exceptions are resolved using [ExceptionResponseResolver] instances, which are provided to the manager
 * using [ExceptionResponseManager.ResolverProvider] beans.
 */
class ExceptionResponseManager(
    private val resolverProviders: Iterable<ResolverProvider>,
) : InitializingBean {
    /**
     * Beans implementing this interface will be used to extend the [ExceptionResponseResolver]s available
     * to the [ExceptionResponseManager] component.
     */
    fun interface ResolverProvider {
        fun provideResolvers(): Collection<ExceptionResponseResolver<*>>
    }

    private lateinit var resolvers: List<ExceptionResponseResolver<*>>

    override fun afterPropertiesSet() {
        resolvers = resolverProviders.flatMap { it.provideResolvers() }
    }

    fun resolveException(exception: Throwable): ErrorResponse {
        val resolver = findMostSpecificResolver(exception)
        @Suppress("UNCHECKED_CAST") val typedResolver = resolver as ExceptionResponseResolver<Throwable>
        return typedResolver.resolve(exception)
    }

    private fun findMostSpecificResolver(exception: Throwable): ExceptionResponseResolver<*> {
        return resolvers.asSequence()
            .filter { it.exceptionClass.isAssignableFrom(exception.javaClass) }
            .maxWithOrNull { a, b ->
                when {
                    // Orders by most specific subclass of exception
                    a.exceptionClass.isAssignableFrom(b.exceptionClass) -> -1
                    b.exceptionClass.isAssignableFrom(a.exceptionClass) -> +1
                    else -> 0
                }
            }
            ?: unknownExceptionResolver
    }
}

class KiwiBuiltinExceptionResponseResolverProvider : ExceptionResponseManager.ResolverProvider {
    override fun provideResolvers() = listOf<ExceptionResponseResolver<*>>(
        ExceptionResponseResolver<DomainException> {
            ExceptionResponseResolver.ResolvedError(
                status = HttpStatus.valueOf(it.status.value),
                message = it.code,
                arguments = it.args,
                errorType = it.eventName
            )
        },
        ExceptionResponseResolver<IllegalArgumentException> {
            it.badRequest("illegal-argument")
        },
        ExceptionResponseResolver<IllegalStateException> {
            it.badRequest("illegal-state")
        },
        ExceptionResponseResolver<HttpMessageNotReadableException> {
            it.badRequest("message-not-readable")
        },
        ExceptionResponseResolver<DataIntegrityViolationException> {
            it.internalError("data-integrity-violation")
        },
        ExceptionResponseResolver<AuthenticationException> {
            it.status("authentication-exception", HttpStatus.UNAUTHORIZED)
        },
        ExceptionResponseResolver<AccessDeniedException> {
            it.status("access-denied", HttpStatus.FORBIDDEN)
        },
    )
}

// Least specific resolver that can handle all unspecific exceptions, but *will* log an error when invoked
private val unknownExceptionResolver = ExceptionResponseResolver<Throwable> { ex ->
    log.error("Unknown Exception: ${ex.message}", ex)
    ExceptionResponseResolver.ResolvedError(
        status = HttpStatus.INTERNAL_SERVER_ERROR,
        message = "unknown-exception",
        arguments = mapOf("reason" to ex.message, "type" to ex.javaClass.simpleName),
        errorType = ex.javaClass.name,
    )
}

/**
 * Interface of an exception resolver, capable of resolving a specific exception type [E] into an [ErrorResponse]
 */
interface ExceptionResponseResolver<E : Throwable> {
    val exceptionClass: Class<E>
    fun resolve(exception: E): ErrorResponse

    companion object {
        /** Create an inline [ExceptionResponseResolver] instance */
        inline operator fun <reified E : Throwable> invoke(
            crossinline resolveFn: (ex: E) -> ResolvedError,
        ) = object : ExceptionResponseResolver<E> {

            override val exceptionClass = E::class.java

            override fun resolve(exception: E): ErrorResponse {
                val resolved = resolveFn(exception)
                return ErrorResponse(
                    status = resolved.status,
                    body = ErrorResponse.ErrorBody(
                        message = resolved.message,
                        arguments = resolved.arguments,
                        errorType = resolved.errorType,
                    )
                )
            }
        }
    }

    /** Internal return-type class used for [Companion.invoke] */
    data class ResolvedError(
        val status: HttpStatus,
        val message: String,
        val arguments: Map<String, *>,
        val errorType: String,
    )
}

/**
 * Data class containing details of an error.
 */
data class ErrorResponse(
    val status: HttpStatus,
    val body: ErrorBody,
) {
    data class ErrorBody(
        val message: String,
        val arguments: Map<String, *>,
        val errorType: String,
    )
}

// Simple generic exception to error-response converter
internal fun Exception.status(message: String, status: HttpStatus) = ExceptionResponseResolver.ResolvedError(
    status = status,
    message = message,
    arguments = mapOf("reason" to this.message),
    errorType = javaClass.name,
)

internal fun Exception.badRequest(message: String) = status(message, HttpStatus.BAD_REQUEST)

internal fun Exception.internalError(message: String) = status(message, HttpStatus.INTERNAL_SERVER_ERROR)
