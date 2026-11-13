package de.kiwi.backgroundTask.domain

import de.kiwi.api.domain.EntityId
import de.kiwi.api.domain.PersistableBaseEntity
import jakarta.persistence.*
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Clock
import java.time.LocalDateTime

@Embeddable
class BackgroundTaskDataId(value: Long) : EntityId(value)

/**
 * Entry of the scratchpad of a [BackgroundTask]: A value stored as JSON for a key, unique per task.
 * @see de.kiwi.api.backgroundTask.BackgroundTaskScratchpad
 */
@Entity
@Table(
    name = "kiwi_background_task_data",
    uniqueConstraints = [
        UniqueConstraint(
            name = "unique_background_task_data_key",
            columnNames = ["task_id", "data_key"],
        ),
    ],
)
class BackgroundTaskData(
    id: BackgroundTaskDataId,
    task: BackgroundTask,
    dataKey: String,
    valueJson: String?,
    updatedOn: LocalDateTime,
) : PersistableBaseEntity<BackgroundTaskDataId>(id) {

    /** Task owning the scratchpad entry */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    val task: BackgroundTask = task

    /** Key of the entry, unique per task */
    @Column(name = "data_key", nullable = false, updatable = false, length = 255)
    val dataKey: String = dataKey

    /** Value as JSON, `null` for a `null` value */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data_value", nullable = true)
    var valueJson: String? = valueJson
        protected set

    /** When the value has been stored */
    @Column(name = "updated_on", nullable = false)
    var updatedOn: LocalDateTime = updatedOn
        protected set

    override fun getId() = super.id

    /** Replace the value */
    fun update(valueJson: String?, clock: Clock) {
        this.valueJson = valueJson
        updatedOn = LocalDateTime.now(clock)
    }

    companion object {
        /** Create a new entry of the given task */
        fun create(
            task: BackgroundTask,
            dataKey: String,
            valueJson: String?,
            clock: Clock,
        ) = BackgroundTaskData(
            id = EntityId.generate(),
            task = task,
            dataKey = dataKey,
            valueJson = valueJson,
            updatedOn = LocalDateTime.now(clock),
        ).also { data ->
            data.markAsNew()
        }
    }
}
