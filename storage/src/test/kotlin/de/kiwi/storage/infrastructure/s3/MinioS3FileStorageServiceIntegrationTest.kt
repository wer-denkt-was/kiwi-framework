package de.kiwi.storage.infrastructure.s3

import de.kiwi.api.application.service.ClockProvider
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.api.util.slf4j
import de.kiwi.storage.KiwiStorageProperties
import de.kiwi.storage.KiwiStorageProperties.S3Properties.*
import de.kiwi.storage.infrastructure.withTemporaryFile
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockServletContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import org.springframework.web.client.toEntity
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.utility.DockerImageName
import java.net.URI
import java.nio.charset.Charset
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.io.path.readText

private val log = slf4j<MinioS3FileStorageServiceIntegrationTest>()

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("minio-test")
class MinioS3FileStorageServiceIntegrationTest {

    // Create and set up MinIO service with storage buckets,
    // then configure S3FileStorageService to connect to MinIO
    companion object {
        @JvmStatic
        @Container
        val minioContainer = MinIOContainer(DockerImageName.parse("minio/minio:latest"))

        private val minioBucketName = UUID.randomUUID().toString()
        private val minioPublicBucketName = "$minioBucketName-public"

        private fun minioLog(message: String) {
            log.info("[MinIO]> $message")
        }

        private fun minioExec(vararg command: String) {
            val result = minioContainer.execInContainer(*command)
            minioLog("CMD [${command.joinToString(" ")}] => (${result.exitCode}) ${result.stdout.trim()}")
        }

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            minioContainer.start()
            minioContainer.followOutput { frame -> minioLog(frame.utf8StringWithoutLineEnding) }

            // Create private and public bucket
            val username = minioContainer.userName
            val password = minioContainer.password
            minioExec("mc", "alias", "set", "local", "http://localhost:9000", username, password)
            minioExec("mc", "mb", "local/$minioBucketName")
            minioExec("mc", "mb", "local/$minioPublicBucketName")
            minioExec("mc", "anonymous", "set", "none", "local/$minioBucketName")
            minioExec("mc", "anonymous", "set", "download", "local/$minioPublicBucketName")
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            minioContainer.stop()
        }

