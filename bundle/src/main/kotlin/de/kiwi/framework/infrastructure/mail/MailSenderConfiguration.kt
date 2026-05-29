package de.kiwi.framework.infrastructure.mail

import com.samskivert.mustache.Mustache
import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.util.slf4j
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.KiwiFrameworkProperties.MailProperties
import de.kiwi.framework.infrastructure.mail.template.MustacheMailTemplateRenderer
import de.kiwi.framework.infrastructure.mail.template.ResourceMailTemplateProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.mail.javamail.JavaMailSender

private val log = slf4j<MailSenderConfiguration>()

@Configuration
class MailSenderConfiguration(
    props: KiwiFrameworkProperties,
    private val resourceLoader: ResourceLoader,
) {
    private val props: MailProperties = props.mail

    @Bean
    @ConditionalOnClass(Mustache::class)
    fun mustacheMailTemplateRenderer(
        compilerProvider: ObjectProvider<Mustache.Compiler>,
    ): MustacheMailTemplateRenderer {
        val compiler = compilerProvider.getIfUnique { Mustache.compiler() }
        return MustacheMailTemplateRenderer(compiler, cacheSize = props.templateCacheSize)
    }

    @Bean
    fun springMailSenderService(
        javaMailSenderProvider: ObjectProvider<JavaMailSender>,
        mailTemplateRendererProvider: ObjectProvider<MailTemplateRenderer>,
    ): MailSenderService {
        if (!props.enabled) {
            return DisabledMailSenderService(props)
        }

        val javaMailSender = javaMailSenderProvider.ifAvailable
            ?: return MissingMailSenderService

        val senderAdapter = createSenderAdapter(javaMailSender)
        val templateRenderer = createTemplateRenderer(mailTemplateRendererProvider)
        val templateProvider = ResourceMailTemplateProvider(resourceLoader, props.templateCharset)

        return SpringMailSenderService(
            sender = senderAdapter,
            provider = templateProvider,
            renderer = templateRenderer,
            config = props,
        )
    }

    private fun createSenderAdapter(javaMailSender: JavaMailSender): MailSenderAdapter {
        return MailSenderAdapter.Delegate(buildList {
            if (props.sendToJavaMail) add(MailSenderAdapter.SpringJavaMail(javaMailSender))
            if (props.sendToConsole) add(MailSenderAdapter.Console(javaMailSender))
        })
    }

    private fun createTemplateRenderer(
        rendererProvider: ObjectProvider<MailTemplateRenderer>,
    ): MailTemplateRenderer {
        val availableRenderers = rendererProvider.associateBy { it.identifier }
        log.info("MailTemplateRenderer instances available: ${availableRenderers.keys}")

        val delegates = props.templateRenderers.map { identifier ->
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
