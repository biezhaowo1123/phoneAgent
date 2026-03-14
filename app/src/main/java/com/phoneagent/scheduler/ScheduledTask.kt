package com.phoneagent.scheduler

import androidx.room.*
import kotlinx.serialization.Serializable

@Entity(tableName = "scheduled_tasks")
@Serializable
data class ScheduledTask(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Human-readable task name */
    val name: String,

    /** The natural language command to execute */
    val command: String,

    /** Cron-like expression: "minute hour dayOfMonth month dayOfWeek"
     *  e.g., "30 8 * * *" = every day at 8:30
     *  or "0 9 * * 1-5" = weekdays at 9:00
     *  Special: "once" = run once at scheduledTime */
    val cronExpression: String = "once",

    /** For one-time tasks, the exact timestamp */
    val scheduledTime: Long = 0,

    /** Last execution time */
    val lastRunTime: Long = 0,

    /** Next scheduled run time */
    val nextRunTime: Long = 0,

    /** Is the task enabled? */
    val enabled: Boolean = true,

    /** Task creation time */
    val createdAt: Long = System.currentTimeMillis(),

    /** WorkManager work request UUID */
    val workId: String = "",

    /** Repeat mode for simple schedules */
    val repeatMode: RepeatMode = RepeatMode.ONCE,

    /** Interval in minutes for INTERVAL repeat mode */
    val intervalMinutes: Long = 0,
)

@Serializable
enum class RepeatMode {
    ONCE,          // Run once
    DAILY,         // Every day at same time
    WEEKLY,        // Every week at same time
    WEEKDAYS,      // Mon-Fri
    INTERVAL,      // Every N minutes
    CRON,          // Custom cron expression
}
