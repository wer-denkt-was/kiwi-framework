package de.kiwi.framework.infrastructure.util

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class FrameworkUtilConfiguration {
    @Bean
    fun classPathScannerUtil() = ClassPathScannerUtil()
}
