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

/** `true` if the configured S3 hostname is the one of AWS, e.g. `amazonaws.com` */
internal val KiwiStorageProperties.S3Properties.isAwsHostname: Boolean
    get() = hostname.equals("amazonaws.com", ignoreCase = true) || hostname.equals("amazonaws.com.cn", ignoreCase = true)

/**
 * Endpoint URL of the S3 client: `null` for AWS, which resolves the regional endpoint `s3.{region}.amazonaws.com`
 * itself, otherwise `{scheme}://{hostname}`.
 */
internal fun KiwiStorageProperties.S3Properties.clientEndpointUrl(): String? =
    if (isAwsHostname) null else "$scheme://$hostname"

/**
 * Object URIs with the bucket in the hostname, matching the hosts the S3 client sends requests to:
 * `{bucket}.s3.{region}.amazonaws.com` for AWS, and `{bucket}.{hostname}` for other S3 providers.
 */
class SubdomainS3EndpointProvider(private val props: KiwiStorageProperties.S3Properties) : S3EndpointProvider {
    private val hostnameEscape = Regex.escape(props.hostname)

    // "{bucket}.s3.{region}.{hostname}", the format of AWS (and of non-AWS object URIs before Kiwi 1.5)
    private val regionalBucketHostnameRegex = """^(?<bucket>.*)\.s3\.(?<region>.*)\.$hostnameEscape$"""
        .toRegex(RegexOption.IGNORE_CASE)

    // "{bucket}.{hostname}", the format of other S3 providers
    private val bucketHostnameRegex = """^(?<bucket>.*)\.$hostnameEscape$"""
        .toRegex(RegexOption.IGNORE_CASE)

    override fun objectEndpoint(info: S3EndpointProvider.S3ObjectInfo): URI {
        val (region, bucket, key) = info
        val host = if (props.isAwsHostname) "$bucket.s3.$region.${props.hostname}" else "$bucket.${props.hostname}"
        val path = "/$key"
        return URI(props.scheme, host, path, null, null)
    }

    override fun objectUriInfo(objectUri: URI): S3EndpointProvider.S3ObjectInfo {
        val key = objectUri.path.trimStart('/')
        val host = objectUri.host ?: throw InvalidS3UriException(objectUri.toString())

        regionalBucketHostnameRegex.matchEntire(host)?.let { match ->
            return S3EndpointProvider.S3ObjectInfo(
                region = match.groups["region"]!!.value,
                bucket = match.groups["bucket"]!!.value,
                key = key,
            )
        }

        val match = bucketHostnameRegex.matchEntire(host)
            ?.takeUnless { props.isAwsHostname }
            ?: throw InvalidS3UriException(objectUri.toString())
        return S3EndpointProvider.S3ObjectInfo(
            region = props.region,
            bucket = match.groups["bucket"]!!.value,
            key = key,
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
