package de.kiwi.framework.infrastructure.jpa

import de.kiwi.api.application.service.EntityTypeProvider
import jakarta.persistence.EntityManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class EntityTypeProviderConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(EntityManager::class)
    fun entityTypeProvider(): EntityTypeProvider = JpaEntityTypeProvider()
}
