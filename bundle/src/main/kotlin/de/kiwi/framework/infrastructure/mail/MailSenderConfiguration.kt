package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.util.slf4j
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.mail.template.ResourceMailTemplateProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.mail.javamail.JavaMailSender

private val log = slf4j<MailSenderConfiguration>()

@Configuration
class MailSenderConfiguration(
    private val props: KiwiFrameworkProperties,
    private val resourceLoader: ResourceLoader,
) {
    @Bean
    fun springMailSenderService(
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
        val availableRenderers = rendererProvider.associateBy { it.identifier }
        log.info("MailTemplateRenderer instances available: ${availableRenderers.keys}")

        val delegates = props.mail.templateRenderers.map { identifier ->
            checkNotNull(availableRenderers[identifier]) {
                log.error(missingTemplateRendererMessage(identifier))
                "Missing MailTemplateRenderer with identifier: $identifier"
            }
        }

        val pipelineDescription = delegates.joinToString(" -> ", "(template) -> ", " -> (message)") { it.identifier }
        log.info("Using MailTemplateRenderer pipeline: $pipelineDescription")

        return MailTemplateRenderer.pipeline(delegates)
    }

    private fun missingTemplateRendererMessage(identifier: String): String {
        return when (identifier.lowercase()) {
            "mjml" -> "Missing the MJML MailTemplateRenderer. Did you include the 'de.kiwi-framework:mjml' module?"
            "mustache" -> "Missing the Mustache MailTemplateRenderer. Please make sure the JMustache-Compiler is available."
            else -> "An unknown renderer ($identifier) could not be found. Are you sure a MailTemplateRenderer with this identifier exists?"
        }
    }
}
