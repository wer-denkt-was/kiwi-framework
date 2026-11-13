package de.kiwi.api.backgroundTask

import de.kiwi.api.backgroundTask.BackgroundTaskHandler.Companion.noRetry
import de.kiwi.api.backgroundTask.BackgroundTaskHandler.Companion.retry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class BackgroundTaskHandlerTest {

    private data class ExampleRequest(val value: Int) : BackgroundTaskRequest()

    private fun contextOfAttempt(attempt: Int) = InMemoryBackgroundTaskContext(
        request = ExampleRequest(1),
        attempt = attempt,
    )

    @Test
    fun failureResponses() {
        assertFalse(noRetry().shouldRetry)
        assertEquals(BackgroundTaskHandler.FailureResponse(true, Duration.ZERO), retry())
        assertEquals(Duration.ofSeconds(5), BackgroundTaskHandler.retryInSeconds(5).retryDelay)
        assertThrows<IllegalArgumentException> { retry(Duration.ofSeconds(-1)) }
    }

    @Test
    fun backoffDelaysStartWithTheBaseDelay() {
        val delays = (1..4).map { attempt ->
            contextOfAttempt(attempt).retryWithBackoff(baseDelay = Duration.ofSeconds(10))
        }
        assertTrue(delays.all { it.shouldRetry })
        assertEquals(listOf(10L, 20L, 40L, 80L), delays.map { it.retryDelay.toSeconds() })
    }

    @Test
    fun backoffIsLimitedByMaxAttempts() {
        val responses = (1..4).map { attempt ->
            contextOfAttempt(attempt).limitRetries(maxAttempts = 3) { retry() }
        }
        // The task runs at most three times: Attempts 1 and 2 are retried, attempt 3 is the last
        assertEquals(listOf(true, true, false, false), responses.map { it.shouldRetry })

        // A single attempt is never retried
        val singleResponse = contextOfAttempt(1).limitRetries(maxAttempts = 1) { retry() }
        assertFalse(singleResponse.shouldRetry)
    }

    @Test
    fun backoffIsCappedByMaxDelay() {
        val response = contextOfAttempt(5).retryWithBackoff(
            baseDelay = Duration.ofSeconds(10),
            exponent = 3.0,
            maxDelay = Duration.ofMinutes(5),
        )
        assertEquals(Duration.ofMinutes(5), response.retryDelay)

        // Constant delay with exponent 1
        val constant = contextOfAttempt(7).retryWithBackoff(Duration.ofSeconds(2), exponent = 1.0)
        assertEquals(Duration.ofSeconds(2), constant.retryDelay)
    }

    @Test
    fun backoffSaturatesWithoutMaxDelay() {
        val response = contextOfAttempt(10_000).retryWithBackoff(Duration.ofSeconds(1))
        assertEquals(Duration.ofMillis(Long.MAX_VALUE), response.retryDelay)
    }

    @Test
    fun backoffWithoutBaseDelayStaysZero() {
        // 0 * 2^n would be NaN once 2^n overflows to Infinity
        val response = contextOfAttempt(10_000).retryWithBackoff(Duration.ZERO)
        assertTrue(response.shouldRetry)
        assertEquals(Duration.ZERO, response.retryDelay)
    }

    @Test
    fun backoffValidatesArguments() {
        val context = contextOfAttempt(1)
        assertThrows<IllegalArgumentException> {
            context.retryWithBackoff(Duration.ofSeconds(-1))
        }
        assertThrows<IllegalArgumentException> {
            context.limitRetries(maxAttempts = 0) { retry() }
        }
        assertThrows<IllegalArgumentException> {
            context.retryWithBackoff(Duration.ofSeconds(1), exponent = 0.5)
        }
        assertThrows<IllegalArgumentException> {
            context.retryWithBackoff(Duration.ofSeconds(1), maxDelay = Duration.ofSeconds(-1))
        }
    }
}
