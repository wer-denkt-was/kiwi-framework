package de.kiwi.framework.infrastructure.jpa

import de.kiwi.api.application.service.EntityTypeProvider
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class EntityTypeProviderConfiguration {

    @Bean
    @ConditionalOnClass(EntityManager::class)
    fun entityTypeProvider(): EntityTypeProvider = JpaEntityTypeProvider()
}
