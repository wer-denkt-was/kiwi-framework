package de.kiwi.framework.infrastructure.mail

import de.kiwi.api.application.service.MailSenderService
import de.kiwi.api.util.slf4j
import de.kiwi.framework.KiwiFrameworkProperties
import org.springframework.beans.factory.InitializingBean

private val log = slf4j<DisabledMailSenderService>()

class DisabledMailSenderService(
    private val props: KiwiFrameworkProperties.MailProperties,
) : MailSenderService, InitializingBean {
    override fun afterPropertiesSet() {
        showWarning { "E-Mail sending is disabled: kiwi.mail.enabled = false" }
    }

    override fun sendEmail(recipients: List<MailSenderService.Recipient>, email: MailSenderService.SendEmail) {
        showWarning { "E-Mail sending is disabled: Attempted to send $email to $recipients" }
    }

    private fun showWarning(getMessage: () -> String) {
        if (props.disabledWarning) {
            log.warn(getMessage())
        }
    }
}
