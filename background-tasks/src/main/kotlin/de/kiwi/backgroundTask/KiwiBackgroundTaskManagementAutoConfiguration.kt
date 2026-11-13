package de.kiwi.backgroundTask

import de.kiwi.backgroundTask.application.BackgroundTaskManagement
import de.kiwi.backgroundTask.infrastructure.actuate.BackgroundTaskEndpoint
import de.kiwi.backgroundTask.infrastructure.actuate.BackgroundTaskStatsEndpoint
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Autoconfiguration of the management endpoints `backgroundTasks` ([BackgroundTaskEndpoint]) and
 * `backgroundTaskStats` ([BackgroundTaskStatsEndpoint]), if Spring Boot Actuator is on the classpath and the respective
 * endpoint is available (enabled and exposed).
 */
@AutoConfiguration(after = [KiwiBackgroundTaskAutoConfiguration::class])
@ConditionalOnClass(Endpoint::class)
@ConditionalOnBean(BackgroundTaskManagement::class)
class KiwiBackgroundTaskManagementAutoConfiguration {

    /** Management endpoint `backgroundTasks`, if enabled and exposed */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    fun backgroundTaskEndpoint(management: BackgroundTaskManagement) = BackgroundTaskEndpoint(management)

    /** Management endpoint `backgroundTaskStats`, if enabled and exposed */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnAvailableEndpoint
    fun backgroundTaskStatsEndpoint(management: BackgroundTaskManagement) = BackgroundTaskStatsEndpoint(management)
}
