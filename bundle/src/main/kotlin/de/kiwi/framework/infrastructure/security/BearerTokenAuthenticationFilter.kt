package de.kiwi.framework.infrastructure.security

import de.kiwi.api.application.service.AuthenticationTokenService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AuthenticationDetailsSource
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextHolderStrategy
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter

private const val BEARER_PREFIX = "Bearer "
private const val ACCESS_TOKEN_QUERY_PARAM = "_accessToken"

class BearerTokenAuthenticationFilter(
    private val authenticationTokenService: AuthenticationTokenService,
) : OncePerRequestFilter() {
    private var securityContextHolderStrategy: SecurityContextHolderStrategy =
        SecurityContextHolder.getContextHolderStrategy()

    private var securityContextRepository: SecurityContextRepository =
        RequestAttributeSecurityContextRepository()

    private var detailsSource: AuthenticationDetailsSource<HttpServletRequest, *> =
        WebAuthenticationDetailsSource()

    fun setSecurityContextHolderStrategy(strategy: SecurityContextHolderStrategy) {
        securityContextHolderStrategy = strategy
    }

    fun setSecurityContextRepository(repository: SecurityContextRepository) {
        securityContextRepository = repository
    }

    fun setDetailsSource(source: AuthenticationDetailsSource<HttpServletRequest, *>) {
        detailsSource = source
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val bearerToken = readBearerToken(request)
        if (bearerToken == null) {
            filterChain.doFilter(request, response)
        } else try {
            val authentication = authenticateBearerToken(bearerToken, request)
            authenticationSuccess(authentication, request, response)
            filterChain.doFilter(request, response)
        } catch (ex: AuthenticationException) {
            SecurityContextHolder.clearContext()
            throw ex
        }
    }

    private fun readBearerToken(request: HttpServletRequest): String? {
        // Accept HTTP "Authorization: Bearer ..." header,
        // or alternatively "_accessToken" query parameter
        return request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX) }
            ?.removePrefix(BEARER_PREFIX)
            ?: request.getParameter(ACCESS_TOKEN_QUERY_PARAM)
                ?.takeIf { it.isNotEmpty() }
    }

    private fun authenticateBearerToken(bearerToken: String, request: HttpServletRequest): Authentication {
        val authToken = authenticationTokenService.authenticateToken(bearerToken)
        authToken.details = detailsSource.buildDetails(request)
        return authToken
    }

    private fun authenticationSuccess(
        auth: Authentication,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val context = SecurityContextHolder.getContext()
        context.authentication = auth
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
    }
}
