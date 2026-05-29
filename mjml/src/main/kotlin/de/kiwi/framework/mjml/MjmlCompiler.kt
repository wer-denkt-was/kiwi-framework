package de.kiwi.framework.mjml

interface MjmlCompiler {
    fun compile(mjml: String): String
}

class MjmlException(message: String) : Exception(message)
