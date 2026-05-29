package de.kiwi.storage.infrastructure.local

import de.kiwi.api.application.service.AuthenticationTokenService
import de.kiwi.api.application.service.FileStorageService
import de.kiwi.storage.KiwiStorageProperties
import de.kiwi.storage.infrastructure.fileChecksumSha256
import de.kiwi.storage.infrastructure.formatChecksum
import jakarta.servlet.ServletContext
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.util.unit.DataSize
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.*
import kotlin.io.path.fileSize

class LocalFileStorageService(
    private val tokenService: AuthenticationTokenService,
    private val props: KiwiStorageProperties.LocalFileProperties,
    private val servletContext: ServletContext?,
) : FileStorageService {
    init {
        Files.createDirectories(props.parentDirectory)
    }

    companion object {
        const val LOCAL_STORAGE_ENDPOINT = "/file/storage"
        const val LOCAL_STORAGE_TOKEN_PARAM = "_signedToken"

        private val FILE_PATH_REGEX = Regex("/(?<uuid>[0-9a-f\\-]+)/(?<filename>.+)", RegexOption.IGNORE_CASE)

        private const val SIGNED_FILE_PRINCIPAL = "SIGNED_FILE_TOKEN"
        private const val SIGNED_FILE_READ_ROLE_PREFIX = "READ_FILE:"
        private const val PUBLIC_ACCESS_METAFILE = ".__public__."

        private fun generateSignedAuthority(uuid: UUID): GrantedAuthority {
            return SimpleGrantedAuthority(SIGNED_FILE_READ_ROLE_PREFIX + uuid.toString())
        }

        private fun Authentication.hasSignedFileAuthority(uuid: UUID): Boolean {
            val expectedSuffix = uuid.toString()
            return name == SIGNED_FILE_PRINCIPAL && authorities.any { it.isReadFileAuthority(expectedSuffix) }
        }

        private fun GrantedAuthority.isReadFileAuthority(expectedSuffix: String): Boolean {
            return authority.startsWith(SIGNED_FILE_READ_ROLE_PREFIX) &&
                    expectedSuffix == authority.substring(SIGNED_FILE_READ_ROLE_PREFIX.length)
        }
    }

    fun accessFileResource(uuid: UUID, filename: String, signedToken: String?): Resource? {
        val filePath = resolveFilePath(uuid, filename)
        val signedTokenAuthentication = signedToken?.let { tokenService.authenticateToken(it) }
        val authorized = isFileAccessAuthorized(filePath, uuid, signedTokenAuthentication)
        return if (authorized) FileSystemResource(filePath) else null
    }

    override fun uploadFileData(fileUpload: FileStorageService.FileUpload): FileStorageService.UploadResult {
        // Generate UUID, filepath
        val uuid = createFileUploadUUID(fileUpload)
        val filename = fileUpload.filename.slugifyFilename()
        val filePath = resolveFilePath(uuid, filename)

        // Write file data to disk
        Files.createDirectories(filePath.parent)
        val fileLength = fileUpload.resource.inputStream.use { data -> Files.copy(data, filePath) }

        // If file-upload has public access, generate a public-access metafile
        if (fileUpload.publicAccess) {
            createPublicAccessMetafile(filePath)
        }

        // Create URI for file retrieval
        val fileStorageUri = createFileStorageUri(uuid, filename)

        // Generate checksum over file-content and return upload result
        return FileStorageService.UploadResult(
            uri = fileStorageUri,
            size = DataSize.ofBytes(fileLength),
            sha256 = filePath.fileChecksumSha256().formatChecksum(),
        )
    }

    private fun createFileStorageUri(uuid: UUID, filename: String): URI {
        // Create URI "{scheme}://{origin}/file/storage/{uuid}/{filename}"
        val fileUriBuilder = createFileUriBuilder()
        props.forceScheme?.let { fileUriBuilder.scheme(it) }

        val storagePath = localFileStoragePath()
        return fileUriBuilder
            .replacePath("$storagePath/{uuid}/{filename}")
            .replaceQuery(null)
            .fragment(null)
            .build(mapOf("uuid" to uuid, "filename" to filename))
            .normalize()
    }

    private fun localFileStoragePath(): String {
        val contextPath = servletContext?.contextPath?.removeSuffix("/") ?: ""
        val storageEndpoint = LOCAL_STORAGE_ENDPOINT.removePrefix("/")
        return if (contextPath.isEmpty()) "/$storageEndpoint"
        else "/$contextPath/$storageEndpoint"
    }

    private fun createFileUriBuilder(): UriComponentsBuilder {
        // Create file-uri builder based on either configuration or current request
        val originUri = props.fileServerOrigin
        val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        return when {
            originUri != null -> UriComponentsBuilder.fromUri(originUri)
            requestAttributes != null -> ServletUriComponentsBuilder.fromRequest(requestAttributes.request)
            else -> throw IllegalStateException("Configuration kiwi.storage.local.file-server-origin is *required* for usage outside servlet requests")
        }
    }

    private fun createFileUploadUUID(fileUpload: FileStorageService.FileUpload): UUID {
        // Either generate new random UUID, or reuse UUID of sibling file URI
        return when (val sibling = fileUpload.siblingFileUri) {
            null -> UUID.randomUUID()
            else -> parseFileUri(sibling).first
        }
    }

    override fun deleteFileData(fileUri: URI) {
        val (uuid, filename) = parseFileUri(fileUri)
        val file = resolveFilePath(uuid, filename)
        Files.deleteIfExists(file)
    }

    override fun downloadFileData(fileUri: URI, destination: Path) =
        loadFileAction<Unit>(fileUri) { file -> Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING) }

    override fun <T> downloadTemporaryFileData(fileUri: URI, action: (Resource) -> T) =
        loadFileAction(fileUri) { file -> action(FileSystemResource(file)) }

    private fun <T> loadFileAction(fileUri: URI, action: (Path) -> T): FileStorageService.DownloadResult<T> {
        val (uuid, filename) = parseFileUri(fileUri)
        val file = resolveFilePath(uuid, filename)

        val size = DataSize.ofBytes(file.fileSize())
        val sha256 = file.fileChecksumSha256().formatChecksum()
        val value = action(file)

        return FileStorageService.DownloadResult(value = value, size = size, sha256 = sha256)
    }

    override fun signFileUri(fileUri: URI, duration: Duration): FileStorageService.SignedFileUri {
        val (uuid, _) = parseFileUri(fileUri)
        val authorities = setOf(generateSignedAuthority(uuid))
        val fileAccessAuthentication = UsernamePasswordAuthenticationToken(SIGNED_FILE_PRINCIPAL, null, authorities)
        val (signedToken, expiresOn) = tokenService.generateTemporaryToken(fileAccessAuthentication, duration)

        val signedUri = UriComponentsBuilder
            .fromUri(fileUri)
            .replaceQueryParam(LOCAL_STORAGE_TOKEN_PARAM, signedToken)
            .build(true) // Already properly encoded in fromUri
            .toUri()

        return FileStorageService.SignedFileUri(signedUri, expiresOn)
    }

    private fun resolveFilePath(uuid: UUID, filename: String): Path {
        val directoryName = uuid.toString().replace("-", "")
        val firstPartition = directoryName.take(2)
        val secondPartition = directoryName.substring(2, 4)
        val container = directoryName.drop(4)

        return props.parentDirectory
            .resolve(firstPartition)
            .resolve(secondPartition)
            .resolve(container)
            .resolve(filename)
    }

    private fun parseFileUri(fileUri: URI): Pair<UUID, String> {
        val endpointPath = fileUri.path
        val filePath = endpointPath.removePrefix(LOCAL_STORAGE_ENDPOINT)
        val pathMatch = FILE_PATH_REGEX.matchEntire(filePath)
            ?: throw IllegalArgumentException("Invalid local file URI")

        val uuid = UUID.fromString(pathMatch.groups["uuid"]!!.value)
        val filename = pathMatch.groups["filename"]!!.value
        return Pair(uuid, filename)
    }

    private fun createPublicAccessMetafile(file: Path) {
        val publicAccessMetafile = resolveMetafile(file, PUBLIC_ACCESS_METAFILE)
        Files.createFile(publicAccessMetafile)
    }

    private fun isPublicAccessFile(file: Path): Boolean {
        val publicAccessMetafile = resolveMetafile(file, PUBLIC_ACCESS_METAFILE)
        return Files.exists(publicAccessMetafile)
    }

    private fun resolveMetafile(path: Path, metaPrefix: String): Path {
        // "/path/to/filename" ->
        // "/path/to/{meta}filename"
        return path.parent.resolve(metaPrefix + path.fileName)
    }

    private fun isFileAccessAuthorized(
        filePath: Path,
        fileUuid: UUID,
        signedTokenAuthentication: Authentication?,
    ): Boolean {
        // Skip authorization check if public-access metafile exists
        if (isPublicAccessFile(filePath)) return true

        // Check if given authentication has a signed-file authority
        return signedTokenAuthentication?.hasSignedFileAuthority(fileUuid) ?: false
    }
}

/**
 * Slugify filenames, such that they are universally valid.
 * Replaces reserved/unsafe characters with underscores, and limits the length to 255 characters.
 */
private fun String.slugifyFilename() =
    replace("[&$+,/:;=?@#<>\\[\\]{}|\\\\^%]+".toRegex(), "_")
        .take(255)
