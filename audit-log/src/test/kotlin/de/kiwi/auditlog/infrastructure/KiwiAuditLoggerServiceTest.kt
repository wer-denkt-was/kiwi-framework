package de.kiwi.auditlog.infrastructure

import de.kiwi.api.application.service.AuditLoggerService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.domain.Auditable
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.DomainException
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.auditlog.KiwiAuditLogAutoConfiguration
import de.kiwi.auditlog.KiwiAuditLogPackagesAutoConfiguration
import de.kiwi.auditlog.application.AuditLogRepository
import de.kiwi.auditlog.domain.AuditLogEntry
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.hamcrest.TypeSafeMatcher
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.skyscreamer.jsonassert.JSONCompareMode
import org.skyscreamer.jsonassert.JSONParser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.liquibase.autoconfigure.LiquibaseAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.skyscreamer.jsonassert.comparator.DefaultComparator as JSONComparator

@DataJpaTest(
    showSql = true,
    properties = [
        "spring.datasource.url=jdbc:tc:mariadb:latest:///auditLog",
        "spring.jpa.hibernate.format_sql=true",
        "kiwi.audit-log.enabled=true",
        "kiwi.audit-log.serializer=jackson",
        "spring.liquibase.changeLog=classpath:/db/changelog/db.audit-log-master.xml",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class KiwiAuditLoggerServiceTest {

    @SpringBootApplication
    @ImportAutoConfiguration(
        LiquibaseAutoConfiguration::class,
        JacksonAutoConfiguration::class,
        KiwiAuditLogPackagesAutoConfiguration::class,
        KiwiAuditLogAutoConfiguration::class,
    )
    class TestApplicationConfig {
        @Bean
        fun transactionService(): TransactionService = TransactionService.DoNothing
    }

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var auditLoggerService: AuditLoggerService

    @Autowired
    private lateinit var auditLogRepository: AuditLogRepository

    @BeforeEach
    fun setUp() {

    }

    @AfterEach
    fun tearDown() {
        clearAuditLog()
    }

    @Test
    fun auditLog() {
        val t1 = LocalDateTime.now().toInstant(ZoneOffset.UTC)
        val t2 = t1.plusSeconds(10)
        val t3 = t2.plusSeconds(10)
        val t4 = t3.plusSeconds(10)

        fun logEvent(event: Auditable) = auditLoggerService.auditLog(event, TestUser)
        logEvent(TestEvent(TestDetails("One: Event"), t1))
        logEvent(TestException(DomainException.Status.BadRequest, t2, "Two: Exception"))
        logEvent(TestEvent(TestDetails("Three: Event"), t3))
        logEvent(TestException(DomainException.Status.NotFound, t4, "Three: Exception"))

        entityManager.flush()
        entityManager.clear()

        val result = auditLogRepository.findAll().onEachIndexed { i, entry ->
            println("[$i]> $entry")
        }

        assertThat(
            result,
            contains(
                matchAuditLogEntry(
                    userId = equalTo(TestUser.userId),
                    userName = equalTo(TestUser.username),
                    eventName = equalTo(TestEvent::class.qualifiedName),
                    severity = sameInstance(Auditable.Severity.Event),
                    occurredOn = equalTo(truncatedTimestamp(t1)),
                    expectedDetails = """{"message":"One: Event"}"""
                ),
                matchAuditLogEntry(
                    userId = equalTo(TestUser.userId),
                    userName = equalTo(TestUser.username),
                    eventName = equalTo(TestException::class.qualifiedName),
                    severity = sameInstance(Auditable.Severity.Error),
                    occurredOn = equalTo(truncatedTimestamp(t2)),
                    expectedDetails = """{"status":400,"code":"test-exception","args":{"args":["Two: Exception"]}}""",
                ),
                matchAuditLogEntry(
                    userId = equalTo(TestUser.userId),
                    userName = equalTo(TestUser.username),
                    eventName = equalTo(TestEvent::class.qualifiedName),
                    severity = sameInstance(Auditable.Severity.Event),
                    occurredOn = equalTo(truncatedTimestamp(t3)),
                    expectedDetails = """{"message":"Three: Event"}""",
                ),
                matchAuditLogEntry(
                    userId = equalTo(TestUser.userId),
                    userName = equalTo(TestUser.username),
                    eventName = equalTo(TestException::class.qualifiedName),
                    severity = sameInstance(Auditable.Severity.Error),
                    occurredOn = equalTo(truncatedTimestamp(t4)),
                    expectedDetails = """{"status":404,"code":"test-exception","args":{"args":["Three: Exception"]}}""",
                ),
            )
        )
    }

    private fun clearAuditLog() {
        auditLogRepository.deleteAll()
        auditLogRepository.flush()
    }
}

private fun truncatedTimestamp(instant: Instant, zoneOffset: ZoneOffset = ZoneOffset.UTC) =
    LocalDateTime.ofInstant(instant, zoneOffset).truncatedTo(ChronoUnit.SECONDS)

private fun matchAuditLogEntry(
    userId: Matcher<Long?>,
    userName: Matcher<String>,
    eventName: Matcher<String>,
    severity: Matcher<Auditable.Severity>,
    occurredOn: Matcher<LocalDateTime>,
    expectedDetails: String,
): Matcher<AuditLogEntry> = allOf(
    hasProperty("userId", userId),
    hasProperty("userName", userName),
    hasProperty("eventName", eventName),
    hasProperty("severity", severity),
    hasProperty("occurredOn", occurredOn),
    hasProperty("detailsJson", matchJsonLike(expectedDetails)),
)

private fun matchJsonLike(expected: String) = object : TypeSafeMatcher<String>(String::class.java) {
    override fun describeTo(description: Description) {
        description.appendText("JSON match")
    }

    override fun matchesSafely(item: String): Boolean {
        val expectedJson = JSONParser.parseJSON(expected) as JSONObject
        val actualJson = JSONParser.parseJSON(item) as JSONObject
        val comparator = JSONComparator(JSONCompareMode.NON_EXTENSIBLE)
        val result = comparator.compareJSON(expectedJson, actualJson)
        return result.passed()
    }
}

private data class TestDetails(val message: String)

private class TestEvent(
    override val details: TestDetails,
    timestamp: Instant,
) : DomainEvent(
    clock = Clock.fixed(timestamp, ZoneOffset.UTC)
)

private class TestException(
    status: Status,
    timestamp: Instant,
    vararg args: Any?,
) : DomainException(
    status = status,
    code = "test-exception",
    args = mapOf("args" to args),
    clock = Clock.fixed(timestamp, ZoneOffset.UTC)
)

private object TestUser : UserDescriptor {
    override val userId = 12345L
    override val identity = this
    override val username = "TestUser"
    override val authenticated = true
    override val roles = emptySet<String>()
    override val principal = null
}
