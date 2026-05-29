package de.kiwi.framework.infrastructure.security

import de.kiwi.api.domain.withUserId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.User

class SpringUserDescriptorTest {
    private val userId = 12345L
    private val user = User(
        "test@localhost",
        "this is not a password!",
        setOf(SimpleGrantedAuthority("USER"), SimpleGrantedAuthority("AUDITOR")),
    )

    @Test
    fun userDescriptorWithId() {
        val userWithId = user.withUserId(userId)
        val userDescriptor = SpringUserDescriptor(userWithId)

        assertEquals(userId, userDescriptor.userId)
        assertEquals("test@localhost", userDescriptor.username)
        assertEquals(true, userDescriptor.authenticated)
        assertEquals(setOf("USER", "AUDITOR"), userDescriptor.roles)
        assertSame(userWithId, userDescriptor.principal)
    }

    @Test
    fun userDescriptorWithoutId() {
        val userDescriptor = SpringUserDescriptor(user)

        assertNull(userDescriptor.userId)
        assertEquals("test@localhost", userDescriptor.username)
        assertEquals(true, userDescriptor.authenticated)
        assertEquals(setOf("USER", "AUDITOR"), userDescriptor.roles)
        assertSame(user, userDescriptor.principal)
    }
}
