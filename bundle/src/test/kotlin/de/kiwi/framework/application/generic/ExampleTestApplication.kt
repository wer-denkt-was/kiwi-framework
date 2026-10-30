package de.kiwi.framework.application.generic

import de.kiwi.framework.infrastructure.generic.GenericHandlerConfiguration
//import de.kiwi.framework.web.GenericEntityController
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.support.ConversionServiceFactoryBean

@SpringBootApplication(
    scanBasePackages = ["de.kiwi.framework.example", "de.kiwi.framework.application.generic"],
)
@Import(
    JacksonAutoConfiguration::class,
    GenericHandlerConfiguration::class,
)
@EntityScan("de.kiwi.framework.example")
class ExampleTestApplication {

    @Bean
    @ConditionalOnMissingBean
    fun conversionService() = ConversionServiceFactoryBean()
}
