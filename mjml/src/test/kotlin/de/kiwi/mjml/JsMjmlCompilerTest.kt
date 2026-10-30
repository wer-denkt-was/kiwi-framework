package de.kiwi.mjml

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import java.util.concurrent.Executors

@Timeout(60)
class JsMjmlCompilerTest {
    companion object {
        private lateinit var jsResource: Resource
        private lateinit var mjmlCompiler: JsMjmlCompiler

        @JvmStatic
        @BeforeAll
        fun setupClass() {
            val classLoader = JsMjmlCompilerTest::class.java.classLoader
            val resources = DefaultResourceLoader(classLoader)
            jsResource = resources.getResource(mjmlJsLocation)
            mjmlCompiler = JsMjmlCompiler(jsResource, fonts = emptyMap())
            mjmlCompiler.afterPropertiesSet()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            mjmlCompiler.close()
        }
    }

    @Test
    fun compile() {
        val html = mjmlCompiler.compile(mjmlText)
        println("MJML ->\n$mjmlText")
        println("------------------")
        println("HTML ->\n$html")

        // HTML output must be document-shaped and contain expected fragments from template
        assertThat(
            html,
            stringContainsInOrder(
                "<!doctype html>",
                "<html",
                "Hello World!",
                "</html>",
            )
        )

        // HTML output must *not* contain a reference to Google Fonts without explicit configuration properties
        assertThat(html, not(containsStringIgnoringCase("https://fonts.googleapis.com")))
    }

    @Test
    fun compileWithInlineStyles() {
        // Inlining styles requires cheerio, which is bundled into the MJML compiler script
        val html = mjmlCompiler.compile(mjmlInlineStyleText)

        assertThat(html, containsString("Hello Styles!"))
        assertThat(html, matchesPattern("(?s).*<div[^>]*class=\"greeting\"[^>]*style=\"[^\"]*color: ?#ff0000.*"))
    }

    @Test
    fun compileConcurrently() {
        JsMjmlCompiler(jsResource, fonts = emptyMap(), poolSize = 2).use { pooledCompiler ->
            pooledCompiler.afterPropertiesSet()

            // Requests are submitted while the workers are still loading the MJML compiler, and must stay queued
            val htmls = Executors.newFixedThreadPool(4).use { executor ->
                List(8) { executor.submit<String> { pooledCompiler.compile(mjmlText) } }.map { it.get() }
            }

            htmls.forEach { html -> assertThat(html, containsString("Hello World!")) }
        }
    }

    @Test
    fun compileFailsIfCompilerCannotBeLoaded() {
        val brokenResource = ByteArrayResource("throw new Error('Broken MJML compiler')".toByteArray())
        JsMjmlCompiler(brokenResource, fonts = emptyMap(), poolSize = 2).use { brokenCompiler ->
            brokenCompiler.afterPropertiesSet()

            // Queued requests must fail, instead of waiting for a worker forever
            assertThrows<MjmlException> { brokenCompiler.compile(mjmlText) }
            assertThrows<MjmlException> { brokenCompiler.compile(mjmlText) }
        }
    }

    @Test
    fun compileFailsAfterClose() {
        val closedCompiler = JsMjmlCompiler(jsResource, fonts = emptyMap())
        closedCompiler.afterPropertiesSet()
        closedCompiler.close()

        assertThrows<MjmlException> { closedCompiler.compile(mjmlText) }
    }

    @Test
    fun compileInvalid() {
        val exception = assertThrows<MjmlException> {
            mjmlCompiler.compile("This is not valid MJML")
        }

        println(exception)
    }
}

// language=xml
private const val mjmlText = """
<mjml>
  <mj-body>
    <mj-section>
      <mj-column>
        <mj-text>Hello World!</mj-text>
      </mj-column>
    </mj-section>
  </mj-body>
</mjml>
"""

// language=xml
private const val mjmlInlineStyleText = """
<mjml>
  <mj-head>
    <mj-style inline="inline">
      .greeting { color: #ff0000; }
    </mj-style>
  </mj-head>
  <mj-body>
    <mj-section>
      <mj-column>
        <mj-text>
          <div class="greeting">Hello Styles!</div>
        </mj-text>
      </mj-column>
    </mj-section>
  </mj-body>
</mjml>
"""
