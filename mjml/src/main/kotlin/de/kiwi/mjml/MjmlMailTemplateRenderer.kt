package de.kiwi.mjml

import de.kiwi.framework.infrastructure.mail.MailTemplateRenderer

class MjmlMailTemplateRenderer(
    private val mjmlCompiler: MjmlCompiler,
) : MailTemplateRenderer {
    override val identifier = "mjml"

    // MJML compiles the markup of the template, after its template expressions have been rendered
    override fun getOrder() = MailTemplateRenderer.MARKUP_ORDER

    override fun renderTemplate(content: String, properties: Any): String {
        return mjmlCompiler.compile(content)
    }
}
