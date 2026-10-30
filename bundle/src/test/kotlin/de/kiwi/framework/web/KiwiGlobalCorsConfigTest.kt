package de.kiwi.framework.web

import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.KiwiFrameworkAutoConfiguration
import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.infrastructure.mail.KiwiMailAutoConfiguration
import de.kiwi.mjml.KiwiMjmlAutoConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.client.ClientHttpRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "kiwi.web.global-cors.enabled=true",
        "kiwi.web.global-cors.allow-credentials=true",
        "kiwi.web.global-cors.allowed-origins[0]=http://localhost:5173",
        "kiwi.web.global-cors.allowed-origins[1]=https://kiwi-framework.de",
        "kiwi.web.global-cors.max-age=2h", // = 7200 seconds
        "spring.mustache.check-template-location=false",
        "spring.jpa.open-in-view=false"
    ]
)
class KiwiGlobalCorsConfigTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            DataSourceAutoConfiguration::class,
            SecurityAutoConfiguration::class,
            SecurityFilterAutoConfiguration::class,
            UserDetailsServiceAutoConfiguration::class,
            ServletWebSecurityAutoConfiguration::class,
            ManagementWebSecurityAutoConfiguration::class,
            // Only the global CORS configuration is under test
            KiwiFrameworkAutoConfiguration::class,
            KiwiBusAutoConfiguration::class,
            KiwiMailAutoConfiguration::class,
            KiwiWebAutoConfiguration::class,
            KiwiOpenApiAutoConfiguration::class,
            KiwiMjmlAutoConfiguration::class,
        ]
    )
    @Import(KiwiGlobalCorsConfig::class)
    @EnableConfigurationProperties(KiwiFrameworkProperties::class)
    @RestController
    @RequestMapping("/corsTest")
    class KiwiGlobalCorsConfigTestApplicationConfig {

        @PostMapping
        fun dummyPostEndpoint() = ResponseEntity.ok().build<Nothing>()
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

        // Assume that dummy-endpoint is available
        val dummyResponseEntity = testClient
            .post().uri("/corsTest")
            .retrieve().toBodilessEntity()

        assumeTrue(dummyResponseEntity.statusCode.is2xxSuccessful)
    }

    @Test
    fun corsAllowedOrigin() {
        val responseEntity = testClient
            .options().uri("/corsTest")
            .header(HttpHeaders.ORIGIN, "https://kiwi-framework.de")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .httpRequest { it.print() }
            .retrieve().toBodilessEntity()

        responseEntity.print()
        assertTrue(responseEntity.statusCode.is2xxSuccessful)
        assertEquals("https://kiwi-framework.de", responseEntity.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        assertEquals("true", responseEntity.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
        assertEquals("7200", responseEntity.getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE))
    }

    @Test
    fun corsDisallowedOrigin() {
        val responseEntity = testClient
            .options().uri("/corsTest")
            .header(HttpHeaders.ORIGIN, "https://some-other-domain.co.uk")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .httpRequest { it.print() }
            .retrieve().toBodilessEntity()

        responseEntity.print()
        assertTrue(responseEntity.statusCode.is4xxClientError)
    }
}

private fun ClientHttpRequest.print() {
    println("<< ${method.name()} ${uri.path}")
    headers.print()
}

private fun ResponseEntity<*>.print() {
    println(">> $statusCode")
    headers.print()
}

private fun ResponseEntity<*>.getHeader(name: String): String? = headers.getFirst(name)

// Spring Framework 7: HttpHeaders no longer implements MultiValueMap
private fun HttpHeaders.print() = forEach { name, values ->
    values.forEach { println("- $name: $it") }
}
