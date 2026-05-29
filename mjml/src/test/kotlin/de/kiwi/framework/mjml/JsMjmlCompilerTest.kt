package de.kiwi.framework.mjml

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.core.io.DefaultResourceLoader

class JsMjmlCompilerTest {
    companion object {
        private lateinit var mjmlCompiler: JsMjmlCompiler

        @JvmStatic
        @BeforeAll
        fun setupClass() {
            val classLoader = JsMjmlCompilerTest::class.java.classLoader
            val resources = DefaultResourceLoader(classLoader)
            val jsResource = resources.getResource(mjmlJsLocation)
            mjmlCompiler = JsMjmlCompiler(jsResource, fonts = emptyMap())
            mjmlCompiler.afterPropertiesSet()
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
