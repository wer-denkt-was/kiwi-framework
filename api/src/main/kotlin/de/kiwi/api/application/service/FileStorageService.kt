package de.kiwi.api.application.service

import org.springframework.core.io.InputStreamSource
import org.springframework.core.io.Resource
import org.springframework.util.unit.DataSize
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Service providing functionality to store, load, and sign files.
 */
interface FileStorageService {
    /** Upload a file with content to the storage. */
    fun uploadFileData(fileUpload: FileUpload): UploadResult

    /** Delete a file from storage. */
    fun deleteFileData(fileUri: URI)

    /** Download file content from the storage, writing the content to the given destination path. */
    fun downloadFileData(fileUri: URI, destination: Path): DownloadResult<Unit>

    /**
     * Temporarily download file content from the storage, making the content available as a resource
     * within the given [action] callback and deleting it after the callback returns.
     */
    fun <T> downloadTemporaryFileData(fileUri: URI, action: (Resource) -> T): DownloadResult<T>

    /**
     * Sign a file URI returned from [uploadFileData] to make the file publicly accessible.
     * The signed URI should expire after the given [duration].
     */
    fun signFileUri(fileUri: URI, duration: Duration): SignedFileUri

    data class FileUpload(
        /** Filename of the uploaded file */
        val filename: String,
        /** Content-type of the uploaded file */
        val contentType: String,
        /** File data to be uploaded */
        val resource: InputStreamSource,
        /** If this URI is not null, the file will be uploaded to the same directory as the given sibling file */
        val siblingFileUri: URI? = null,
        /** If true, this file will be accessible to the public (without authentication) using the file storage URI */
        val publicAccess: Boolean = false,
    )

    data class UploadResult(
        /** File URI referencing and uniquely identifying the uploaded file */
        val uri: URI,
        /** Uploaded file size */
        val size: DataSize,
        /** File data SHA-256 checksum */
        val sha256: String,
    )

    data class DownloadResult<T>(
        val value: T,
        val size: DataSize,
        val sha256: String,
    )

    data class SignedFileUri(
        val signedUri: URI,
        val expiresOn: Instant?,
    )
}
