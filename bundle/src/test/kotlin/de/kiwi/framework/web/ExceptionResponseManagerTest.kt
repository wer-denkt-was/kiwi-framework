package de.kiwi.framework.web

import de.kiwi.framework.application.authz.TestUserDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException

class ExceptionResponseManagerTest {
    private val dummyUser = TestUserDescriptor(0L)
    private lateinit var manager: ExceptionResponseManager

    @BeforeEach
    fun setUp() {
        val provider = ExceptionResponseManager.ResolverProvider {
            listOf(
                ExceptionResponseResolver<RuntimeException> { it.badRequest("runtime-exception") },
                ExceptionResponseResolver<IllegalStateException> { it.badRequest("illegal-state") },
                ExceptionResponseResolver<IllegalArgumentException> { it.badRequest("illegal-argument") },
                ExceptionResponseResolver<CustomIllegalArgumentException> { it.badRequest("custom-illegal") },
                ExceptionResponseResolver<CustomRuntimeException> { it.badRequest("custom-runtime") },
                ExceptionResponseResolver<SpecializedCustomRuntimeException> { it.badRequest("special-custom") },
            )
        }
        manager = ExceptionResponseManager(resolverProviders = listOf(provider))
        manager.afterPropertiesSet()
    }

    @Test
    fun testMostSpecificException() {
        assertErrorMessage("runtime-exception", RuntimeException("error"))
        assertErrorMessage("runtime-exception", AccessDeniedException("denied"))
        assertErrorMessage("illegal-state", IllegalStateException("state"))
        assertErrorMessage("illegal-argument", IllegalArgumentException("argument"))
        assertErrorMessage("custom-illegal", CustomIllegalArgumentException())
        assertErrorMessage("custom-runtime", CustomRuntimeException())
        assertErrorMessage("custom-runtime", UnhandledCustomRuntimeException())
        assertErrorMessage("special-custom", SpecializedCustomRuntimeException())
        assertErrorMessage("unknown-exception", UnknownException()) // Invokes least specific resolver
    }

    private fun assertErrorMessage(expectedMessage: String, exception: Exception) {
        val actualResponse = manager.resolveException(exception, user = dummyUser)
        println("Resolved ${exception.javaClass.name} => $actualResponse")
        assertEquals(expectedMessage, actualResponse.body.message)
    }
}

private class CustomIllegalArgumentException : IllegalArgumentException()

private open class CustomRuntimeException : RuntimeException()

private class SpecializedCustomRuntimeException : CustomRuntimeException()

private class UnhandledCustomRuntimeException : CustomRuntimeException()

private class UnknownException : Exception()
