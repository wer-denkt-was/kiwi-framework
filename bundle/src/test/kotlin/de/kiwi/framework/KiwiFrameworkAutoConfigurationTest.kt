package de.kiwi.framework

import de.kiwi.api.application.command.CommandBus
import de.kiwi.api.application.event.EventBus
import de.kiwi.api.application.query.QueryBus
import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.CommandQueryBus
import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.application.service.TransactionService
import de.kiwi.api.schema.QuerySchemaService
import de.kiwi.framework.infrastructure.bus.KiwiBusAutoConfiguration
import de.kiwi.framework.infrastructure.bus.command.BasicCommandBus
import de.kiwi.framework.infrastructure.mail.KiwiMailAutoConfiguration
import de.kiwi.framework.infrastructure.mail.MailTemplateRenderer
import de.kiwi.framework.infrastructure.mail.template.MustacheMailTemplateRenderer
import de.kiwi.framework.web.*
import de.kiwi.mjml.KiwiMjmlAutoConfiguration
import de.kiwi.mjml.MjmlMailTemplateRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeanProvider
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration
import org.springframework.web.servlet.function.RouterFunction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Wiring tests of the Kiwi auto-configurations.
 * The OpenAPI autoconfiguration is tested by [de.kiwi.framework.web.KiwiOpenApiIntegrationTest].
 */
class KiwiFrameworkAutoConfigurationTest {

    private val contextRunner = WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                HibernateJpaAutoConfiguration::class.java,
                TransactionAutoConfiguration::class.java,
                JacksonAutoConfiguration::class.java,
                HttpMessageConvertersAutoConfiguration::class.java,
                WebMvcAutoConfiguration::class.java,
                KiwiFrameworkAutoConfiguration::class.java,
                KiwiBusAutoConfiguration::class.java,
                KiwiMailAutoConfiguration::class.java,
                KiwiWebAutoConfiguration::class.java,
                KiwiManagementSecurityAutoConfiguration::class.java,
                KiwiMjmlAutoConfiguration::class.java,
            )
        )
        .withPropertyValues("spring.datasource.url=jdbc:h2:mem:framework-autoconfiguration-test")

    @Test
    fun clockZoneAcceptsRegionIdsAndOffsets() {
        contextRunner
            .withPropertyValues("kiwi.clock.zone=Europe/Berlin", "kiwi.clock.fixed=2026-07-01T12:00:00")
            .run { context ->
                val clockProvider = context.getBean<ClockProvider>()
                val clock = clockProvider.clock()
                assertThat(clock.zone).isEqualTo(ZoneId.of("Europe/Berlin"))
                assertThat(clock.instant()).isEqualTo(Instant.parse("2026-07-01T10:00:00Z"))
            }
        contextRunner
            .withPropertyValues("kiwi.clock.zone=+02:00")
            .run { context ->
                assertThat(context.getBean<ClockProvider>().clock().zone).isEqualTo(ZoneOffset.ofHours(2))
            }
    }

    @Test
    fun registersFrameworkBeans() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(QuerySchemaService::class.java)
            assertThat(context).hasSingleBean(TransactionService::class.java)
            assertThat(context).hasSingleBean(EventBus::class.java)
            assertThat(context).hasBean("commandBus")
            assertThat(context).hasBean("queryBus")
            assertThat(context).hasSingleBean(CommandQueryBus::class.java)
            assertThat(context).hasSingleBean(MailSenderService::class.java)
            assertThat(context).hasSingleBean(KiwiControllerAdvice::class.java)
            assertThat(context).hasSingleBean(GenericEntityMethodHandler::class.java)
            assertThat(context).hasSingleBean(AnalyticalReportMethodHandler::class.java)
            assertThat(context).hasBean("genericEntityRouter")
            assertThat(context).hasBean("analyticalReportRouter")
            assertThat(context).doesNotHaveBean(KiwiManagementSecurityAutoConfiguration::class.java)
        }
    }

    @Test
    fun beansCanBeReplaced() {
        val customCommandBus = BasicCommandBus(enableCaching = false)
        contextRunner
            .withBean(CommandBus::class.java, { customCommandBus })
            .run { context ->
                assertThat(context).hasNotFailed()
                // The custom bean replaces the Kiwi bean, both are named after their type
                assertThat(context.getBean("commandBus")).isSameAs(customCommandBus)
            }
    }

    @Test
    fun busesAreUniqueByType() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            // CommandQueryBus implements CommandBus and QueryBus as well, the actual buses are primary
            assertThat(context.getBean<CommandBus>()).isSameAs(context.getBean("commandBus"))
            assertThat(context.getBean<QueryBus>()).isSameAs(context.getBean("queryBus"))
            assertThat(context.getBean<EventBus>()).isSameAs(context.getBean("eventBus"))
            assertThat(context).hasSingleBean(CommandQueryBus::class.java)
        }
    }

    @Test
    fun replacedBusMustBePrimary() {
        val customCommandBus = BasicCommandBus(enableCaching = false)
        contextRunner
            .withBean(CommandBus::class.java, { customCommandBus }, { it.isPrimary = true })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean<CommandBus>()).isSameAs(customCommandBus)
            }
    }

    @Test
    fun webLayerCanBeDisabled() {
        contextRunner
            .withPropertyValues("kiwi.web.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(KiwiRouteHelper::class.java)
                assertThat(context).doesNotHaveBean(KiwiControllerAdvice::class.java)
                assertThat(context).doesNotHaveBean(RouterFunction::class.java)
                assertThat(context).hasSingleBean(CommandQueryBus::class.java)
            }
    }

    @Test
    fun mailTemplateRenderersAreOrdered() {
        contextRunner.run { context ->
            val renderers = context.getBeanProvider<MailTemplateRenderer>().orderedStream().toList()
            assertThat(renderers).hasExactlyElementsOfTypes(
                MustacheMailTemplateRenderer::class.java,
                MjmlMailTemplateRenderer::class.java,
            )
        }
    }

    @Test
    fun mjmlRendererCanBeDisabled() {
        contextRunner
            .withPropertyValues("kiwi.mail.mjml.enabled=false")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(MjmlMailTemplateRenderer::class.java)
                assertThat(context).hasSingleBean(MustacheMailTemplateRenderer::class.java)
            }
    }
}
