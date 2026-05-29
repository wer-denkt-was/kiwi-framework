package de.kiwi.api.domain

import org.springframework.security.core.userdetails.UserDetails
import java.io.Serializable

/**
 * Extended [UserDetails] interface, providing an additional numeric [userId] property.  Mainly used for providing
 * [UserDescriptor] instances in a [de.kiwi.api.application.service.UserDescriptorProvider] implementation.
 */
interface UserIdDetails : UserDetails {
    val userId: Long
}

/**
 * Wrap this [UserDetails] instance with a user ID to create
 * a [UserIdDetails] instance.
 */
fun UserDetails.withUserId(userId: Long): UserIdDetails =
    UserDetailsWrapper(userId, this)

private class UserDetailsWrapper(
    override val userId: Long,
    val userDetails: UserDetails,
) : Serializable, UserIdDetails, UserDetails by userDetails {

    override fun isAccountNonExpired() = userDetails.isAccountNonExpired
    override fun isAccountNonLocked() = userDetails.isAccountNonLocked
    override fun isCredentialsNonExpired() = userDetails.isCredentialsNonExpired
    override fun isEnabled() = userDetails.isEnabled
}
