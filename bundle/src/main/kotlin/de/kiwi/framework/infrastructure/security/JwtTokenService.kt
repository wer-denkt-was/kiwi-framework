package de.kiwi.framework.infrastructure.security

import de.kiwi.api.application.service.AuthenticationTokenService
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.domain.UserIdDetails
import de.kiwi.framework.KiwiFrameworkProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtBuilder
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.io.Decoders
import io.jsonwebtoken.security.Keys
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.userdetails.UserDetails
import java.time.Duration
import java.time.Instant
import java.util.*
import javax.crypto.SecretKey

private const val CLAIM_USER_ID = "uid"
private const val CLAIM_USER_ROLES = "roles"

open class JwtTokenService(
    protected val props: KiwiFrameworkProperties.JwtProperties,
    protected val clockProvider: ClockProvider,
) : AuthenticationTokenService {
    private val secretKey: SecretKey

    init {
        require(props.secretKey.isNotEmpty()) { """JWT secret key ("kiwi.jwt.secret-key") is empty""" }
        secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(props.secretKey))
    }

    override fun generateToken(authentication: Authentication): String {
        val (token, _) = generateTemporaryToken(authentication, duration = props.expiration)
        return token
    }

    override fun generateTemporaryToken(
        authentication: Authentication,
        duration: Duration,
    ): AuthenticationTokenService.TemporaryToken {
        val clock = clockProvider.clock()
        val now = Instant.now(clock)
        val expiresOn = now + duration

        val token = Jwts.builder().run {
            createJwtClaims(authentication)

            issuer(props.issuer)
            issuedAt(Date.from(now))
            expiration(Date.from(expiresOn))

            signWith(secretKey, Jwts.SIG.HS256)
            compact()
        }

        return AuthenticationTokenService.TemporaryToken(token, expiresOn)
    }

    override fun authenticateToken(bearerToken: String): UsernamePasswordAuthenticationToken {
        val clock = clockProvider.clock()
        val parser = Jwts
            .parser()
            .verifyWith(secretKey)
            .clock { Date.from(Instant.now(clock)) }
            .apply { if (props.requireIssuer) requireIssuer(props.issuer) }
            .build()

        val claims = parser.parseSignedClaims(bearerToken).payload
        val principal = createUserDetails(claims)
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.authorities)
    }

    protected fun JwtBuilder.createJwtClaims(authentication: Authentication) {
        val principal = authentication.principal
        val userRoles = AuthorityUtils.authorityListToSet(authentication.authorities).joinToString(",")

        // Claim user info
        subject(authentication.name)
        claim(CLAIM_USER_ROLES, userRoles)
        if (principal is UserIdDetails) {
            claim(CLAIM_USER_ID, principal.userId)
        }
    }

    protected fun createUserDetails(claims: Claims): UserDetails {
        return if (claims.containsKey(CLAIM_USER_ID)) JwtUserIdDetails(claims)
        else JwtUserDetails(claims)
    }
}

private open class JwtUserDetails(private val claims: Claims) : UserDetails {
    override fun getUsername(): String = claims.subject
    override fun getPassword(): String? = null
    override fun getAuthorities(): Collection<GrantedAuthority> = authorities

    private val authorities = claims[CLAIM_USER_ROLES]?.let { it as? String }
        ?.let { AuthorityUtils.commaSeparatedStringToAuthorityList(it) }
        ?: throw IllegalArgumentException("Missing $CLAIM_USER_ROLES claim in JWT")
}

private class JwtUserIdDetails(claims: Claims) : JwtUserDetails(claims), UserIdDetails {
    override val userId = claims[CLAIM_USER_ID]?.let { it as? Number }?.toLong()
        ?: throw IllegalArgumentException("Missing $CLAIM_USER_ID claim in JWT")
}
