package de.kiwi.storage.infrastructure.s3

import aws.sdk.kotlin.services.s3.endpoints.DefaultS3EndpointProvider
import aws.sdk.kotlin.services.s3.endpoints.S3EndpointParameters
import de.kiwi.storage.KiwiStorageProperties
import de.kiwi.storage.infrastructure.s3.S3EndpointProvider.S3ObjectInfo
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI

/**
 * Object URIs must match the hosts the S3 client sends requests to (A-21).
 */
class S3EndpointProviderTest {
    private val aws = KiwiStorageProperties.S3Properties(region = "eu-central-1")
    private val other = KiwiStorageProperties.S3Properties(region = "fsn1", hostname = "fsn1.your-objectstorage.com")
    private val info = S3ObjectInfo(region = "eu-central-1", bucket = "my-bucket", key = "path/file.txt")

    @Test
    fun awsSubdomain() {
        val provider = SubdomainS3EndpointProvider(aws)
        val uri = provider.objectEndpoint(info)
        assertEquals(URI("https://my-bucket.s3.eu-central-1.amazonaws.com/path/file.txt"), uri)
        assertEquals(info, provider.objectUriInfo(uri))
        assertEquals(uri.host, sdkRequestHost(aws))

        assertThrows<InvalidS3UriException> { provider.objectUriInfo(URI("https://my-bucket.amazonaws.com/file.txt")) }
    }

    @Test
    fun otherProviderSubdomain() {
        val provider = SubdomainS3EndpointProvider(other)
        val uri = provider.objectEndpoint(info.copy(region = "fsn1"))
        assertEquals(URI("https://my-bucket.fsn1.your-objectstorage.com/path/file.txt"), uri)
        assertEquals(info.copy(region = "fsn1"), provider.objectUriInfo(uri))
        assertEquals(uri.host, sdkRequestHost(other))

        // Object URIs created before Kiwi 1.5 can still be parsed
        val legacyUri = URI("https://my-bucket.s3.fsn1.fsn1.your-objectstorage.com/path/file.txt")
        assertEquals(info.copy(region = "fsn1"), provider.objectUriInfo(legacyUri))
    }

    @Test
    fun otherProviderPathPrefix() {
        val props = other.copy(objectUriType = KiwiStorageProperties.S3Properties.ObjectUriType.PathPrefix)
        val provider = PathPrefixS3EndpointProvider(props)
        val uri = provider.objectEndpoint(info.copy(region = "fsn1"))
        assertEquals(URI("https://fsn1.your-objectstorage.com/my-bucket/path/file.txt"), uri)
        assertEquals(info.copy(region = "fsn1"), provider.objectUriInfo(uri))
        assertEquals(uri.host, sdkRequestHost(props, forcePathStyle = true))
    }

    private fun sdkRequestHost(props: KiwiStorageProperties.S3Properties, forcePathStyle: Boolean = false) =
        runBlocking {
            val endpoint = DefaultS3EndpointProvider().resolveEndpoint(
                S3EndpointParameters {
                    bucket = "my-bucket"
                    region = props.region
                    endpoint = props.clientEndpointUrl()
                    this.forcePathStyle = forcePathStyle
                },
            )
            endpoint.uri.host.toString()
        }
}
