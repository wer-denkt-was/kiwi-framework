package de.kiwi.framework.infrastructure.mail

import com.samskivert.mustache.Mustache
import de.kiwi.api.application.service.MailSenderService
import de.kiwi.framework.KiwiFrameworkProperties
import de.kiwi.framework.infrastructure.mail.template.MustacheMailTemplateRenderer
import de.kiwi.framework.infrastructure.mail.template.ResourceMailTemplateProvider
import de.kiwi.framework.mjml.MjmlConfiguration
import de.kiwi.framework.mjml.MjmlMailProperties
import de.kiwi.framework.mjml.MjmlMailTemplateRenderer
import jakarta.mail.Address
import jakarta.mail.Message.RecipientType
import jakarta.mail.Multipart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.mockingDetails
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.mail.javamail.MimeMessagePreparator
import org.springframework.security.util.InMemoryResource
import java.io.ByteArrayOutputStream

class SpringMailSenderServiceTest {
    private val classLoader = SpringMailSenderServiceTest::class.java.classLoader
    private val resources = DefaultResourceLoader(classLoader)
    private val provider = ResourceMailTemplateProvider(resources, Charsets.UTF_8)

    private lateinit var sender: TestMailSenderAdapter
    private lateinit var service: SpringMailSenderService

    @BeforeEach
    fun setUp() {
        val mjmlProps = MjmlMailProperties(fonts = emptyMap())
        val mjmlConfig = MjmlConfiguration(props = mjmlProps, resources = resources)
        val mjmlCompiler = mjmlConfig.mjmlCompiler()
        if (mjmlCompiler is InitializingBean) {
            mjmlCompiler.afterPropertiesSet()
        }

        val mustacheCompiler = Mustache.compiler()

        val renderer = MailTemplateRenderer.pipeline(
            MustacheMailTemplateRenderer(mustacheCompiler, cacheSize = 0),
            MjmlMailTemplateRenderer(mjmlCompiler),
        )

        sender = TestMailSenderAdapter()
        service = SpringMailSenderService(
            sender = sender,
            provider = provider,
            renderer = renderer,
            config = KiwiFrameworkProperties.MailProperties(
                fromAddress = "test@localhost",
                htmlMessage = true,
                sendToConsole = true,
            ),
        )
    }

    @Test
    fun sendEmail() {
        val recipients = listOf(
            MailSenderService.to("recipient@localhost"),
            MailSenderService.cc("another@localhost"),
        )
        val email = MailSenderService.SendEmail(
            subject = "Dear Slim Shady",
            template = "classpath:mail/testMail.mjml",
            properties = mapOf(
                "who" to "Slim",
                "what" to "my cell, my pager, and my home phone",
                "when" to "back in autumn",
                "signedBy" to "Stan",
            ),
            attachments = listOf(
                MailSenderService.Attachment(
                    filename = "fish.txt",
                    contentType = "text/plain",
                    resource = InMemoryResource("<°)))><|"),
                    inline = false,
                )
            ),
        )

        service.sendEmail(recipients, email)
        assertThat(sender.sent, hasSize(1))

        val (messageData) = sender.sent
        val (messageMock) = sender.mocks
        println(messageData.printContent())
        println(mockingDetails(messageMock).printInvocations())

        assertThat(messageData.from, equalTo("test@localhost"))
        assertThat(messageData.to, containsInAnyOrder("recipient@localhost"))
        assertThat(messageData.cc, containsInAnyOrder("another@localhost"))
        assertThat(messageData.bcc, hasSize(0))
        assertThat(messageData.subject, equalTo("Dear Slim Shady"))
        assertThat(
            messageData.printContent(),
            stringContainsInOrder(
                "------",
                "<!doctype html>",
                "<html", // ...>
                "<head", // ...>
                "</head>",
                "<body", // ...>
                "Dear Slim, I wrote you, but you still ain't callin'",
                "I sent two letters back in autumn, you must not've got 'em.",
                "Anyways, I hope you get this, man, hit me back.",
                "Just to chat, truly yours, your biggest fan, this is Stan.",
                "</body>",
                "</html>",
                "------",
                "Content-Disposition: attachment",
                "filename=fish.txt",
                "<°)))><|",
                "------",
            )
        )
    }
}

private class MessageData {
    var from: String = ""
    val to = ArrayList<String>()
    val cc = ArrayList<String>()
    val bcc = ArrayList<String>()
    var subject: String = ""
    var multipart: MimeMultipart? = null

    fun printContent(): String? = multipart?.let {
        val output = ByteArrayOutputStream()
        it.writeTo(output)
        output.toString(Charsets.UTF_8)
    }
}

private class TestMailSenderAdapter : MailSenderAdapter {
    val mocks = ArrayList<MimeMessage>()
    val sent = ArrayList<MessageData>()

    override fun sendMessage(prep: MimeMessagePreparator) {
        val data = MessageData()
        val mockMessage = mock<MimeMessage> {
            on { setFrom(any<Address>()) } doAnswer {
                data.from = it.getArgument<Address>(0).toString()
            }
            on { addRecipient(any<RecipientType>(), any<Address>()) } doAnswer {
                val address = it.getArgument<Address>(1).toString()
                when (it.getArgument<RecipientType>(0).toString()) {
                    RecipientType.TO.toString() -> data.to.add(address)
                    RecipientType.CC.toString() -> data.cc.add(address)
                    RecipientType.BCC.toString() -> data.bcc.add(address)
                }
                Unit
            }
            on { setSubject(any<String>()) } doAnswer {
                data.subject = it.getArgument(0)
            }
            on { setContent(any<Multipart>()) } doAnswer {
                data.multipart = it.getArgument<MimeMultipart>(0)
            }
        }

        prep.prepare(mockMessage)
        mocks.add(mockMessage)
        sent.add(data)
    }
}
