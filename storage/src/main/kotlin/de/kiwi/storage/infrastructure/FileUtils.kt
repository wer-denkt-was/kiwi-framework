package de.kiwi.storage.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer
import java.util.*
import kotlin.io.path.inputStream

inline fun <T> withTemporaryFile(block: (Path) -> T): T {
    val tmpFile = Files.createTempFile("kiwi-tmp-", "-data")
    return try {
        block(tmpFile)
    } finally {
        Files.deleteIfExists(tmpFile)
    }
}

private val NON_WORD_REGEX = "[^\\w\\s-]".toRegex()
private val WHITESPACE_REGEX = "\\s+".toRegex()

fun String.slugifyFilename() = Normalizer
    .normalize(this, Normalizer.Form.NFD)
    .replace(NON_WORD_REGEX, "")
    .replace('-', ' ')
    .trim()
    .replace(WHITESPACE_REGEX, "_")
    .lowercase()

private const val SHA256_ALGORITHM = "SHA-256"
private const val SHA256_BUFFER_LENGTH = 8 * 1024

fun Path.fileChecksumSha256(): ByteArray = inputStream().use { input ->
    val buffer = ByteArray(SHA256_BUFFER_LENGTH)
    val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
    var read = input.read(buffer, 0, SHA256_BUFFER_LENGTH)
    while (read > -1) {
        digest.update(buffer, 0, read)
        read = input.read(buffer, 0, SHA256_BUFFER_LENGTH)
    }
    return@use digest.digest()
}

fun ByteArray.checksumSha256(): String {
    val digest = MessageDigest.getInstance(SHA256_ALGORITHM)
    val hash = digest.digest(this)
    return hash.formatChecksum()
}

fun ByteArray.formatBase64(): String {
    return Base64.getEncoder().encodeToString(this)
}

fun ByteArray.formatChecksum(): String {
    val builder = StringBuilder(2 * size)
    forEach { builder.append("%02x".format(it)) }
    return builder.toString()
}
