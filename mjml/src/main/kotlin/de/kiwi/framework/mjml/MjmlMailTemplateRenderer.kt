package de.kiwi.framework.mjml

import de.kiwi.framework.infrastructure.mail.MailTemplateRenderer

class MjmlMailTemplateRenderer(
    private val mjmlCompiler: MjmlCompiler,
) : MailTemplateRenderer {
    override val identifier = "mjml"

    override fun renderTemplate(content: String, properties: Any): String {
        return mjmlCompiler.compile(content)
    }
}
