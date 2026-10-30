package de.kiwi.api.domain

import jakarta.persistence.criteria.AbstractQuery
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Composition of access controls, where a `null` predicate is unrestricted access.
 */
class AccessControlCompositionTest {
    private val restricted = mock<Predicate>()
    private val other = mock<Predicate>()
    private val disjunction = mock<Predicate>()
    private val conjunction = mock<Predicate>()

    private val builder = mock<CriteriaBuilder> {
        on { or(restricted, other) } doReturn disjunction
        on { and(restricted, other) } doReturn conjunction
    }

    private val root = mock<Root<Any>>()
    private val query = mock<AbstractQuery<*>>()

    private val restrictedAccess = AccessControl { AccessControlSpecification { _, _, _ -> restricted } }
    private val otherAccess = AccessControl { AccessControlSpecification { _, _, _ -> other } }
    private val unrestrictedAccess = AccessControl { AccessControlSpecification { _, _, _ -> null } }

    private fun AccessControl<Any>.predicate() =
        checkAuthorized(UserDescriptor.SYSTEM).accessControlPredicate(root, query, builder)

    @Test
    fun or() {
        assertSame(disjunction, (restrictedAccess or otherAccess).predicate())
        assertNull((restrictedAccess or unrestrictedAccess).predicate())
        assertNull((unrestrictedAccess or restrictedAccess).predicate())
    }

    @Test
    fun and() {
        assertSame(conjunction, (restrictedAccess and otherAccess).predicate())
        assertSame(restricted, (restrictedAccess and unrestrictedAccess).predicate())
        assertSame(restricted, (unrestrictedAccess and restrictedAccess).predicate())
    }

    @Test
    fun anyOfAndAllOf() {
        assertSame(disjunction, AccessControl.anyOf(restrictedAccess, otherAccess).predicate())
        assertSame(restricted, AccessControl.anyOf(restrictedAccess).predicate())
        assertNull(AccessControl.anyOf(restrictedAccess, unrestrictedAccess).predicate())
        assertNull(AccessControl.anyOf<Any>().predicate())

        assertSame(conjunction, AccessControl.allOf(restrictedAccess, otherAccess).predicate())
        assertSame(restricted, AccessControl.allOf(restrictedAccess, unrestrictedAccess).predicate())
        assertNull(AccessControl.allOf<Any>().predicate())
    }
}
