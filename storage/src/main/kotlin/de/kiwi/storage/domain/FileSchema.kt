package de.kiwi.storage.domain

import de.kiwi.api.schema.*
import java.time.Duration

object FileSchema : EntitySchema<FileEntity>() {
    val id by entityId()
    val filename by column(FileEntity::filename)
    val uploadedOn by column(FileEntity::uploadedOn, IsoLocalDateTimeType)
    val uploadedBy by column(FileEntity::uploadedBy)
    val uploadedById by column(FileEntity::uploadedById)
    val contentType by column<String>(FileEntity::contentType)
    val fileSize by column(FileEntity::fileSize, DataSizeType)
    val fileUri by column(FileEntity::storageUri, UriType)
}

object FileFieldsets : FieldsetsAvailable<FileEntity>(FileSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        include(FileSchema.id, FileSchema.filename, FileSchema.fileUri)
    }

    /**
     * Create a dynamic fieldset with a signed [fileUri][FileSchema.fileUri] field.
     * This [ColumnField] field will use the [SignedFileUriType] instead of the default [UriType].
     * @see [SignedFileUriProvider]
     */
    fun withSignedFileUri(duration: Duration) = FileSchema.buildFieldset(name = "<signed-$duration>") {
        extendsFieldset(default)

        // Replace "fileUri" column with a copy that signs the File-URI
        exclude(FileSchema.fileUri)
        include(FileSchema.fileUri.copy(type = SignedFileUriType(duration)))
    }
}

