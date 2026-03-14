package com.phoneagent.scheduler

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Task Scheduler: manages scheduled tasks using Room + WorkManager.
 * Supports one-time, daily, weekly, weekday, interval, and cron-based schedules.
 */
class TaskScheduler(private val context: Context) {

    private val db = TaskDatabase.getInstance(context)
    private val dao = db.taskDao()
    private val workManager by lazy {
        try { WorkManager.getInstance(context) } catch (e: Exception) {
            android.util.Log.e("TaskScheduler", "WorkManager not initialized", e)
            null
        }
    }

    val allTasks: Flow<List<ScheduledTask>> = dao.getAllTasks()
    val enabledTasks: Flow<List<ScheduledTask>> = dao.getEnabledTasks()

    /** Create and schedule a new task. Returns the task ID. */
    suspend fun createTask(
        name: String,
        command: String,
        repeatMode: RepeatMode = RepeatMode.ONCE,
        scheduledTime: Long = 0,
        intervalMinutes: Long = 0,
        cronExpression: String = "",
    ): Long {
        val nextRun = calculateNextRunTime(repeatMode, scheduledTime, intervalMinutes, cronExpression)

        val task = ScheduledTask(
            name = name,
            command = command,
            cronExpression = cronExpression,
            scheduledTime = scheduledTime,
            nextRunTime = nextRun,
            repeatMode = repeatMode,
            intervalMinutes = intervalMinutes,
        )

        val id = dao.insertTask(task)
        scheduleWork(task.copy(id = id))
        return id
    }

    /** Delete a task and cancel its WorkManager job. */
    suspend fun deleteTask(taskId: Long) {
        val task = dao.getTaskById(taskId) ?: return
        if (task.workId.isNotEmpty()) {
            try { workManager?.cancelWorkById(UUID.fromString(task.workId)) } catch (_: Exception) {}
        }
        dao.deleteTaskById(taskId)
    }

    /** Toggle task enabled state. */
    suspend fun setTaskEnabled(taskId: Long, enabled: Boolean) {
        dao.setEnabled(taskId, enabled)
        val task = dao.getTaskById(taskId) ?: return
        if (enabled) {
            val nextRun = calculateNextRunTime(task.repeatMode, task.scheduledTime, task.intervalMinutes, task.cronExpression)
            dao.updateRunTimes(taskId, task.lastRunTime, nextRun)
            scheduleWork(task.copy(nextRunTime = nextRun, enabled = true))
        } else if (task.workId.isNotEmpty()) {
            try { workManager?.cancelWorkById(UUID.fromString(task.workId)) } catch (_: Exception) {}
        }
    }

    /** Called when a task finishes. Updates run times and reschedules if repeating. */
    suspend fun onTaskCompleted(taskId: Long) {
        val task = dao.getTaskById(taskId) ?: return
        val now = System.currentTimeMillis()

        if (task.repeatMode == RepeatMode.ONCE) {
            dao.setEnabled(taskId, false)
            dao.updateRunTimes(taskId, now, 0)
        } else {
            val nextRun = calculateNextRunTime(task.repeatMode, task.scheduledTime, task.intervalMinutes, task.cronExpression)
            dao.updateRunTimes(taskId, now, nextRun)
            scheduleWork(task.copy(lastRunTime = now, nextRunTime = nextRun))
        }
    }

    /** Get tasks that are due for execution. */
    suspend fun getDueTasks(): List<ScheduledTask> {
        return dao.getDueTasks(System.currentTimeMillis())
    }

    // ---------- WorkManager Integration ----------

    private suspend fun scheduleWork(task: ScheduledTask) {
        val delay = (task.nextRunTime - System.currentTimeMillis()).coerceAtLeast(0)

        val data = Data.Builder()
            .putLong("task_id", task.id)
            .putString("command", task.command)
            .build()

        val request = if (task.repeatMode == RepeatMode.INTERVAL && task.intervalMinutes > 0) {
            val safeInterval = task.intervalMinutes.coerceAtLeast(15)
            PeriodicWorkRequestBuilder<TaskWorker>(
                safeInterval, TimeUnit.MINUTES
            )
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("task_${task.id}")
                .build()
        } else {
            OneTimeWorkRequestBuilder<TaskWorker>()
                .setInputData(data)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .addTag("task_${task.id}")
                .build()
        }

        workManager?.enqueueUniqueWork(
            "task_${task.id}",
            ExistingWorkPolicy.REPLACE,
            if (request is OneTimeWorkRequest) request
            else {
                // For periodic, use enqueueUniquePeriodicWork
                workManager?.cancelUniqueWork("task_${task.id}")
                val periodicRequest = request as PeriodicWorkRequest
                workManager?.enqueueUniquePeriodicWork(
                    "task_${task.id}",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest
                )
                return
            }
        )

        dao.updateTask(task.copy(workId = request.id.toString()))
    }

