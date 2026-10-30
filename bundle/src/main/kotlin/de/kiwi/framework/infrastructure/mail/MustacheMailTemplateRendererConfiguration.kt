package de.kiwi.framework.infrastructure.mail

import com.samskivert.mustache.Mustache
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.mail.template.MustacheMailTemplateRenderer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.samskivert.mustache.Mustache"])
class MustacheMailTemplateRendererConfiguration(
    private val props: KiwiFrameworkProperties,
) {
    @Bean
    @ConditionalOnMissingBean
    fun mustacheMailTemplateRenderer(
        compilerProvider: ObjectProvider<Mustache.Compiler>,
    ): MustacheMailTemplateRenderer {
        val compiler = compilerProvider.getIfUnique { Mustache.compiler() }
        return MustacheMailTemplateRenderer(compiler, cacheSize = props.mail.templateCacheSize)
    }
}
