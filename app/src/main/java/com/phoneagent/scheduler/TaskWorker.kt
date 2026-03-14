package com.phoneagent.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phoneagent.PhoneAgentApp
import com.phoneagent.engine.AgentEngine

/**
 * WorkManager Worker that executes scheduled tasks.
 * Gets the command from input data and runs it through the AgentEngine.
 */
class TaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong("task_id", -1)
        val command = inputData.getString("command") ?: return Result.failure()

        Log.i("TaskWorker", "Executing task $taskId: $command")

        return try {
            val app = applicationContext as PhoneAgentApp
            val engine = AgentEngine.getInstance(app)
            engine.executeCommand(command)

            // Update scheduler
            val scheduler = TaskScheduler(applicationContext)
            if (taskId > 0) {
                scheduler.onTaskCompleted(taskId)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e("TaskWorker", "Task $taskId failed", e)
            Result.retry()
        }
    }
}