    // ---------- Time Calculation ----------

    private fun calculateNextRunTime(
        mode: RepeatMode,
        scheduledTime: Long,
        intervalMinutes: Long,
        cronExpression: String,
    ): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        return when (mode) {
            RepeatMode.ONCE -> {
                if (scheduledTime > now) scheduledTime else now + 60_000
            }
            RepeatMode.DAILY -> {
                cal.timeInMillis = if (scheduledTime > 0) scheduledTime else now
                val targetHour = cal.get(Calendar.HOUR_OF_DAY)
                val targetMinute = cal.get(Calendar.MINUTE)
                cal.timeInMillis = now
                cal.set(Calendar.HOUR_OF_DAY, targetHour)
                cal.set(Calendar.MINUTE, targetMinute)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            RepeatMode.WEEKLY -> {
                cal.timeInMillis = if (scheduledTime > 0) scheduledTime else now
                val targetDay = cal.get(Calendar.DAY_OF_WEEK)
                val targetHour = cal.get(Calendar.HOUR_OF_DAY)
                val targetMinute = cal.get(Calendar.MINUTE)
                cal.timeInMillis = now
                cal.set(Calendar.DAY_OF_WEEK, targetDay)
                cal.set(Calendar.HOUR_OF_DAY, targetHour)
                cal.set(Calendar.MINUTE, targetMinute)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now) cal.add(Calendar.WEEK_OF_YEAR, 1)
                cal.timeInMillis
            }
            RepeatMode.WEEKDAYS -> {
                cal.timeInMillis = if (scheduledTime > 0) scheduledTime else now
                val targetHour = cal.get(Calendar.HOUR_OF_DAY)
                val targetMinute = cal.get(Calendar.MINUTE)
                cal.timeInMillis = now
                cal.set(Calendar.HOUR_OF_DAY, targetHour)
                cal.set(Calendar.MINUTE, targetMinute)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                // Skip weekends
                while (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY ||
                    cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
                    cal.add(Calendar.DAY_OF_MONTH, 1)
                }
                cal.timeInMillis
            }
            RepeatMode.INTERVAL -> {
                now + (intervalMinutes * 60_000)
            }
            RepeatMode.CRON -> {
                parseCronNextRun(cronExpression, now)
            }
        }
    }

    /** Simple cron parser: "minute hour dayOfMonth month dayOfWeek" */
    private fun parseCronNextRun(cron: String, from: Long): Long {
        val parts = cron.trim().split("\\s+".toRegex())
        if (parts.size < 5) return from + 3600_000 // fallback: 1 hour

        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        cal.add(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // Try up to 366 days ahead
        repeat(366 * 24 * 60) {
            val minute = cal.get(Calendar.MINUTE)
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
            val month = cal.get(Calendar.MONTH) + 1
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday

            if (matchCronField(parts[0], minute) &&
                matchCronField(parts[1], hour) &&
                matchCronField(parts[2], dayOfMonth) &&
                matchCronField(parts[3], month) &&
                matchCronField(parts[4], dayOfWeek)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
        }

        return from + 3600_000
    }

    private fun matchCronField(pattern: String, value: Int): Boolean {
        if (pattern == "*") return true
        // Comma-separated values
        return pattern.split(",").any { part ->
            when {
                part.contains("-") -> {
                    val (start, end) = part.split("-").map { it.trim().toInt() }
                    value in start..end
                }
                part.contains("/") -> {
                    val step = part.split("/")[1].trim().toInt()
                    value % step == 0
                }
                else -> part.trim().toIntOrNull() == value
            }
        }
    }
}
