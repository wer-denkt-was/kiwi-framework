package de.kiwi.framework.infrastructure.mail.template

import de.kiwi.framework.infrastructure.mail.MailTemplateRenderer
import com.samskivert.mustache.Mustache.Compiler as MustacheCompiler
import com.samskivert.mustache.Template as MustacheTemplate

class MustacheMailTemplateRenderer(
    private val mustacheCompiler: MustacheCompiler,
    cacheSize: Int,
) : MailTemplateRenderer {
    private val templateCache = LruCache(cacheSize)

    override val identifier = "mustache"

    override fun renderTemplate(content: String, properties: Any): String {
        val template = synchronized(templateCache) {
            templateCache.computeIfAbsent(content, ::compileTemplate)
        }
        return template.execute(properties)
    }

    private fun compileTemplate(content: String): MustacheTemplate {
        return mustacheCompiler.compile(content)
    }
}

private class LruCache(private val maxEntries: Int) : LinkedHashMap<String, MustacheTemplate>(maxEntries) {
    override fun removeEldestEntry(eldest: Map.Entry<String, MustacheTemplate>): Boolean {
        return size > maxEntries
    }
}
