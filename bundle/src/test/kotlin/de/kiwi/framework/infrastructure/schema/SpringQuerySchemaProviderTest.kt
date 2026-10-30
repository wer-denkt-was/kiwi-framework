package de.kiwi.framework.infrastructure.schema

import de.kiwi.framework.infrastructure.util.ClassPathScannerUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class SpringQuerySchemaProviderTest {

    @Configuration
    @EntityScan("de.kiwi.testing.duplicates")
    class DuplicateSchemasConfig {
        @Bean
        fun classPathScanner() = ClassPathScannerUtil()

        @Bean
        fun querySchemaProvider(classPathScanner: ClassPathScannerUtil) = SpringQuerySchemaProvider(classPathScanner)
    }

    @Test
    fun duplicateSchemasFailStartup() {
        ApplicationContextRunner().withUserConfiguration(DuplicateSchemasConfig::class.java).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).rootCause()
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Duplicate entity schema classes found for entity types")
                .hasMessageContaining("de.kiwi.testing.duplicates.FirstPublisherSchema")
                .hasMessageContaining("de.kiwi.testing.duplicates.SecondPublisherSchema")
        }
    }
}
