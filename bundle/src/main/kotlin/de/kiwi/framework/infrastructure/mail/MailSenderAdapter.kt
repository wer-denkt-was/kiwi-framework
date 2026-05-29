package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.util.slf4j
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessagePreparator
import java.io.ByteArrayOutputStream

private val log = slf4j<MailSenderAdapter>()

interface MailSenderAdapter {
    fun sendMessage(prep: MimeMessagePreparator)

    class Delegate(private val delegates: Iterable<MailSenderAdapter>) : MailSenderAdapter {
        override fun sendMessage(prep: MimeMessagePreparator) = delegates.forEach { it.sendMessage(prep) }
    }

    class SpringJavaMail(private val sender: JavaMailSender) : MailSenderAdapter {
        override fun sendMessage(prep: MimeMessagePreparator) = sender.send(prep)
    }

    class Console(private val sender: JavaMailSender) : MailSenderAdapter {
        override fun sendMessage(prep: MimeMessagePreparator) {
            val output = ByteArrayOutputStream()
            val message = sender.createMimeMessage()
            prep.prepare(message)
            message.writeTo(output)
            val content = output.toString(Charsets.UTF_8)
            log.info("Send E-Mail:\n{}", content)
        }
    }
}
