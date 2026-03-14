package com.phoneagent.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Boot receiver: re-schedules all enabled tasks after device reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            CoroutineScope(Dispatchers.IO).launch {
                val scheduler = TaskScheduler(context)
                val tasks = scheduler.enabledTasks.first()
                tasks.forEach { task ->
                    scheduler.setTaskEnabled(task.id, true) // re-schedules
                }
            }
        }
    }
}
