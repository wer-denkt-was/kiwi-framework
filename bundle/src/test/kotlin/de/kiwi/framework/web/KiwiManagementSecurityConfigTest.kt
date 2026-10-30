package de.kiwi.framework.web

import de.kiwi.api.application.service.UserDescriptorProvider
import de.kiwi.api.domain.UserDescriptor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity

private const val TEST_USERNAME = "management"
private const val TEST_PASSWORD = "Open Sesame!"

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "kiwi.web.management-security.enabled=true",
        "kiwi.web.management-security.management-username=$TEST_USERNAME",
        "kiwi.web.management-security.management-password=$TEST_PASSWORD",
        "kiwi.web.management-security.management-password-encoding=plain",
        "kiwi.web.management-security.permit-all-endpoints=health",
        "management.endpoints.web.exposure.include=*",
        "spring.mustache.check-template-location=false",
        "spring.jpa.open-in-view=false",
    ]
)
class KiwiManagementSecurityConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class KiwiManagementSecurityConfigTestApplicationConfig {

        @Bean
        fun userDescriptorProvider() = UserDescriptorProvider { UserDescriptor.ANONYMOUS }
    }

    @LocalServerPort
    private var localPort: Int = 0

    private lateinit var testClient: RestClient

    @BeforeEach
    fun setUp() {
        testClient = RestClient.builder()
            .baseUrl("http://127.0.0.1:$localPort")
            .defaultStatusHandler { _ -> true } // Don't throw Exception on HTTP error status
            .build()
    }

    @Test
    fun permitAnonymousRequestToPublicEndpoint() {
        val responseEntity = anonymousRequestTo("/actuator/health")
        assertEquals(HttpStatus.OK, responseEntity.statusCode)
    }

    @Test
    fun denyAnonymousRequestToSecuredEndpoint() {
        val responseEntity = anonymousRequestTo("/actuator/info")
        assertEquals(HttpStatus.UNAUTHORIZED, responseEntity.statusCode)
    }

    @Test
    fun allowAuthenticatedRequestToPublicEndpoint() {
        val responseEntity = authenticatedRequestTo("/actuator/health")
        assertEquals(HttpStatus.OK, responseEntity.statusCode)
    }

    @Test
    fun allowAuthenticatedRequestToSecuredEndpoint() {
        val responseEntity = authenticatedRequestTo("/actuator/info")
        assertEquals(HttpStatus.OK, responseEntity.statusCode)
    }

    @Test
    fun denyInvalidRequestToPublicEndpoint() {
        val responseEntity = invalidRequestTo("/actuator/health")
        assertEquals(HttpStatus.UNAUTHORIZED, responseEntity.statusCode)
    }

    @Test
    fun denyInvalidRequestToSecuredEndpoint() {
        val responseEntity = invalidRequestTo("/actuator/info")
        assertEquals(HttpStatus.UNAUTHORIZED, responseEntity.statusCode)
    }

    private fun anonymousRequestTo(endpoint: String): ResponseEntity<*> = testClient
        .get().uri(endpoint)
        .retrieve().toEntity<String>()

    private fun authenticatedRequestTo(endpoint: String) = testClient
        .get().uri(endpoint)
        .headers { it.setBasicAuth(TEST_USERNAME, TEST_PASSWORD) }
        .retrieve().toEntity<String>()

    private fun invalidRequestTo(endpoint: String) = testClient
        .get().uri(endpoint)
        .headers { it.setBasicAuth(TEST_USERNAME, "THIS IS THE WRONG PASSWORD!") }
        .retrieve().toEntity<String>()
}
