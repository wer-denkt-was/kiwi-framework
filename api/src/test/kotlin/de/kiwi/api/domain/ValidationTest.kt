package de.kiwi.api.domain

import jakarta.persistence.Column
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import kotlin.reflect.KProperty

class ValidationTest {
    @Test
    fun isValidEmail() {
        assertAll(testEmails.map { (email, expectedValid) ->
            Executable {
                val actual = Validation.validEmail.validate(email)
                assertEquals(expectedValid, actual.isValid, email)
            }
        })
    }

    private val stringShort = "A".repeat(25)
    private val stringLong = "A".repeat(100)
    private val stringVeryLong = "A".repeat(9999)
    private val emailShort = "$stringShort@test.com"
    private val emailLong = "$stringLong@test.com"
    private val emailVeryLong = "$stringVeryLong@test.com"

    @Test
    fun validateNull() = assertValidateColumn(
        value = null,
        validateEmail = false,
        string64 = Validation.invalid("not-null"),
        string255 = Validation.invalid("not-null"),
        text = Validation.invalid("not-null"),
        nullableString64 = Validation.valid(null),
        nullableString255 = Validation.valid(null),
        nullableText = Validation.valid(null),
    )

    @Test
    fun validateNullEmail() = assertValidateColumn(
        value = null,
        validateEmail = true,
        string64 = Validation.invalid("not-null"),
        string255 = Validation.invalid("not-null"),
        text = Validation.invalid("not-null"),
        nullableString64 = Validation.valid(null),
        nullableString255 = Validation.valid(null),
        nullableText = Validation.valid(null),
    )

    @Test
    fun validateStringShort() = assertValidateColumn(
        value = stringShort,
        validateEmail = false,
        string64 = Validation.valid(stringShort),
        string255 = Validation.valid(stringShort),
        text = Validation.valid(stringShort),
        nullableString64 = Validation.valid(stringShort),
        nullableString255 = Validation.valid(stringShort),
        nullableText = Validation.valid(stringShort),
    )

    @Test
    fun validateStringShortValidEmail() = assertValidateColumn(
        value = stringShort,
        validateEmail = true,
        string64 = Validation.invalid("invalid-email"),
        string255 = Validation.invalid("invalid-email"),
        text = Validation.invalid("invalid-email"),
        nullableString64 = Validation.invalid("invalid-email"),
        nullableString255 = Validation.invalid("invalid-email"),
        nullableText = Validation.invalid("invalid-email"),
    )

    @Test
    fun validateStringLong() = assertValidateColumn(
        value = stringLong,
        validateEmail = false,
        string64 = Validation.invalid("too-long"),
        string255 = Validation.valid(stringLong),
        text = Validation.valid(stringLong),
        nullableString64 = Validation.invalid("too-long"),
        nullableString255 = Validation.valid(stringLong),
        nullableText = Validation.valid(stringLong),
    )

    @Test
    fun validateStringVeryLong() = assertValidateColumn(
        value = stringVeryLong,
        validateEmail = false,
        string64 = Validation.invalid("too-long"),
        string255 = Validation.invalid("too-long"),
        text = Validation.valid(stringVeryLong),
        nullableString64 = Validation.invalid("too-long"),
        nullableString255 = Validation.invalid("too-long"),
        nullableText = Validation.valid(stringVeryLong),
    )

    @Test
    fun validateStringLongValidEmail() = assertValidateColumn(
        value = stringLong,
        validateEmail = true,
        string64 = Validation.invalid("too-long"),
        string255 = Validation.invalid("invalid-email"),
        text = Validation.invalid("invalid-email"),
        nullableString64 = Validation.invalid("too-long"),
        nullableString255 = Validation.invalid("invalid-email"),
        nullableText = Validation.invalid("invalid-email"),
    )

