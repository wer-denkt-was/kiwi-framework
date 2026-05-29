package de.kiwi.api.application.service

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.kiwi.api.domain.DomainEvent
import de.kiwi.api.domain.UserDescriptor
import de.kiwi.api.util.slf4j
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import ch.qos.logback.classic.Logger as LoggerImpl

class AuditLoggerServiceTest {
    @Test
    fun testConsole() {
        val logger = slf4j<AuditLoggerService>() as LoggerImpl
        val listAppender = ListAppender<ILoggingEvent>()
        listAppender.start()
        logger.addAppender(listAppender)

        val message = "Hello World"
        val consoleLoggerService = AuditLoggerService.Console()
        consoleLoggerService.auditLog(TestEvent(message), TestUser)

        val logEvents = listAppender.list
        listAppender.stop()

        assertEquals(1, logEvents.size)
        val (logEvent) = logEvents
        assertEquals(
            "AuditLog [2024-03-28T12:00:00 ${TestUser.username}] ${TestEvent::class.java.name}: $message",
            logEvent.message
        )
    }
}

private val testTimestamp = OffsetDateTime.parse("2024-03-28T12:00:00Z")
private val testClock = Clock.fixed(testTimestamp.toInstant(), ZoneOffset.UTC)

class TestEvent(message: String) : DomainEvent(testClock) {
    override val details = message
}

object TestUser : UserDescriptor {
    override val userId = null
    override val identity = this
    override val username = "TestUser"
    override val authenticated = true
    override val roles = emptySet<String>()
    override val principal = null
}
