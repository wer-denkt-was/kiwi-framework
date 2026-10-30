package de.kiwi.storage.infrastructure.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.*
import aws.smithy.kotlin.runtime.content.ByteStream
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties.S3Properties
import de.kiwi.storage.KiwiStorageProperties.S3Properties.AnonymizeFilename
import de.kiwi.storage.KiwiStorageProperties.S3Properties.ValidateBucketMode
import de.kiwi.storage.infrastructure.checksumSha256
import de.kiwi.storage.infrastructure.withTemporaryFile
import de.kiwi.storage.sha256
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.matchesPattern
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset
import kotlin.io.path.readText

class S3FileStorageServiceTest {
    private val props = S3Properties(
        region = "test-region",
        bucket = "test-bucket",
        accessKey = "abcdef",
        secretKey = "uvwxyz",
        anonymizeFilename = AnonymizeFilename.None,
    )

    @Test
    fun initializeCreateBucketIfNotExists() {
        val client = mock<S3Client> {
            on { headBucket(any()) } doThrow NoSuchBucket {}
            on { createBucket(any()) } doAnswer { CreateBucketResponse { location = "/test-bucket" } }
        }

        val service = S3FileStorageService(client, props.copy(validateBucket = ValidateBucketMode.AutoCreate))
        assertDoesNotThrow { service.afterPropertiesSet() }

        verifyBlocking(client, times(1)) { headBucket(any()) }
        verifyBlocking(client, times(1)) { createBucket(any()) }
    }

    @Test
    fun initializeCreateBucketAlreadyExists() {
        val client = mock<S3Client> {
            on { headBucket(any()) } doAnswer { HeadBucketResponse {} }
            on { createBucket(any()) } doAnswer { CreateBucketResponse { location = "/test-bucket" } }
        }

        val service = S3FileStorageService(client, props.copy(validateBucket = ValidateBucketMode.AutoCreate))
        assertDoesNotThrow { service.afterPropertiesSet() }

        verifyBlocking(client, times(1)) { headBucket(any()) }
        verifyBlocking(client, times(0)) { createBucket(any()) } // Not invoked!
    }

    @Test
    fun uploadFileData() {
        val client = mock<S3Client> {
            on { putObject(any()) } doAnswer { PutObjectResponse {} }
        }

        val service = S3FileStorageService(client, props)
        val data = """Jeff Bezos' rocket looks like a big phallus"""
        val upload = FileStorageService.FileUpload(
            filename = "amazon.txt",
            contentType = "text/plain",
            resource = { data.toInputStream() },
        )

        val result = service.uploadFileData(upload)

        // Verify that S3 client was invoked
        verifyBlocking(client, times(1)) { putObject(any()) }

        // Verify returned result
        assertThat(
            result.uri.toString(),
            matchesPattern("^https://test-bucket\\.s3\\.test-region\\.amazonaws\\.com/../../.{28}/amazon\\.txt\$")
        )
        assertEquals(data.length.toLong(), result.size.toBytes())
        assertEquals(sha256(data), result.sha256)
    }

    @Test
    fun uploadSiblingFileData() {
        val client = mock<S3Client> {
            on { putObject(any()) } doAnswer { PutObjectResponse {} }
        }

        val service = S3FileStorageService(client, props)
        val firstData = "It's-a me, Mario!"
        val secondData = "Luigi number one!"
        val firstResult = service.uploadFileData(
            FileStorageService.FileUpload(
                filename = "mario.txt",
                contentType = "text/plain",
                resource = { firstData.toInputStream() }
            )
        )
        val secondResult = service.uploadFileData(
            FileStorageService.FileUpload(
                filename = "luigi.txt",
                contentType = "text/plain",
                resource = { secondData.toInputStream() },
                siblingFileUri = firstResult.uri, // <- Sibling of the first file
            )
        )

        // Verify that S3 client was invoked twice
        verifyBlocking(client, times(2)) { putObject(any()) }
        assertEquals(sha256(firstData), firstResult.sha256)
        assertEquals(sha256(secondData), secondResult.sha256)

        // Verify that files are siblings within the same directory
        val commonPrefix = firstResult.uri.toString().commonPrefixWith(secondResult.uri.toString())
        val origin = "https://test-bucket.s3.test-region.amazonaws.com"
        val expectedCommonPrefixLength = "$origin/XX/XX/XXXXXXXXXXXXXXXXXXXXXXXXXXXX/".length
        assertEquals(expectedCommonPrefixLength, commonPrefix.length, "Unexpected common prefix length")
    }

    @Test
    fun downloadTemporaryFileData() {
        val key = """x/y/z/zuckerberg.txt"""
        val data = """Who put Mark Zuckerberg, the elf with no friends, in charge of human connection?!"""
        val dataBytes = data.toByteArray(Charsets.UTF_8)

        val client = mockGetObjectClient(key, dataBytes)

        val service = S3FileStorageService(client, props)
        val fileUri = URI.create("https://test-bucket.s3.test-region.amazonaws.com/$key")

        val result = service.downloadTemporaryFileData(fileUri) { resource ->
            val content = resource.contentAsByteArray
            assertEquals(data, content.toString(Charsets.UTF_8))
        }

        assertEquals(result.sha256, dataBytes.checksumSha256())
        assertEquals(result.size.toBytes(), dataBytes.size.toLong())
        verifyBlocking(client, times(1)) { getObject(any(), any()) }
    }

    @Test
    fun downloadFileData() {
        val key = """foo/bar/musk.txt"""
        val data = """An Elongated Muskrat should *not* be in charge of Social Media."""
        val dataBytes = data.toByteArray(Charsets.UTF_8)

        val client = mockGetObjectClient(key, dataBytes)

        val service = S3FileStorageService(client, props)
        val fileUri = URI.create("https://test-bucket.s3.test-region.amazonaws.com/$key")

        val result = withTemporaryFile { temp ->
            service.downloadFileData(fileUri, temp).also {
                assertEquals(data, temp.readText(Charsets.UTF_8))
            }
        }

        assertEquals(result.sha256, dataBytes.checksumSha256())
        assertEquals(result.size.toBytes(), dataBytes.size.toLong())
        verifyBlocking(client, times(1)) { getObject(any(), any()) }
    }

    private fun mockGetObjectClient(key: String, data: ByteArray) = mock<S3Client> {
        on { it.getObject<Any?>(any(), any()) } doSuspendableAnswer {
            val request = it.getArgument<GetObjectRequest>(0)
            val callback = it.getArgument<suspend (GetObjectResponse) -> Any>(1)
            assertEquals(key, request.key)
            assertEquals(props.bucket, request.bucket)

            callback.invoke(GetObjectResponse {
                body = ByteStream.fromBytes(data)
                contentLength = data.size.toLong()
                checksumSha256 = data.checksumSha256()
            })
        }
    }
}

private fun String.toInputStream(charset: Charset = Charsets.UTF_8) =
    ByteArrayInputStream(toByteArray(charset))
