package de.kiwi.storage.domain

import de.kiwi.api.schema.ColumnType
import java.net.URI
import java.time.Duration

/**
 * [ColumnType] implementation similar to [UriType][de.kiwi.api.schema.UriType],
 * which additionally uses a [SignedFileUriProvider] instance to sign the given [URI] on [format].
 * Before this type can be used, a [SignedFileUriProvider] must be registered as [provider][Companion.provider].
 * @see de.kiwi.storage.KiwiStorageSignedFileUriTypeConfiguration.registerSignedFileUriProvider
 */
class SignedFileUriType(private val duration: Duration) : ColumnType<URI, String> {
    companion object {
        /** Static provider used for signing file URIs */
        lateinit var provider: SignedFileUriProvider

        private fun requireProvider(): SignedFileUriProvider {
            check(::provider.isInitialized) { "SignedFileUriType.provider has not been initialized" }
            return provider
        }
    }

    override val valueType = String::class.java

    override fun parse(value: String): URI {
        return URI.create(value)
    }

    override fun format(value: URI): String {
        val provider = requireProvider()
        val signedFile = provider.signFileUri(value, duration)
        return signedFile.toString()
    }
}

/**
 * Provider interface for signing [FileEntity] storage URIs.
 * @see de.kiwi.api.application.service.FileStorageService.signFileUri
 * @see de.kiwi.storage.KiwiStorageSignedFileUriTypeConfiguration.registerSignedFileUriProvider
 */
fun interface SignedFileUriProvider {
    fun signFileUri(fileUri: URI, duration: Duration): URI
}
