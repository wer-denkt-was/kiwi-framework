package de.kiwi.storage.infrastructure.local

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import de.kiwi.api.application.service.AuthenticationTokenService
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties
import de.kiwi.storage.infrastructure.checksumSha256
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockServletContext
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.util.FileSystemUtils
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*

class LocalFileStorageServiceTest {

    private lateinit var temporaryParentDirectory: Path
    private lateinit var localFileStorageService: LocalFileStorageService
    private lateinit var mockServletRequest: MockHttpServletRequest

    @BeforeEach
    fun setUp() {
        temporaryParentDirectory = Files.createTempDirectory("test-local-storage-")
        localFileStorageService = LocalFileStorageService(
            tokenService = TestTokenService,
            props = KiwiStorageProperties.LocalFileProperties(parentDirectory = temporaryParentDirectory),
            servletContext = MockServletContext(),
        )

        mockServletRequest = MockHttpServletRequest()
        mockServletRequest.method = "http"
        mockServletRequest.serverName = "test-server"
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(mockServletRequest))
    }

    @AfterEach
    fun tearDown() {
        FileSystemUtils.deleteRecursively(temporaryParentDirectory)
    }

    @Test
    fun uploadFileData() {
        val content = "Hello World".toByteArray(Charsets.UTF_8)
        val result = localFileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "test.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = ByteArrayResource(content),
            )
        )

        println(result)
        assertThat(result.uri.toString(), stringContainsInOrder("http://test-server/file/storage/", "/test.txt"))
        assertThat(result.size.toBytes(), equalTo(content.size.toLong()))
        assertThat(result.sha256, equalTo(content.checksumSha256()))
    }

    @Test
    fun uploadSiblingFileData() {
        val firstContent = "It's-a me, Mario!".toByteArray(Charsets.UTF_8)
        val secondContent = "Luigi number one!".toByteArray(Charsets.UTF_8)

        val firstResult = localFileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "mario.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = ByteArrayResource(firstContent)
            )
        )
        val secondResult = localFileStorageService.uploadFileData(
            FileStorageService.FileUpload(
                filename = "luigi.txt",
                contentType = MediaType.TEXT_PLAIN_VALUE,
                resource = ByteArrayResource(secondContent),
                siblingFileUri = firstResult.uri, // <- Sibling of the first file
            )
        )

        // Common prefix should include UUID between siblings, as they are stored in the same directory
        val commonUriPrefix = firstResult.uri.toString().commonPrefixWith(secondResult.uri.toString())
        val expectedPrefixLength = "http://test-server/file/storage/XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX/".length

        println("first = $firstResult")
        println("second = $secondResult")
        println("common = $commonUriPrefix")
        assertThat(commonUriPrefix, hasLength(expectedPrefixLength))
        assertThat(firstResult.sha256, equalTo(firstContent.checksumSha256()))
        assertThat(secondResult.sha256, equalTo(secondContent.checksumSha256()))
    }

    @Test
    fun downloadFileData() {
        val content = "Hello World".toByteArray(Charsets.UTF_8)
        val fileUri = prepareUploadFile("test.txt", content)

        val downloadFile = temporaryParentDirectory.resolve("download.txt")
        localFileStorageService.downloadFileData(fileUri, downloadFile)

        assertTrue(Files.exists(downloadFile))
        assertArrayEquals(content, Files.readAllBytes(downloadFile))
    }

    @Test
    fun downloadTemporaryFileData() {
        val content = "Hello World".toByteArray(Charsets.UTF_8)
        val fileUri = prepareUploadFile("test.txt", content)

        localFileStorageService.downloadTemporaryFileData(fileUri) { resource ->
            assertEquals("test.txt", resource.filename)
            assertArrayEquals(content, resource.inputStream.readAllBytes())
        }
    }

    @Test
    fun signFileUri() {
        val content = "Hello World".toByteArray(Charsets.UTF_8)
        val fileUri = prepareUploadFile("test.txt", content)

        val signed = localFileStorageService.signFileUri(fileUri, Duration.ofMinutes(30))

        println(signed)
        assertThat(
            signed.signedUri.toString(),
            stringContainsInOrder(
                "http://test-server/file/storage/",
                "/test.txt",
                "?_signedToken=",
            )
        )
    }

    @Test
    fun privateFileAccessDenied() {
        val content = "You are NOT allowed to read this!".toByteArray(Charsets.UTF_8)
        val privateFileUri = prepareUploadFile("test.txt", content, publicAccess = false)

        val (uuid, filename) = prepareSplitFileUri(privateFileUri)
        val fileResource = localFileStorageService.accessFileResource(uuid, filename, signedToken = null)
        assertNull(fileResource)
    }

    @Test
    fun privateFileAccessAllowed() {
        val content = "You are allowed to read this".toByteArray(Charsets.UTF_8)
        val privateFileUri = prepareUploadFile("test.txt", content, publicAccess = false)
        val signedFileUri = localFileStorageService.signFileUri(privateFileUri, Duration.ofMinutes(30)).signedUri

        val signedToken = signedFileUri.parseQueryParams().getFirst(LocalFileStorageService.LOCAL_STORAGE_TOKEN_PARAM)
        val (uuid, filename) = prepareSplitFileUri(signedFileUri)
        val fileResource = localFileStorageService.accessFileResource(uuid, filename, signedToken)
            ?: fail("Failed to access private file ${uuid}/${filename} with token [$signedToken]")

        println("$signedFileUri => $fileResource")
        assertTrue(fileResource.exists())
        assertArrayEquals(content, fileResource.inputStream.readAllBytes())
    }

    @Test
    fun publicFileAccess() {
        val content = "Hello World".toByteArray(Charsets.UTF_8)
        val publicFileUri = prepareUploadFile("test.txt", content, publicAccess = true)

        val (uuid, filename) = prepareSplitFileUri(publicFileUri)
        val fileResource = localFileStorageService.accessFileResource(uuid, filename, signedToken = null)
            ?: fail("Failed to access public file ${uuid}/${filename}")

        println("$publicFileUri => $fileResource")
        assertTrue(fileResource.exists())
        assertArrayEquals(content, fileResource.inputStream.use { it.readAllBytes() })
    }

    private fun prepareSplitFileUri(fileUri: URI): Pair<UUID, String> {
        val (_, _, _, uuidValue, filename) = fileUri.path.split('/')
        val uuid = UUID.fromString(uuidValue)
        return Pair(uuid, filename)
    }

    private fun prepareUploadFile(
        filename: String,
        content: ByteArray,
        contentType: String = MediaType.TEXT_PLAIN_VALUE,
        publicAccess: Boolean = false,
    ): URI {
        val upload = FileStorageService.FileUpload(
            filename = filename,
            contentType = contentType,
            resource = ByteArrayResource(content),
            publicAccess = publicAccess,
        )
        val result = localFileStorageService.uploadFileData(upload)
        return result.uri
    }
}

