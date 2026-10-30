package de.kiwi.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration properties (prefix `kiwi.storage`). Properties are `var` to be included in the configuration
 * metadata, see `KiwiFrameworkProperties` of the kiwi-framework module.
 */
@ConfigurationProperties("kiwi.storage")
data class KiwiStorageProperties(
    /** Storage provider, required unless the application declares its own `FileStorageService` bean */
    var provider: StorageProvider? = null,
    val s3: S3Properties = S3Properties(),
    val local: LocalFileProperties = LocalFileProperties(),
    val entity: EntityOptions = EntityOptions(),
) {
    enum class StorageProvider { None, DataUri, S3, Local }

    data class S3Properties(
        var region: String = "eu-central-1",
        var hostname: String = "amazonaws.com",
        var scheme: String = "https",
        var bucket: String = "",
        var accessKey: String = "",
        var secretKey: String = "",
        var objectUriType: ObjectUriType = ObjectUriType.Subdomain,
        var validateBucket: ValidateBucketMode = ValidateBucketMode.Validate,
        var anonymizeFilename: AnonymizeFilename = AnonymizeFilename.None,
        var publicAccessHostname: String? = null,
        var publicObjectMode: PublicObjectMode = PublicObjectMode.Acl,
        var publicObjectBucketSuffix: String = "-public",
    ) {
        enum class AnonymizeFilename { None, Slugify, Anonymous }
        enum class ValidateBucketMode { None, Validate, AutoCreate }
        enum class ObjectUriType { Subdomain, PathPrefix }
        enum class PublicObjectMode { Acl, Bucket }

        val publicAccessBucket: String
            get() = when (publicObjectMode) {
                PublicObjectMode.Bucket -> (bucket + publicObjectBucketSuffix)
                PublicObjectMode.Acl -> bucket
            }

        val isPublicAclEnabled get() = publicObjectMode == PublicObjectMode.Acl
        val isPublicBucketEnabled get() = publicObjectMode == PublicObjectMode.Bucket
    }

    data class LocalFileProperties(
        var forceScheme: String? = null,
        var fileServerOrigin: URI? = null,
        var parentDirectory: Path = Paths.get("storage"),
        val token: LocalFileTokenProperties = LocalFileTokenProperties(),
    )

    data class LocalFileTokenProperties(
        /**
         * Base64-encoded secret key for signing file access tokens, required for the local file storage provider.
         * Must be at least 256 bits, e.g. generated using the command line:
         * ```sh
         * head -c 32 /dev/random | base64
         * ```
         * All application instances serving the same local file storage must share the same secret key.
         */
        var secretKey: String = "",
    )

    data class EntityOptions(
        var onDeleteRemoveFromStorage: Boolean = true,
    )
}
