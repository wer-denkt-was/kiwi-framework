package de.kiwi.backgroundTask.application

import de.kiwi.backgroundTask.domain.BackgroundTaskData
import de.kiwi.backgroundTask.domain.BackgroundTaskDataId
import de.kiwi.backgroundTask.domain.BackgroundTaskId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/** Repository of [BackgroundTaskData] entities, the scratchpad entries of the tasks */
@Repository
interface BackgroundTaskDataRepository : JpaRepository<BackgroundTaskData, BackgroundTaskDataId> {

    /** Find all scratchpad entries of the task */
    @Query("select d from BackgroundTaskData d where d.task.id = ?1")
    fun findAllByTaskId(taskId: BackgroundTaskId): List<BackgroundTaskData>

    /** Find the scratchpad entry of the task with the given key */
    @Query("select d from BackgroundTaskData d where d.task.id = ?1 and d.dataKey = ?2")
    fun findByTaskIdAndKey(taskId: BackgroundTaskId, dataKey: String): BackgroundTaskData?

    /** Find the keys of the task's scratchpad entries, in alphabetical order */
    @Query("select d.dataKey from BackgroundTaskData d where d.task.id = ?1 order by d.dataKey")
    fun findKeysByTaskId(taskId: BackgroundTaskId): List<String>

    /** Delete the scratchpad of the task, returning the number of deleted entries */
    @Modifying
    @Query("delete from BackgroundTaskData d where d.task.id = ?1")
    fun deleteAllByTaskId(taskId: BackgroundTaskId): Int

    /** Delete the scratchpad entry of the task with the given key, returning the number of deleted entries */
    @Modifying
    @Query("delete from BackgroundTaskData d where d.task.id = ?1 and d.dataKey = ?2")
    fun deleteByTaskIdAndKey(taskId: BackgroundTaskId, dataKey: String): Int
}
