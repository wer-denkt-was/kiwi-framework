package de.kiwi.framework.infrastructure.mail

import org.springframework.core.Ordered

/**
 * Service provider interface for rendering e-mail templates.
 *
 * All [MailTemplateRenderer] beans are combined into a [pipeline], sorted by their [order][getOrder]:
 * The output of a renderer is the input of the next renderer.
 * Kiwi provides a Mustache renderer at [TEMPLATE_ORDER], inserting the e-mail properties into the template,
 * and the optional MJML module provides a renderer at [MARKUP_ORDER], compiling MJML markup into HTML.
 */
interface MailTemplateRenderer : Ordered {
    /** Name of this renderer, used for logging */
    val identifier: String

    fun renderTemplate(content: String, properties: Any): String

    override fun getOrder(): Int = TEMPLATE_ORDER

    companion object {
        /** Order of renderers processing template expressions, e.g. Mustache */
        const val TEMPLATE_ORDER = 0

        /** Order of renderers processing the markup of an already rendered template, e.g. MJML */
        const val MARKUP_ORDER = 1000

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
