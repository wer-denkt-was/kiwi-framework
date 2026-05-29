package de.kiwi.api.application.repository

import de.kiwi.api.domain.AccessControl
import de.kiwi.api.domain.AccessDenied
import de.kiwi.api.domain.UserDescriptor
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.*
import kotlin.jvm.optionals.getOrNull

interface JpaAccessControlExecutor<T : Any, ID : Any> {

    fun findAuthorized(
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): List<T>

    fun findAuthorized(
        sort: Sort,
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): List<T>

    fun findAuthorized(
        pageable: Pageable,
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): Page<T>

    fun findAuthorizedByIds(
        ids: Iterable<ID>,
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): List<T>

    fun findAuthorizedById(
        id: ID,
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): Optional<T>

    fun existsAuthorizedById(
        id: ID,
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): Boolean

    fun countAuthorized(
        accessControl: AccessControl<T>,
        user: UserDescriptor? = null,
        allowAdmin: Boolean = true,
    ): Long
}

inline fun <reified T : Any, ID : Any> JpaAccessControlExecutor<T, ID>.requireAuthorizedById(
    id: ID,
    accessControl: AccessControl<T>,
    user: UserDescriptor,
    allowAdmin: Boolean = true,
): T {
    return findAuthorizedById(id, accessControl, user, allowAdmin)
        .getOrNull() ?: throw AccessDenied(user)
}
