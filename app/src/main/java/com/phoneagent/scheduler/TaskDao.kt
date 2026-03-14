package com.phoneagent.scheduler

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY nextRunTime ASC")
    fun getAllTasks(): Flow<List<ScheduledTask>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 ORDER BY nextRunTime ASC")
    fun getEnabledTasks(): Flow<List<ScheduledTask>>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): ScheduledTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ScheduledTask): Long

    @Update
    suspend fun updateTask(task: ScheduledTask)

    @Delete
    suspend fun deleteTask(task: ScheduledTask)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: Long)

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND nextRunTime <= :time")
    suspend fun getDueTasks(time: Long): List<ScheduledTask>

    @Query("UPDATE scheduled_tasks SET lastRunTime = :time, nextRunTime = :nextTime WHERE id = :id")
    suspend fun updateRunTimes(id: Long, time: Long, nextTime: Long)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
