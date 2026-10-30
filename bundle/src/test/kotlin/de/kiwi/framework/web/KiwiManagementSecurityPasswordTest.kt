package de.kiwi.framework.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

/**
 * A missing or blank management password fails the application startup.
 */
class KiwiManagementSecurityPasswordTest {

    @Test
    fun blankPasswordFailsStartup() {
        val exception = assertThrows<Exception> {
            SpringApplicationBuilder(KiwiManagementSecurityConfigTest.KiwiManagementSecurityConfigTestApplicationConfig::class.java)
                .web(WebApplicationType.SERVLET)
                .properties(
                    "server.port=0",
                    "kiwi.web.management-security.enabled=true",
                    "kiwi.web.management-security.management-password=",
                    "spring.mustache.check-template-location=false",
                    "spring.jpa.open-in-view=false",
                )
                .run()
                .close()
        }

        val rootCause = generateSequence<Throwable>(exception) { it.cause }.last()
        assertEquals("Missing kiwi.web.management-security.management-password", rootCause.message)
    }
}
