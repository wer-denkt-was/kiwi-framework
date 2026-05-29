package de.kiwi.storage.infrastructure

import de.kiwi.api.util.slf4j
import de.kiwi.api.application.service.FileStorageService
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.util.unit.DataSize
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.fileSize
import kotlin.io.path.outputStream

private val log = slf4j<NullFileStorageService>()

private val emptySha256 = ByteArray(0).checksumSha256()

object NullFileStorageService : FileStorageService {
    override fun uploadFileData(
        fileUpload: FileStorageService.FileUpload,
    ): FileStorageService.UploadResult {
        return withTemporaryFile { tmp ->
            log.debug("Will ignore data from uploaded file ${fileUpload.filename}")
            fileUpload.resource.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            FileStorageService.UploadResult(
                uri = URI.create("null://${fileUpload.filename}"),
                size = DataSize.ofBytes(tmp.fileSize()),
                sha256 = tmp.fileChecksumSha256().formatChecksum(),
            )
        }
    }

    override fun deleteFileData(fileUri: URI) {
        // Do nothing
    }

    override fun downloadFileData(
        fileUri: URI,
        destination: Path,
    ): FileStorageService.DownloadResult<Unit> {
        return FileStorageService.DownloadResult(
            value = Unit,
            size = DataSize.ofBytes(0L),
            sha256 = emptySha256
        )
    }

    override fun <T> downloadTemporaryFileData(
        fileUri: URI,
        action: (Resource) -> T,
    ): FileStorageService.DownloadResult<T> {
        val value = action(ByteArrayResource(ByteArray(0), "<null>"))
        return FileStorageService.DownloadResult(
            value = value,
            size = DataSize.ofBytes(0L),
            sha256 = emptySha256
        )
    }

    override fun signFileUri(fileUri: URI, duration: Duration): FileStorageService.SignedFileUri {
        // Null file URIs are always invalid, signing returns URI as-is
        return FileStorageService.SignedFileUri(fileUri, expiresOn = null)
    }
}
