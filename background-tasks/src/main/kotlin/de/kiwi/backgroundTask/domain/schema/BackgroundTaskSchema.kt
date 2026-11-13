package de.kiwi.backgroundTask.domain.schema

import de.kiwi.api.schema.*
import de.kiwi.backgroundTask.domain.BackgroundTask

/** [EntitySchema] of [BackgroundTask] entities */
object BackgroundTaskSchema : EntitySchema<BackgroundTask>() {
    val id by entityId()
    val taskHandle by column(BackgroundTask::taskHandle, type = UuidType)
    val taskRequestClassName by column(BackgroundTask::taskRequestClassName)
    val taskRequest by column(BackgroundTask::taskRequestJson, type = RawJsonStringType)
    val createdOn by column(BackgroundTask::createdOn, type = IsoLocalDateTimeType)
    val createdByUserId by column(BackgroundTask::createdByUserId)
    val createdByUsername by column(BackgroundTask::createdByUsername)
    val status by column(BackgroundTask::status, type = ColumnType.enum())
    val progressPercent by column(BackgroundTask::progressPercent)
    val progress by column(BackgroundTask::progressJson, type = RawJsonStringType)
    val attempt by column(BackgroundTask::attempt)
    val leaseWorkerName by column(BackgroundTask::leaseWorkerName)
    val leaseExpiresOn by column(BackgroundTask::leaseExpiresOn, type = IsoLocalDateTimeType)
    val pendingSince by column(BackgroundTask::pendingSince, type = IsoLocalDateTimeType)
    val retryOn by column(BackgroundTask::retryOn, type = IsoLocalDateTimeType)
    val startedOn by column(BackgroundTask::startedOn, type = IsoLocalDateTimeType)
    val completedOn by column(BackgroundTask::completedOn, type = IsoLocalDateTimeType)
    val result by column(BackgroundTask::resultJson, type = RawJsonStringType)
    val error by column(BackgroundTask::errorJson, type = RawJsonStringType)
}

/**
 * Fieldsets of [BackgroundTask] entities:
 * - `default` includes all columns, including the task's result
 * - `short` concisely identifies the task, it is included in the task's domain events (without the secret task handle,
 *   as the events may be recorded, e.g. in the audit log)
 */
object BackgroundTaskFieldsets : FieldsetsAvailable<BackgroundTask>(BackgroundTaskSchema) {
    override val default by defineFieldset {
        includeAllColumns()
    }

    val short by defineFieldset {
        include(BackgroundTaskSchema.id)
        include(BackgroundTaskSchema.taskRequestClassName)
        include(BackgroundTaskSchema.createdOn)
        include(BackgroundTaskSchema.createdByUserId)
        include(BackgroundTaskSchema.createdByUsername)
    }
}
