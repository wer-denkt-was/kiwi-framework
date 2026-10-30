package de.kiwi.mjml

interface MjmlCompiler {
    fun compile(mjml: String): String
}

class MjmlException(message: String, cause: Throwable? = null) : Exception(message, cause)
