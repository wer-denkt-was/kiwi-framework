package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.util.slf4j
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.mail.template.ResourceMailTemplateProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ResourceLoader
import org.springframework.mail.javamail.JavaMailSender

private val log = slf4j<KiwiMailAutoConfiguration>()

/**
 * Autoconfiguration of the Kiwi [MailSenderService], rendering e-mail templates using all available
 * [MailTemplateRenderer] beans, and sending them using the Spring [JavaMailSender].
 */
@AutoConfiguration(afterName = ["org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"])
@EnableConfigurationProperties(KiwiFrameworkProperties::class)
@Import(MustacheMailTemplateRendererConfiguration::class)
class KiwiMailAutoConfiguration(
    private val props: KiwiFrameworkProperties,
    private val resourceLoader: ResourceLoader,
) {
    @Bean
    @ConditionalOnMissingBean
    fun mailSenderService(
        javaMailSenderProvider: ObjectProvider<JavaMailSender>,
        mailTemplateRendererProvider: ObjectProvider<MailTemplateRenderer>,
    ): MailSenderService {
        if (!props.mail.enabled) {
            return DisabledMailSenderService(props.mail)
        }

        val javaMailSender = javaMailSenderProvider.ifAvailable
            ?: return MissingMailSenderService

        val senderAdapter = createSenderAdapter(javaMailSender)
        val templateRenderer = createTemplateRenderer(mailTemplateRendererProvider)
        val templateProvider = ResourceMailTemplateProvider(resourceLoader, props.mail.templateCharset)

        return SpringMailSenderService(
            sender = senderAdapter,
            provider = templateProvider,
            renderer = templateRenderer,
            config = props.mail,
        )
    }

    private fun createSenderAdapter(javaMailSender: JavaMailSender): MailSenderAdapter {
        return MailSenderAdapter.Delegate(buildList {
            if (props.mail.sendToJavaMail) add(MailSenderAdapter.SpringJavaMail(javaMailSender))
            if (props.mail.sendToConsole) add(MailSenderAdapter.Console(javaMailSender))
        })
    }

    private fun createTemplateRenderer(
        rendererProvider: ObjectProvider<MailTemplateRenderer>,
    ): MailTemplateRenderer {
        val renderers = rendererProvider.orderedStream().toList()
        if (renderers.isEmpty()) {
            log.warn("No MailTemplateRenderer available, e-mail templates are sent without rendering")
        }

        val pipelineDescription = renderers.joinToString(" -> ", "(template) -> ", " -> (message)") { it.identifier }
        log.info("Using MailTemplateRenderer pipeline: $pipelineDescription")

        return MailTemplateRenderer.pipeline(renderers)
    }
}
