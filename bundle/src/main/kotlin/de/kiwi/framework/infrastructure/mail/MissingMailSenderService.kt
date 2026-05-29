package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.util.slf4j

private val log = slf4j<MailSenderService>()

object MissingMailSenderService : MailSenderService {
    override fun sendEmail(recipients: List<MailSenderService.Recipient>, email: MailSenderService.SendEmail) {
        log.warn("No MailSenderService available: To $recipients send $email")
    }
}
