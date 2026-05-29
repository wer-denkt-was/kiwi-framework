package de.kiwi.framework.infrastructure.mail

interface MailTemplateProvider {
    fun loadTemplate(template: String): String
}