        @JvmStatic
        @Suppress("unused")
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            val minioUri = URI.create(minioContainer.s3URL)
            registry.add("kiwi.storage.provider") { KiwiStorageProperties.StorageProvider.S3 }
            registry.add("kiwi.storage.s3.scheme") { "http" }
            registry.add("kiwi.storage.s3.hostname") { "${minioUri.host}:${minioUri.port}" }
            registry.add("kiwi.storage.s3.access-key") { minioContainer.userName }
            registry.add("kiwi.storage.s3.secret-key") { minioContainer.password }
            registry.add("kiwi.storage.s3.bucket") { minioBucketName }
            registry.add("kiwi.storage.s3.object-uri-type") { ObjectUriType.PathPrefix }
            registry.add("kiwi.storage.s3.validate-bucket") { ValidateBucketMode.Validate }
            registry.add("kiwi.storage.s3.public-object-mode") { PublicObjectMode.Bucket }
            registry.add("kiwi.storage.s3.public-object-bucket-suffix") { "-public" }
        }
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun clockProvider() = ClockProvider.SystemUTC

        @Bean
        fun servletContext() = MockServletContext()
    }

    @Autowired
    lateinit var fileStorageService: FileStorageService

    private val restClient = RestClient.create()

    @BeforeEach
    fun setUp() {
        assertInstanceOf<S3FileStorageService>(fileStorageService)
    }

    @Test
    fun storePrivateFile() {
        // Upload dummy file
        val dummyData = generateDummyData("This is a private file, reading this is forbidden!")
        val uploaded = fileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "test.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = dummyData.toResource(),
                publicAccess = false, // no public access
            )
        )

        // Assert that file can't be downloaded from anonymous file URI
        val getFileResponseStatusCode = restClient.get().uri(uploaded.uri).exchange { _, res -> res.statusCode }
        println("GET unsigned => $getFileResponseStatusCode")
        assertEquals(HttpStatus.FORBIDDEN, getFileResponseStatusCode)

        // Assert that file can be downloaded from signed file URI
        val now = Instant.now()
        val (signedUri, expiresOn) = fileStorageService.signFileUri(uploaded.uri, Duration.ofMinutes(5))
        println("EXPIRES ON => $expiresOn")

        val getSignedFileResponse = restClient.get().uri(signedUri).retrieve().toEntity<String>()
        println("GET signed => $getSignedFileResponse")

        assertEquals(HttpStatus.OK, getSignedFileResponse.statusCode)
        assertEquals(dummyData, getSignedFileResponse.body)

        val notYetExpired = now.plus(Duration.ofMinutes(2))
        val alreadyExpired = now.plus(Duration.ofMinutes(15))
        assertTrue(expiresOn != null && expiresOn.isAfter(now), "expiresOn is in the past")
        assertTrue(expiresOn != null && expiresOn.isAfter(notYetExpired), "expiresOn is too early")
        assertTrue(expiresOn != null && expiresOn.isBefore(alreadyExpired), "expiresOn is too late")
    }

    @Test
    fun storePublicFile() {
        // Upload dummy file
        val dummyData = generateDummyData("This is a public file, feel free to read it :)")
        val uploaded = fileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "test-public.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = dummyData.toResource(),
                publicAccess = true, // allow public access!
            )
        )

        // Assert that file can be downloaded from anonymous file URI
        val getFileResponse = restClient.get().uri(uploaded.uri).retrieve().toEntity<String>()
        println("GET unsigned => $getFileResponse")
        assertEquals(HttpStatus.OK, getFileResponse.statusCode)
        assertEquals(dummyData, getFileResponse.body)

        // Assert that file can be downloaded from signed file URI
        val signed = fileStorageService.signFileUri(uploaded.uri, Duration.ofMinutes(5))
        val getSignedFileResponse = restClient.get().uri(signed.signedUri).retrieve().toEntity<String>()
        println("GET signed => $getSignedFileResponse")
        assertEquals(HttpStatus.OK, getSignedFileResponse.statusCode)
        assertEquals(dummyData, getSignedFileResponse.body)
    }

    @Test
    fun downloadFile() {
        // Upload dummy file
        val dummyData = generateDummyData("Some data to be downloaded")
        val uploaded = fileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "test-download.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = dummyData.toResource(),
            )
        )

        // Download file to local filesystem
        withTemporaryFile { tempFile ->
            fileStorageService.downloadFileData(uploaded.uri, tempFile)
            val fileContent = tempFile.readText(Charsets.UTF_8)
            assertEquals(dummyData, fileContent)
        }

        // Download file temporarily
        fileStorageService.downloadTemporaryFileData(uploaded.uri) { resource ->
            val downloadContent = resource.getContentAsString(Charsets.UTF_8)
            assertEquals(dummyData, downloadContent)
        }
    }

    @Test
    fun deleteFile() {
        // Upload dummy file
        val dummyData = generateDummyData("This file will be deleted")
        val uploaded = fileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "test-delete-me.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = dummyData.toResource(),
            )
        )

        // Assume the file is readable
        val signed = fileStorageService.signFileUri(uploaded.uri, Duration.ofMinutes(5))
        val getSignedFileResponse = restClient.get().uri(signed.signedUri).retrieve().toEntity<String>()
        println("GET before delete => $getSignedFileResponse")
        assumeTrue { getSignedFileResponse.statusCode == HttpStatus.OK }
        assumeTrue { getSignedFileResponse.body == dummyData }

        // Delete file
        fileStorageService.deleteFileData(uploaded.uri)

        // Assert that the file no longer exists
        val getDeletedFileResponseCode = restClient.get().uri(signed.signedUri).exchange { _, res -> res.statusCode }
        println("GET after delete => $getDeletedFileResponseCode")
        assertEquals(HttpStatus.NOT_FOUND, getDeletedFileResponseCode)
    }
}

private fun generateDummyData(readableContent: String) = buildString {
    val boundary = UUID.randomUUID().toString()
    appendLine("--- BEGIN $boundary ---")
    appendLine(readableContent)
    appendLine("--- END $boundary ---")
}

private fun String.toResource(charset: Charset = Charsets.UTF_8) = ByteArrayResource(toByteArray(charset))
