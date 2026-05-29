package de.kiwi.framework.infrastructure.mail.template

import de.kiwi.framework.infrastructure.mail.MailTemplateProvider
import org.springframework.core.io.ResourceLoader
import java.nio.charset.Charset

class ResourceMailTemplateProvider(
    private val resources: ResourceLoader,
    private val charset: Charset = Charsets.UTF_8,
) : MailTemplateProvider {
    override fun loadTemplate(template: String): String {
        val resource = resources.getResource(template)
        return resource.getContentAsString(charset)
    }
}
