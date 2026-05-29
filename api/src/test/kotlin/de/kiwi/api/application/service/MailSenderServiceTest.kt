package de.kiwi.api.application.service

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

import org.springframework.core.io.ByteArrayResource

class MailSenderServiceTest {

    @Test
    fun sendEmailBuilder() {
        val service = TestMailSenderService()

        val inlineResource1 = TestResource()
        val inlineResource2 = TestResource()
        val fileResource1 = TestResource()
        val fileResource2 = TestResource()

        service.sendEmail("classpath:test.mjml", TestProperties) {
            subject = "Set the subject"
            recipients {
                to("to@test")
                cc("cc@test")
                bcc("bcc@test")
            }
            attachments {
                inline("inline-1.txt", inlineResource1)
                inline("inline-2.txt", "text/plain", inlineResource2)
                file("file-1.json", fileResource1)
                file("file-2.json", "application/json", fileResource2)
            }
        }

        println(service.sent)

        assertThat(
            service.sent,
            contains(
                allOf(
                    hasProperty(
                        "recipients",
                        containsInAnyOrder<MailSenderService.Recipient>(
                            allOf(
                                hasProperty("address", equalTo("to@test")),
                                hasProperty("type", sameInstance(MailSenderService.Recipient.Type.To))
                            ),
                            allOf(
                                hasProperty("address", equalTo("cc@test")),
                                hasProperty("type", sameInstance(MailSenderService.Recipient.Type.Cc))
                            ),
                            allOf(
                                hasProperty("address", equalTo("bcc@test")),
                                hasProperty("type", sameInstance(MailSenderService.Recipient.Type.Bcc))
                            )
                        )
                    ),
                    hasProperty(
                        "email",
                        allOf<MailSenderService.SendEmail>(
                            hasProperty("subject", equalTo("Set the subject")),
                            hasProperty("template", equalTo("classpath:test.mjml")),
                            hasProperty("properties", sameInstance(TestProperties)),
                            hasProperty(
                                "attachments",
                                containsInAnyOrder<MailSenderService.Attachment>(
                                    allOf(
                                        hasProperty("filename", equalTo("inline-1.txt")),
                                        hasProperty("inline", equalTo(true)),
                                        hasProperty("resource", sameInstance(inlineResource1)),
                                        hasProperty("contentType", nullValue()),
                                    ),
                                    allOf(
                                        hasProperty("filename", equalTo("inline-2.txt")),
                                        hasProperty("inline", equalTo(true)),
                                        hasProperty("resource", sameInstance(inlineResource2)),
                                        hasProperty("contentType", equalTo("text/plain")),
                                    ),
                                    allOf(
                                        hasProperty("filename", equalTo("file-1.json")),
                                        hasProperty("inline", equalTo(false)),
                                        hasProperty("resource", sameInstance(fileResource1)),
                                        hasProperty("contentType", nullValue()),
                                    ),
                                    allOf(
                                        hasProperty("filename", equalTo("file-2.json")),
                                        hasProperty("inline", equalTo(false)),
                                        hasProperty("resource", sameInstance(fileResource2)),
                                        hasProperty("contentType", equalTo("application/json")),
                                    ),
                                )
                            )
                        )
                    )
                )
            )
        )
    }
}

private object TestProperties

private class TestResource : ByteArrayResource(ByteArray(0), "test")

private class TestMailSenderService : MailSenderService {
    val sent = ArrayList<MailSent>()

    override fun sendEmail(recipients: List<MailSenderService.Recipient>, email: MailSenderService.SendEmail) {
        sent.add(MailSent(recipients, email))
    }
}

data class MailSent(
    val recipients: List<MailSenderService.Recipient>,
    val email: MailSenderService.SendEmail,
)
