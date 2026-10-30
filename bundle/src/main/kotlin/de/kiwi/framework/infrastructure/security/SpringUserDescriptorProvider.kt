package de.kiwi.framework.infrastructure.security

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import org.springframework.core.Ordered
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails

/**
 * [UserDescriptorProvider] of the current Spring Security [Authentication], converted using the given
 * [AuthenticationUserDescriptorConverter]s, or by default from [UserDetails] principals (see [SpringUserDescriptor]).
 * @param rolePrefix Prefix removed from the granted authorities of [UserDetails] principals
 */
class SpringUserDescriptorProvider(
    authenticationConverters: List<AuthenticationUserDescriptorConverter>,
    rolePrefix: String = SpringUserDescriptor.DEFAULT_ROLE_PREFIX,
) : UserDescriptorProvider {
    private val allAuthenticationConverters: List<AuthenticationUserDescriptorConverter> = run {
        val defaultConverters = listOf(UserDetailsUserDescriptorConverter(rolePrefix))
        (authenticationConverters + defaultConverters).sortedBy { it.order }
    }

    override fun currentUser(): UserDescriptor {
        val context = requireNotNull(SecurityContextHolder.getContext()) { "Missing SecurityContext" }
        val authentication = context.authentication
            ?.takeIf { it.isAuthenticated } // Require authenticated
            ?.takeIf { it !is AnonymousAuthenticationToken } // Require not anonymous
            ?: return UserDescriptor.ANONYMOUS

        val userDescriptor = allAuthenticationConverters.firstNotNullOfOrNull { it.convert(authentication) }
        return checkNotNull(userDescriptor) {
            "Could not convert Authentication ${authentication.javaClass.name} into UserDescriptor"
        }
    }
}

interface AuthenticationUserDescriptorConverter : Converter<Authentication, UserDescriptor?>, Ordered {
    override fun getOrder() = 0
}

private class UserDetailsUserDescriptorConverter(private val rolePrefix: String) : AuthenticationUserDescriptorConverter {
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE // Use default as last

    override fun convert(source: Authentication): UserDescriptor? {
        val details = source.principal as? UserDetails ?: return null
        return SpringUserDescriptor(details, rolePrefix)
    }
}
