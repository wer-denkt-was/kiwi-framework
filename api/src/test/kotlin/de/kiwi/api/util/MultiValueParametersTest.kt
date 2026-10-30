package de.kiwi.api.util

import de.kiwi.api.util.splitMultiValueParameters
import de.kiwi.api.util.splitWithEscape
import org.junit.jupiter.api.Test

import org.junit.jupiter.api.Assertions.*

class MultiValueMapParametersTest {
    @Test
    fun splitMultiValueParameters() {
        val params = mapOf(
            "singleValueNoSplit" to listOf("foo"),
            "singleValueSplit" to listOf("foo,bar"),
            "singleValueEscapedSplit" to listOf("foo\\,bar,baz"),
            "multiValueNoSplit" to listOf("foo", "bar"),
            "multiValueDontSplit" to listOf("foo,bar", "baz"),
        )

        val actual = params.splitMultiValueParameters(split = ',', escape = '\\')

        val expected = mapOf(
            "singleValueNoSplit" to listOf("foo"),
            "singleValueSplit" to listOf("foo", "bar"),
            "singleValueEscapedSplit" to listOf("foo,bar", "baz"),
            "multiValueNoSplit" to listOf("foo", "bar"),
            "multiValueDontSplit" to listOf("foo,bar", "baz"),
        )

        println("expected = $expected")
        println("actual   = $actual")
        assertEquals(expected, actual)
    }

    @Test
    fun splitWithEscape() {
        val input = "one^|uno||three^^^^|four^^^|^cuatro|"
        val actual = input.splitWithEscape(split = '|', escape = '^')
        val expected = listOf("one|uno", "", "three^^", "four^|cuatro", "")

        println("expected = $expected")
        println("actual   = $actual")
        assertEquals(expected, actual)
    }

    @Test
    fun splitWithEscapeKeepsTrailingEscapeInLastToken() {
        assertEquals(listOf("abc\\"), "abc\\".splitWithEscape())
        assertEquals(listOf("foo", "bar\\"), "foo,bar\\".splitWithEscape())
        assertEquals(listOf("foo,bar\\"), "foo\\,bar\\".splitWithEscape())
        assertEquals(listOf("\\"), "\\".splitWithEscape())
        assertEquals(listOf("foo", "\\"), "foo,\\".splitWithEscape())
    }
}
