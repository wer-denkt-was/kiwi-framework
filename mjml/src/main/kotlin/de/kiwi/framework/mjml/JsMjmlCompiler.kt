package de.kiwi.framework.mjml

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.io.Resource
import java.io.Closeable
import kotlin.time.measureTime
import org.graalvm.polyglot.Context as JsContext
import org.graalvm.polyglot.PolyglotException as JsException
import org.graalvm.polyglot.Value as JsValue
import org.graalvm.polyglot.proxy.ProxyObject as ProxyJsObject

private val log = LoggerFactory.getLogger(JsMjmlCompiler::class.java)!!

internal class JsMjmlCompiler(
    private val jsResource: Resource,
    private val fonts: Map<String, String>,
) : MjmlCompiler, InitializingBean, Closeable {
    private lateinit var context: JsContext
    private lateinit var mjml2html: JsValue

    override fun afterPropertiesSet() {
        context = JsContext
            .newBuilder("js")
            .option("engine.WarnInterpreterOnly", "false")
            .build()

        log.info("Loading MJML compiler from $jsResource ...")
        val loadTime = measureTime {
            context.eval("js", "var window = globalThis;")
            context.eval("js", jsResource.getContentAsString(Charsets.UTF_8))
            mjml2html = context.eval("js", "MJML.mjml2html")
        }
        log.info("Finished loading MJML compiler in $loadTime")
    }

    override fun close() {
        context.close()
    }

    override fun compile(mjml: String): String {
        try {
            val fontsProxy = ProxyJsObject.fromMap(fonts)
            val optionsProxy = ProxyJsObject.fromMap(mapOf("fonts" to fontsProxy))
            val result = mjml2html.execute(mjml, optionsProxy)
            return result.getMember("html").asString()
        } catch (ex: JsException) {
            throw MjmlException(ex.message!!)
        }
    }
}
