package de.kiwi.framework.web

import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Spring MVC [RestControllerAdvice] handling [DomainException]s thrown in [RestController]s.
 */
@RestControllerAdvice(annotations = [RestController::class])
class KiwiControllerAdvice(private val manager: ExceptionResponseManager) {

    @ExceptionHandler(Exception::class)
    fun handleException(ex: Exception, user: UserDescriptor): ResponseEntity<*> {
        val response = manager.resolveException(ex, user)
        return ResponseEntity
            .status(response.status)
            .body(response.body)
    }
}
