package de.kiwi.api.application.service

import org.springframework.core.io.Resource

/**
 * Service for rendering and sending e-mail templates to specific recipients.
 */
interface MailSenderService {

    fun sendEmail(recipients: List<Recipient>, email: SendEmail)

    data class Recipient(
        val address: String,
        val type: Type,
    ) {
        enum class Type { To, Cc, Bcc }
    }

    data class SendEmail(
        val subject: String,
        val template: String,
        val properties: Any,
        val attachments: List<Attachment> = emptyList(),
        val headers: Map<String, String> = emptyMap(),
    )

    data class Attachment(
        val filename: String,
        val inline: Boolean,
        val resource: Resource,
        val contentType: String?,
    )

    companion object {
        fun to(address: String) = Recipient(address = address, type = Recipient.Type.To)
        fun cc(address: String) = Recipient(address = address, type = Recipient.Type.Cc)
        fun bcc(address: String) = Recipient(address = address, type = Recipient.Type.Bcc)
    }
}

interface SendMailBuilder {
    var subject: String
    fun recipient(vararg recipient: MailSenderService.Recipient)
    fun recipients(build: RecipientsBuilder.() -> Unit)

    fun attachment(vararg attachment: MailSenderService.Attachment)
    fun attachments(build: AttachmentsBuilder.() -> Unit)

    fun setHeader(name: String, value: String)

    interface RecipientsBuilder {
        fun to(vararg address: String)
        fun cc(vararg address: String)
        fun bcc(vararg address: String)
    }

    interface AttachmentsBuilder {
        fun file(filename: String, resource: Resource)
        fun file(filename: String, contentType: String, resource: Resource)
        fun inline(filename: String, resource: Resource)
        fun inline(filename: String, contentType: String, resource: Resource)
    }
}

fun MailSenderService.sendEmail(template: String, properties: Any, build: SendMailBuilder.() -> Unit) {
    val recipients = ArrayList<MailSenderService.Recipient>()
    val attachments = ArrayList<MailSenderService.Attachment>()

    fun addRecipient(recipient: MailSenderService.Recipient) = recipients.add(recipient).let { }
    fun addAttachment(attachment: MailSenderService.Attachment) = attachments.add(attachment).let { }

    val recipientsBuilder = object : SendMailBuilder.RecipientsBuilder {
        override fun to(vararg address: String) = address.forEach { addRecipient(MailSenderService.to(it)) }
        override fun cc(vararg address: String) = address.forEach { addRecipient(MailSenderService.cc(it)) }
        override fun bcc(vararg address: String) = address.forEach { addRecipient(MailSenderService.bcc(it)) }
    }

    val attachmentsBuilder = object : SendMailBuilder.AttachmentsBuilder {
        override fun file(filename: String, resource: Resource) =
            addAttachment(MailSenderService.Attachment(filename, false, resource, null))

        override fun file(filename: String, contentType: String, resource: Resource) =
            addAttachment(MailSenderService.Attachment(filename, false, resource, contentType))

        override fun inline(filename: String, resource: Resource) =
            addAttachment(MailSenderService.Attachment(filename, true, resource, null))

        override fun inline(filename: String, contentType: String, resource: Resource) =
            addAttachment(MailSenderService.Attachment(filename, true, resource, contentType))
    }

    val headers = LinkedHashMap<String, String>()

    val sendMailBuilder = object : SendMailBuilder {
        override lateinit var subject: String
        override fun recipient(vararg recipient: MailSenderService.Recipient) = recipient.forEach(recipients::add)
        override fun recipients(build: SendMailBuilder.RecipientsBuilder.() -> Unit) = recipientsBuilder.build()
        override fun attachment(vararg attachment: MailSenderService.Attachment) = attachment.forEach(attachments::add)
        override fun attachments(build: SendMailBuilder.AttachmentsBuilder.() -> Unit) = attachmentsBuilder.build()
        override fun setHeader(name: String, value: String) = headers.put(name, value).let {}
    }

    sendMailBuilder.build()
    val subject = sendMailBuilder.subject
    val sendEmail = MailSenderService.SendEmail(subject, template, properties, attachments, headers)
    sendEmail(recipients, sendEmail)
}
