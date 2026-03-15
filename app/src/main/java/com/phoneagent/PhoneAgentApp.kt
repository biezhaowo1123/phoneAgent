package com.phoneagent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.phoneagent.skill.SkillRegistry
import com.phoneagent.skill.builtin.*

class PhoneAgentApp : Application() {

    lateinit var skillRegistry: SkillRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Set up a global exception handler to log crashes
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("PhoneAgent", "FATAL CRASH in ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try { createNotificationChannels() } catch (e: Exception) { Log.e("PhoneAgent", "Notification channels failed", e) }
        try { initSkills() } catch (e: Exception) { Log.e("PhoneAgent", "Skill init failed", e); skillRegistry = SkillRegistry() }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_AGENT, "Agent 服务",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_TASK, "定时任务",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun initSkills() {
        skillRegistry = SkillRegistry()
        skillRegistry.register(PhoneControlSkill(this))
        skillRegistry.register(AppLauncherSkill(this))
        skillRegistry.register(NotificationSkill(this))
        skillRegistry.register(SystemSettingsSkill(this))
        skillRegistry.register(FileManagerSkill(this))
        skillRegistry.register(ClipboardSkill(this))
        skillRegistry.register(InstantMessageSkill(this))
        skillRegistry.register(AutomationFlowSkill(this))
    }

    companion object {
        lateinit var instance: PhoneAgentApp
            private set

        const val CHANNEL_AGENT = "agent_service"
        const val CHANNEL_TASK = "scheduled_tasks"
    }
}
