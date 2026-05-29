package de.kiwi.storage.infrastructure.s3

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.model.ChecksumAlgorithm
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.ObjectCannedAcl
import aws.sdk.kotlin.services.s3.presigners.presignGetObject
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.fromFile
import aws.smithy.kotlin.runtime.content.toInputStream
import aws.smithy.kotlin.runtime.content.writeToFile
import aws.smithy.kotlin.runtime.net.url.QueryParameters
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.util.slf4j
import de.kiwi.storage.KiwiStorageProperties
import de.kiwi.storage.KiwiStorageProperties.S3Properties.*
import de.kiwi.storage.infrastructure.*
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.InitializingBean
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.InputStreamSource
import org.springframework.core.io.Resource
import org.springframework.util.unit.DataSize
import org.springframework.web.util.UriComponentsBuilder
import java.io.Closeable
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream
import kotlin.time.toKotlinDuration

private val log = slf4j<S3FileStorageService>()
private val amzDateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX")

class S3FileStorageService(
    private val client: S3Client,
    private val properties: KiwiStorageProperties.S3Properties,
) : FileStorageService, InitializingBean, Closeable by client {

    private val endpoints: S3EndpointProvider = when (properties.objectUriType) {
        ObjectUriType.Subdomain -> SubdomainS3EndpointProvider(properties)
        ObjectUriType.PathPrefix -> PathPrefixS3EndpointProvider(properties)
    }

    private val bucketInitializer: S3BucketInitializer = when (properties.validateBucket) {
        ValidateBucketMode.None -> DoNothingBucketInitializer
        ValidateBucketMode.Validate -> ValidateBucketInitializer
        ValidateBucketMode.AutoCreate -> AutoCreateBucketInitializer
    }

    override fun afterPropertiesSet() = runBlocking {
        bucketInitializer.initializeBucket(client, properties.bucket, publicRead = false)
        if (properties.isPublicBucketEnabled) {
            bucketInitializer.initializeBucket(client, properties.publicAccessBucket, publicRead = true)
        }
    }

    override fun uploadFileData(
        fileUpload: FileStorageService.FileUpload,
    ): FileStorageService.UploadResult = runBlocking {
        val objectKey = generateObjectKey(fileUpload)
        val objectBucket = if (fileUpload.publicAccess) properties.publicAccessBucket else properties.bucket

        val (_, fileSize, sha256) = withTemporaryFile { tmp ->
            // Copy upload data into a temporary file for processing
            fileUpload.resource.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    log.debug("Writing upload file [{}] data to temporary file {}", fileUpload.filename, tmp)
                    input.copyTo(output)
                }
            }

            // Evaluate file size and checksum
            val hash = tmp.fileChecksumSha256()
            val fileSize = DataSize.ofBytes(tmp.fileSize())
            val response = client.putObject {
                bucket = objectBucket
                key = objectKey
                body = ByteStream.fromFile(tmp.toFile())
                contentType = fileUpload.contentType
                contentLength = fileSize.toBytes()
                checksumAlgorithm = ChecksumAlgorithm.Sha256
                checksumSha256 = hash.formatBase64()
                if (properties.isPublicAclEnabled && fileUpload.publicAccess) {
                    acl = ObjectCannedAcl.PublicRead
                }
            }

            log.debug("Uploaded file [{}] data to S3 bucket {}::{}", fileUpload.filename, objectBucket, objectKey)
            Triple(first = response, second = fileSize, third = hash.formatChecksum())
        }

        val objectInfo = S3EndpointProvider.S3ObjectInfo(
            region = properties.region,
            bucket = objectBucket,
            key = objectKey,
        )

        FileStorageService.UploadResult(
            uri = endpoints.objectEndpoint(objectInfo),
            size = fileSize,
            sha256 = sha256,
        )
    }

    override fun deleteFileData(fileUri: URI) = runBlocking<Unit> {
        val objectKey = endpoints.objectUriInfo(fileUri)
        client.deleteObject {
            bucket = objectKey.bucket
            key = objectKey.key
        }
    }

    override fun downloadFileData(
        fileUri: URI,
        destination: Path,
    ): FileStorageService.DownloadResult<Unit> = runBlocking {
        downloadObject(fileUri) { body ->
            body.writeToFile(destination)
            Unit
        }
    }

    override fun <T> downloadTemporaryFileData(
        fileUri: URI,
        action: (Resource) -> T,
    ): FileStorageService.DownloadResult<T> = runBlocking {
        downloadObject(fileUri) { body ->
            val description = "s3 object loaded from $fileUri"
            val streamSource = InputStreamSource { body.toInputStream() }
            val resource = InputStreamResource(streamSource, description)
            action(resource)
        }
    }

    override fun signFileUri(fileUri: URI, duration: Duration) = runBlocking {
        val request = createGetRequest(fileUri)
        val signedRequest = client.presignGetObject(request, duration.toKotlinDuration())

        val signedUri = URI.create(signedRequest.url.toString())
        val publicSignedUri = properties.publicAccessHostname
            ?.let { publicHostname -> UriComponentsBuilder.fromUri(signedUri).host(publicHostname).build().toUri() }
            ?: signedUri

        FileStorageService.SignedFileUri(
            signedUri = publicSignedUri,
            expiresOn = parseExpiresOn(signedRequest.url.parameters),
        )
    }

    private fun parseExpiresOn(signedRequestParams: QueryParameters): Instant? {
        // See https://docs.aws.amazon.com/AmazonS3/latest/API/sigv4-query-string-auth.html
        val decodedParams = signedRequestParams.decodedParameters
        val amzDateValue = decodedParams["X-Amz-Date"]?.firstOrNull() ?: return null
        val amzExpiresValue = decodedParams["X-Amz-Expires"]?.firstOrNull() ?: return null
        val amzExpiresSeconds = amzExpiresValue.toLongOrNull() ?: return null
        val amzDate = OffsetDateTime.parse(amzDateValue, amzDateFormatter)
        val expirationDate = amzDate.plusSeconds(amzExpiresSeconds)
        return expirationDate.toInstant()
    }

    private fun generateObjectKey(upload: FileStorageService.FileUpload): String {
        val prefix = generateObjectKeyPrefix(upload.siblingFileUri)
        val suffix = generateObjectKeySuffix(upload.filename)
        return "$prefix/$suffix"
    }

    private fun generateObjectKeyPrefix(siblingFileUri: URI?): String {
        // Create ".{2}/.{2}/.{28}" (34 characters) hexadecimal prefix,
        // either by randomly generating from a UUID or reusing the sibling URI prefix
        val prefixLength = 34
        val prefix = if (siblingFileUri == null) {
            val uuid = UUID.randomUUID().toString().replace("-", "") // 32 hex digits
            val part1 = uuid.substring(0, 2)
            val part2 = uuid.substring(2, 4)
            val part3 = uuid.substring(4)
            "$part1/$part2/$part3"
        } else {
            val objectInfo = endpoints.objectUriInfo(siblingFileUri)
            val siblingKey = objectInfo.key
            siblingKey.substring(0, prefixLength)
        }

        assert(prefix.length == prefixLength) { "Invalid random object-key prefix generated" }
        return prefix
    }

    private fun generateObjectKeySuffix(filename: String): String {
        return when (properties.anonymizeFilename) {
            AnonymizeFilename.None -> filename
            AnonymizeFilename.Slugify -> filename.slugifyFilename()
            AnonymizeFilename.Anonymous -> "file_${filename.toByteArray(Charsets.UTF_8).checksumSha256()}"
        }
    }

    private suspend fun <T> downloadObject(
        fileUri: URI,
        withBody: suspend (ByteStream) -> T,
    ): FileStorageService.DownloadResult<T> {
        val request = createGetRequest(fileUri)
        return client.getObject(request) { response ->
            val size = DataSize.ofBytes(response.contentLength ?: 0L)
            val sha256 = response.checksumSha256 ?: ""
            val body = response.body ?: ByteStream.fromBytes(ByteArray(0))
            val value = withBody(body)
            FileStorageService.DownloadResult(
                value = value,
                size = size,
                sha256 = sha256
            )
        }
    }

    private fun createGetRequest(fileUri: URI): GetObjectRequest {
        val objectKey = endpoints.objectUriInfo(fileUri)
        return GetObjectRequest {
            bucket = objectKey.bucket
            key = objectKey.key
        }
    }
}
