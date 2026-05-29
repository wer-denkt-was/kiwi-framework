package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.application.service.MailSenderService.Recipient
import de.kiwi.framework.KiwiFrameworkProperties.MailProperties
import org.springframework.mail.javamail.MimeMessageHelper

class SpringMailSenderService(
    private val sender: MailSenderAdapter,
    private val provider: MailTemplateProvider,
    private val renderer: MailTemplateRenderer,
    private val config: MailProperties,
) : MailSenderService {
    override fun sendEmail(recipients: List<Recipient>, email: MailSenderService.SendEmail) {
        val templateContent = provider.loadTemplate(email.template)
        val messageContent = renderer.renderTemplate(templateContent, email.properties)

        sender.sendMessage { message ->
            val helper = MimeMessageHelper(message, /* multipart = */ true)

            helper.setFrom(config.fromAddress)
            recipients.forEach {
                when (it.type) {
                    Recipient.Type.To -> helper.addTo(it.address)
                    Recipient.Type.Cc -> helper.addCc(it.address)
                    Recipient.Type.Bcc -> helper.addBcc(it.address)
                }
            }

            helper.setSubject(email.subject)
            helper.setText(messageContent, config.htmlMessage)

            email.headers.forEach { (name, value) ->
                message.setHeader(name, value)
            }

            email.attachments.forEach { attachment ->
                val (filename, inline, resource) = attachment
                val contentType = attachment.contentType?.takeIf { it.isNotEmpty() }
                if (inline) {
                    if (contentType == null) helper.addInline(filename, resource)
                    else helper.addInline(filename, resource, contentType)
                } else {
                    if (contentType == null) helper.addAttachment(filename, resource)
                    else helper.addAttachment(filename, resource, contentType)
                }
            }
        }
    }
}
