package com.phoneagent.skill.builtin

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoneagent.PhoneAgentApp
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill

/** Skill: send notifications to the user. */
class NotificationSkill(private val context: Context) : Skill {

    override val id = "notify"
    override val name = "通知管理"
    override val description = "发送系统通知提醒用户"

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "send",
            description = "发送一条系统通知",
            parameters = mapOf(
                "title" to ToolParameter("string", "通知标题", required = true),
                "content" to ToolParameter("string", "通知内容", required = true),
                "priority" to ToolParameter("string", "优先级", enum = listOf("low", "normal", "high")),
            )
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        if (toolName != "send") return "Unknown tool: $toolName"

        val title = arguments["title"] ?: return "Error: missing title"
        val content = arguments["content"] ?: return "Error: missing content"
        val priority = when (arguments["priority"]) {
            "high" -> NotificationCompat.PRIORITY_HIGH
            "low" -> NotificationCompat.PRIORITY_LOW
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, PhoneAgentApp.CHANNEL_TASK)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(priority)
            .setAutoCancel(true)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
        return "通知已发送: $title"
    }
}
