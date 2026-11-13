package de.kiwi.backgroundTask.infrastructure.actuate

import de.kiwi.backgroundTask.application.BackgroundTaskManagement
import de.kiwi.backgroundTask.application.BackgroundTaskManagement.PeriodStats
import de.kiwi.backgroundTask.application.BackgroundTaskManagement.TaskStats
import de.kiwi.backgroundTask.infrastructure.actuate.BackgroundTaskEndpoint.ErrorResponse
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.boot.actuate.endpoint.web.annotation.WebEndpoint
import org.springframework.boot.convert.DurationStyle
import java.time.Duration

/**
 * Management endpoint `backgroundTaskStats`, providing statistics of the background tasks for monitoring and alerting:
 *
 * - `GET /actuator/backgroundTaskStats`: Statistics of all tasks
 * - `GET /actuator/backgroundTaskStats?period=15m`: Statistics of the tasks of the given period
 *   (e.g. `15m`, `6h`, `PT30M`, at most [BackgroundTaskManagement.MAX_STATS_PERIOD])
 *
 * See [TaskStats] and [PeriodStats] for the provided figures.
 * Monitoring may alert e.g. on a growing number of failed tasks, tasks failing to launch (`period.failedUnstarted`),
 * tasks waiting for a worker for long (`oldestPendingSince`), overdue retries (`oldestRetryOn`), or stalled (orphaned)
 * tasks (`stalled`).
 *
 * The endpoint must be exposed (`management.endpoints.web.exposure.include=backgroundTaskStats`), and should be secured
 * by the application. Invalid requests respond with `400` and an [ErrorResponse].
 */
@WebEndpoint(id = "backgroundTaskStats")
class BackgroundTaskStatsEndpoint(private val management: BackgroundTaskManagement) {

    /**
     * `GET /actuator/backgroundTaskStats`: Statistics of the tasks,
     * see [BackgroundTaskManagement.getStats] and [BackgroundTaskManagement.getPeriodStats]
     * @param period Either `null` for all-tasks stats, or the [Duration] of the recent period, e.g. `15m` or `PT1H`
     * @return [TaskStats] or [PeriodStats]
     */
    @ReadOperation
    fun stats(period: String?): WebEndpointResponse<*> = try {
        val stats = when (val periodDuration = period?.let(::parsePeriod)) {
            null -> management.getStats()
            else -> management.getPeriodStats(periodDuration)
        }
        WebEndpointResponse(stats, WebEndpointResponse.STATUS_OK)
    } catch (ex: IllegalArgumentException) {
        WebEndpointResponse(ErrorResponse("invalid-request", ex.message), WebEndpointResponse.STATUS_BAD_REQUEST)
    }

    private fun parsePeriod(period: String): Duration = try {
        DurationStyle.detectAndParse(period)
    } catch (ex: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid period: $period", ex)
    }
}
