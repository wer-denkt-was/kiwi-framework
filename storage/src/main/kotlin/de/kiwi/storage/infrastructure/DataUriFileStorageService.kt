package de.kiwi.storage.infrastructure

import de.kiwi.api.application.service.FileStorageService
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.util.unit.DataSize
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.*
import kotlin.io.path.writeBytes

object DataUriFileStorageService : FileStorageService {

    override fun uploadFileData(
        fileUpload: FileStorageService.FileUpload,
    ): FileStorageService.UploadResult {
        val data = fileUpload.resource.inputStream.use { it.readAllBytes() }
        val mime = fileUpload.contentType
        val base64 = Base64.getEncoder().encodeToString(data)
        return FileStorageService.UploadResult(
            uri = URI.create("data:$mime;charset=UTF-8;base64,$base64"),
            size = DataSize.ofBytes(data.size.toLong()),
            sha256 = data.checksumSha256(),
        )
    }

    override fun deleteFileData(fileUri: URI) {
        // Do nothing
    }

    override fun downloadFileData(
        fileUri: URI,
        destination: Path,
    ): FileStorageService.DownloadResult<Unit> {
        val (data, result) = decodeDataUri(fileUri)
        destination.writeBytes(data)
        return result
    }

    override fun <T> downloadTemporaryFileData(
        fileUri: URI,
        action: (Resource) -> T,
    ): FileStorageService.DownloadResult<T> {
        val (data, result) = decodeDataUri(fileUri)
        val value = action(ByteArrayResource(data, result.sha256))
        return FileStorageService.DownloadResult(
            value = value,
            size = result.size,
            sha256 = result.sha256
        )
    }

    override fun signFileUri(fileUri: URI, duration: Duration): FileStorageService.SignedFileUri {
        // Data URI is always valid, signing simply returns URI as-is
        return FileStorageService.SignedFileUri(fileUri, expiresOn = null)
    }

    private fun decodeDataUri(dataUri: URI): Pair<ByteArray, FileStorageService.DownloadResult<Unit>> {
        require(dataUri.scheme == "data") { "Not a data: URI" }
        val uriContent = dataUri.schemeSpecificPart

        val headAndBody = uriContent.split(',', limit = 2)
        require(headAndBody.size == 2) { "Invalid data URI content: $uriContent" }

        val (head, body) = headAndBody
        val base64Suffix = ";base64"
        val base64 = head.endsWith(base64Suffix, ignoreCase = true)
        val mediaTypeName = if (base64) head.removeSuffix(base64Suffix) else head
        val mediaType = MediaType.parseMediaType(mediaTypeName)

        val content =
            if (base64) Base64.getDecoder().decode(body)
            else body.toByteArray(mediaType.charset ?: Charsets.UTF_8)

        val size = DataSize.ofBytes(content.size.toLong())
        val sha256 = content.checksumSha256()
        val result = FileStorageService.DownloadResult(Unit, size, sha256)

        return content to result
    }
}
