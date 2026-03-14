package com.phoneagent.skill.builtin

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill

/** Skill: phone call, SMS, alarm, brightness, volume, etc. */
class PhoneControlSkill(private val context: Context) : Skill {

    override val id = "phone"
    override val name = "手机控制"
    override val description = "拨打电话、发送短信、设置闹钟、调节亮度和音量"

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "call",
            description = "拨打电话",
            parameters = mapOf(
                "number" to ToolParameter("string", "电话号码", required = true),
            )
        ),
        ToolDefinition(
            name = "sms",
            description = "发送短信",
            parameters = mapOf(
                "number" to ToolParameter("string", "电话号码", required = true),
                "message" to ToolParameter("string", "短信内容", required = true),
            )
        ),
        ToolDefinition(
            name = "set-alarm",
            description = "设置闹钟",
            parameters = mapOf(
                "hour" to ToolParameter("string", "小时 (0-23)", required = true),
                "minute" to ToolParameter("string", "分钟 (0-59)", required = true),
                "label" to ToolParameter("string", "闹钟标签"),
            )
        ),
        ToolDefinition(
            name = "set-timer",
            description = "设置倒计时",
            parameters = mapOf(
                "seconds" to ToolParameter("string", "秒数", required = true),
                "label" to ToolParameter("string", "标签"),
            )
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        return when (toolName) {
            "call" -> {
                val number = arguments["number"] ?: return "Error: missing number"
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "正在拨打 $number"
            }
            "sms" -> {
                val number = arguments["number"] ?: return "Error: missing number"
                val message = arguments["message"] ?: return "Error: missing message"
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", message)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "已打开短信发送界面: $number"
            }
            "set-alarm" -> {
                val hour = arguments["hour"]?.toIntOrNull() ?: return "Error: invalid hour"
                val minute = arguments["minute"]?.toIntOrNull() ?: return "Error: invalid minute"
                val label = arguments["label"] ?: "PhoneAgent 闹钟"
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "已设置闹钟: ${hour}:${minute.toString().padStart(2, '0')} - $label"
            }
            "set-timer" -> {
                val seconds = arguments["seconds"]?.toIntOrNull() ?: return "Error: invalid seconds"
                val label = arguments["label"] ?: "PhoneAgent 计时器"
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "已设置倒计时: ${seconds}秒 - $label"
            }
            else -> "Unknown tool: $toolName"
        }
    }
}
