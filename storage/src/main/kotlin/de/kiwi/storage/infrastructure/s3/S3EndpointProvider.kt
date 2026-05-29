package de.kiwi.storage.infrastructure.s3

import de.kiwi.api.domain.DomainException
import de.kiwi.storage.KiwiStorageProperties
import java.net.URI

interface S3EndpointProvider {
    fun objectEndpoint(info: S3ObjectInfo): URI

    fun objectUriInfo(objectUri: URI): S3ObjectInfo

    data class S3ObjectInfo(
        val region: String,
        val bucket: String,
        val key: String,
    )
}

class SubdomainS3EndpointProvider(private val props: KiwiStorageProperties.S3Properties) : S3EndpointProvider {
    private val hostnameEscape = props.hostname
        .replace("\\", "\\\\")
        .replace(".", "\\.")
        .replace("*", "\\*")

    private val bucketHostnameRegex = """^(?<bucket>.*)\.s3\.(?<region>.*)\.$hostnameEscape$"""
        .toRegex(RegexOption.IGNORE_CASE)

    override fun objectEndpoint(info: S3EndpointProvider.S3ObjectInfo): URI {
        val (region, bucket, key) = info
        val host = "$bucket.s3.$region.${props.hostname}"
        val path = "/$key"
        return URI(props.scheme, host, path, null, null)
    }

    override fun objectUriInfo(objectUri: URI): S3EndpointProvider.S3ObjectInfo {
        val key = objectUri.path.trimStart('/')
        val match = objectUri.host
            ?.let { bucketHostnameRegex.matchEntire(it) }
            ?: throw InvalidS3UriException(objectUri.toString())

        val bucket = match.groups["bucket"]!!.value
        val region = match.groups["region"]!!.value
        return S3EndpointProvider.S3ObjectInfo(
            region = region,
            bucket = bucket,
            key = key
        )
    }
}

class PathPrefixS3EndpointProvider(
    private val props: KiwiStorageProperties.S3Properties,
) : S3EndpointProvider {
    // Path is "/<bucket>/<...objectKey>"
    private val pathRegex = """^/(?<bucket>.*?)/(?<key>.*)$""".toRegex()

    override fun objectEndpoint(info: S3EndpointProvider.S3ObjectInfo): URI {
        val path = "/${info.bucket}/${info.key}"
        return URI(props.scheme, props.hostname, path, null, null)
    }

    override fun objectUriInfo(objectUri: URI): S3EndpointProvider.S3ObjectInfo {
        val match = objectUri.path
            ?.let { pathRegex.matchEntire(it) }
            ?: throw InvalidS3UriException(objectUri.toString())

        val bucket = match.groups["bucket"]!!.value
        val key = match.groups["key"]!!.value
        return S3EndpointProvider.S3ObjectInfo(
            region = props.region,
            bucket = bucket,
            key = key,
        )
    }
}

class InvalidS3UriException(reason: String) : DomainException(
    status = Status.InternalError,
    code = "invalid-s3-uri",
    args = mapOf("reason" to reason)
)
