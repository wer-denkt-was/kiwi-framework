package de.kiwi.storage.domain

import de.kiwi.api.domain.BaseEntity
import de.kiwi.api.domain.DomainResult
import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.UserDescriptor
import jakarta.persistence.*
import org.springframework.util.unit.DataSize
import java.net.URI
import java.time.Clock
import java.time.LocalDateTime

@Embeddable
class FileId(value: Long) : EntityId(value)

@Entity
@Table(name = "kiwi_file")
class FileEntity(
    id: FileId,

    @Column(name = "filename", length = 1024, nullable = false)
    val filename: String,

    @Column(name = "uploaded_on", nullable = false)
    val uploadedOn: LocalDateTime,

    @Column(name = "uploaded_by", length = 1024, nullable = false)
    val uploadedBy: String,

    @Column(name = "uploaded_by_id", nullable = true)
    val uploadedById: Long?,

    @Column(name = "content_type", length = 255, nullable = false)
    val contentType: String,

    @Convert(converter = FileDataSizeConverter::class)
    @Column(name = "file_size_bytes", nullable = false)
    val fileSize: DataSize,

    @Convert(converter = FileUriConverter::class)
    @Column(name = "storage_uri", columnDefinition = "mediumtext")
    val storageUri: URI,
) : BaseEntity<FileId>(id) {

    companion object {
        fun create(
            filename: String,
            fileSize: DataSize,
            fileUri: URI,
            contentType: String,
            uploader: UserDescriptor,
            clock: Clock,
        ) = DomainResult { record ->
            FileEntity(
                id = EntityId.generate<FileId>(),
                filename = filename,
                uploadedOn = LocalDateTime.now(clock),
                uploadedBy = uploader.username,
                uploadedById = uploader.userId,
                contentType = contentType,
                fileSize = fileSize,
                storageUri = fileUri,
            ).also {
                record(FileCreated(it))
            }
        }
    }
}

@Converter(autoApply = false)
internal class FileDataSizeConverter : AttributeConverter<DataSize, Long> {
    override fun convertToDatabaseColumn(attribute: DataSize?): Long? = attribute?.toBytes()
    override fun convertToEntityAttribute(dbData: Long?): DataSize? = dbData?.let { DataSize.ofBytes(it) }
}

@Converter(autoApply = false)
internal class FileUriConverter : AttributeConverter<URI, String> {
    override fun convertToDatabaseColumn(attribute: URI?): String? = attribute?.toString()
    override fun convertToEntityAttribute(dbData: String?): URI? = dbData?.let { URI.create(it) }
}
