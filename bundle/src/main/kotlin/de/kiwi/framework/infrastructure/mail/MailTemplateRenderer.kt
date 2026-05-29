package de.kiwi.framework.infrastructure.mail

interface MailTemplateRenderer {
    val identifier: String

    fun renderTemplate(content: String, properties: Any): String

    companion object {
        fun pipeline(vararg renderers: MailTemplateRenderer) = pipeline(renderers.asList())
        fun pipeline(renderers: List<MailTemplateRenderer>): MailTemplateRenderer = Pipeline(renderers)
    }

    private class Pipeline(private val renderers: List<MailTemplateRenderer>) : MailTemplateRenderer {
        override val identifier =
            renderers.joinToString(">>") { it.identifier }

        override fun renderTemplate(content: String, properties: Any) =
            renderers.fold(content) { input, renderer -> renderer.renderTemplate(input, properties) }
    }
}