private fun URI.parseQueryParams(): MultiValueMap<String, String> {
    val params = LinkedMultiValueMap<String, String>()
    rawQuery.split('&').forEach { part ->
        val split = part.split("=", limit = 2)
        val name = split.getOrNull(0) ?: ""
        val value = split.getOrNull(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: ""
        params.add(name, value)
    }
    return params
}

private object TestTokenService : AuthenticationTokenService {
    private const val PREFIX = "TEST:"

    private val jsonMapper = ObjectMapper()
    private val jsonFactory = JsonNodeFactory.instance!!
    private val encoder = Base64.getUrlEncoder()
    private val decoder = Base64.getUrlDecoder()

    override fun generateToken(authentication: Authentication): String {
        val roles = jsonFactory.arrayNode()
        roles.addAll(authentication.authorities.map { jsonFactory.textNode(it.authority) })

        val token = jsonFactory.objectNode()
        token.replace("name", jsonFactory.textNode(authentication.name))
        token.replace("roles", roles)

        val tokenBytes = token.toString().toByteArray(Charsets.UTF_8)
        return PREFIX + encoder.encodeToString(tokenBytes)
    }

    override fun generateTemporaryToken(
        authentication: Authentication,
        duration: Duration,
    ): AuthenticationTokenService.TemporaryToken {
        val token = generateToken(authentication)
        return AuthenticationTokenService.TemporaryToken(token, expiresOn = null)
    }

    override fun authenticateToken(bearerToken: String): UsernamePasswordAuthenticationToken {
        if (!bearerToken.startsWith(PREFIX)) {
            throw BadCredentialsException("Invalid test token")
        }

        val tokenBase64 = bearerToken.removePrefix(PREFIX)
        val tokenBytes = decoder.decode(tokenBase64)
        val token = jsonMapper.readTree(tokenBytes)

        val name = token.get("name").asText()
        val roles = token.get("roles").map { it.asText() }
        val authorities = roles.mapTo(HashSet()) { SimpleGrantedAuthority(it) }

        return UsernamePasswordAuthenticationToken.authenticated(name, null, authorities)
    }
}
