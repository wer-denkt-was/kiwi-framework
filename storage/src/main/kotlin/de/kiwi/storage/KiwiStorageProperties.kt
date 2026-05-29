package de.kiwi.storage

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

@ConfigurationProperties("kiwi.storage")
data class KiwiStorageProperties(
    var provider: StorageProvider = StorageProvider.S3,
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
    )

    data class EntityOptions(
        var onDeleteRemoveFromStorage: Boolean = true,
    )
}
