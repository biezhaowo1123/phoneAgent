package com.phoneagent.scheduler

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoneagent.PhoneAgentApp

/**
 * Foreground service to keep the agent alive for task execution.
 */
class AgentForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationCompat.Builder(this, PhoneAgentApp.CHANNEL_AGENT)
            .setContentTitle("PhoneAgent")
            .setContentText("AI Agent 运行中...")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
