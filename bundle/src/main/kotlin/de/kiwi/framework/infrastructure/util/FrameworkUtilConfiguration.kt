package de.kiwi.framework.infrastructure.util

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class FrameworkUtilConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun classPathScannerUtil() = ClassPathScannerUtil()
}
