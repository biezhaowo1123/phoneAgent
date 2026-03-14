package com.phoneagent.skill.builtin

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill

/** Skill: adjust system settings like volume, brightness, etc. */
class SystemSettingsSkill(private val context: Context) : Skill {

    override val id = "system"
    override val name = "系统设置"
    override val description = "调节音量、亮度、查看电池电量等"

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "set-volume",
            description = "设置媒体音量 (0-15)",
            parameters = mapOf(
                "level" to ToolParameter("string", "音量级别 0-15", required = true),
            )
        ),
        ToolDefinition(
            name = "set-brightness",
            description = "设置屏幕亮度 (0-255)",
            parameters = mapOf(
                "level" to ToolParameter("string", "亮度级别 0-255", required = true),
            )
        ),
        ToolDefinition(
            name = "get-battery",
            description = "获取当前电池电量",
        ),
        ToolDefinition(
            name = "set-ringer-mode",
            description = "设置响铃模式",
            parameters = mapOf(
                "mode" to ToolParameter("string", "模式", required = true,
                    enum = listOf("normal", "silent", "vibrate")),
            )
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (toolName) {
            "set-volume" -> {
                val level = arguments["level"]?.toIntOrNull() ?: return "Error: invalid level"
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    level.coerceIn(0, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)),
                    0
                )
                "音量已设置为 $level"
            }
            "set-brightness" -> {
                val level = arguments["level"]?.toIntOrNull() ?: return "Error: invalid level"
                try {
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                    )
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                        level.coerceIn(0, 255)
                    )
                    "亮度已设置为 $level"
                } catch (e: Exception) {
                    "需要修改系统设置权限: ${e.message}"
                }
            }
            "get-battery" -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
                val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging = bm.isCharging
                "电池电量: ${level}%${if (charging) " (充电中)" else ""}"
            }
            "set-ringer-mode" -> {
                val mode = when (arguments["mode"]) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                audioManager.ringerMode = mode
                "铃声模式已设置为: ${arguments["mode"]}"
            }
            else -> "Unknown tool: $toolName"
        }
    }
}