    @Test
    fun validateEmailShortValidEmail() = assertValidateColumn(
        value = emailShort,
        validateEmail = true,
        string64 = Validation.valid(emailShort),
        string255 = Validation.valid(emailShort),
        text = Validation.valid(emailShort),
        nullableString64 = Validation.valid(emailShort),
        nullableString255 = Validation.valid(emailShort),
        nullableText = Validation.valid(emailShort),
    )

    @Test
    fun validateEmailLongValidEmail() = assertValidateColumn(
        value = emailLong,
        validateEmail = true,
        string64 = Validation.invalid("too-long"),
        string255 = Validation.valid(emailLong),
        text = Validation.valid(emailLong),
        nullableString64 = Validation.invalid("too-long"),
        nullableString255 = Validation.valid(emailLong),
        nullableText = Validation.valid(emailLong),
    )

    @Test
    fun validateEmailVeryLongValidEmail() = assertValidateColumn(
        value = emailVeryLong,
        validateEmail = true,
        string64 = Validation.invalid("too-long"),
        string255 = Validation.invalid("too-long"),
        text = Validation.valid(emailVeryLong),
        nullableString64 = Validation.invalid("too-long"),
        nullableString255 = Validation.invalid("too-long"),
        nullableText = Validation.valid(emailVeryLong),
    )

    private fun assertValidateColumn(
        value: String?,
        validateEmail: Boolean,
        string64: Validation.Result<String?>,
        string255: Validation.Result<String?>,
        text: Validation.Result<String?>,
        nullableString64: Validation.Result<String?>,
        nullableString255: Validation.Result<String?>,
        nullableText: Validation.Result<String?>,
    ) {
        fun assertValid(expected: Validation.Result<String?>, property: KProperty<String?>) {
            val column = property.validateColumn()
            val validate = if (validateEmail) column and Validation.validEmail.orNull else column
            val result = validate.validate(value)
            assertEquals(expected, result, property.name)
        }

        assertValid(string64, TestProps::string64)
        assertValid(string255, TestProps::string255)
        assertValid(text, TestProps::text)
        assertValid(nullableString64, TestProps::nullableString64)
        assertValid(nullableString255, TestProps::nullableString255)
        assertValid(nullableText, TestProps::nullableText)
    }
}

private object TestProps {

    @Column(length = 64, nullable = false)
    lateinit var string64: String

    @Column(length = 255, nullable = false)
    lateinit var string255: String

    @Column(length = 64, nullable = true)
    var nullableString64: String? = null

    @Column(length = 255, nullable = true)
    var nullableString255: String? = null

    @Column(columnDefinition = "TEXT", nullable = false)
    lateinit var text: String

    @Column(columnDefinition = "TEXT", nullable = true)
    var nullableText: String? = null
}

/**
 * https://gist.github.com/cjaoude/fd9910626629b53c4d25
 */
private val testEmails = mapOf(
    // List of Valid Email Addresses
    """email@example.com""" to true,
    """firstname.lastname@example.com""" to true,
    """email@subdomain.example.com""" to true,
    """firstname+lastname@example.com""" to true,
    """email@123.123.123.123""" to true,
    """email@[123.123.123.123]""" to true,
    """email@example.com""" to true,
    """1234567890@example.com""" to true,
    """email@example-one.com""" to true,
    """_______@example.com""" to true,
    """email@example.name""" to true,
    """email@example.museum""" to true,
    """email@example.co.jp""" to true,
    """firstname-lastname@example.com""" to true,

    // List of Invalid Email Addresses
    """plainaddress""" to false,
    """#@%^%#$@#$@#.com""" to false,
    """@example.com""" to false,
    """Joe Smith <email@example.com>""" to false,
    """email.example.com""" to false,
    """email@example@example.com""" to false,
    """.email@example.com""" to false,
    """email.@example.com""" to false,
    """email..email@example.com""" to false,
    """あいうえお@example.com""" to false,
    """email@example.com (Joe Smith)""" to false,
    """email@example""" to false,
    """email@-example.com""" to false,
)
