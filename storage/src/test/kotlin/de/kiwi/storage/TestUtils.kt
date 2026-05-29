package de.kiwi.storage

import java.security.MessageDigest

fun sha256(data: String) =
    sha256(data.toByteArray(Charsets.UTF_8))

fun sha256(data: ByteArray) = MessageDigest
    .getInstance("SHA-256")
    .digest(data)
    .joinToString("") { "%02x".format(it) }
