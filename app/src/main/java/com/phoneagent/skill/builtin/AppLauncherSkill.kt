package com.phoneagent.skill.builtin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill

/** Skill: launch apps, list installed apps, open URLs. */
class AppLauncherSkill(private val context: Context) : Skill {

    override val id = "app"
    override val name = "应用管理"
    override val description = "启动应用、列出已安装应用、打开URL"

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "launch",
            description = "通过包名或应用名启动应用",
            parameters = mapOf(
                "query" to ToolParameter("string", "包名或应用名称", required = true),
            )
        ),
        ToolDefinition(
            name = "list-apps",
            description = "列出已安装的应用",
            parameters = mapOf(
                "filter" to ToolParameter("string", "过滤关键字（可选）"),
            )
        ),
        ToolDefinition(
            name = "open-url",
            description = "在浏览器中打开URL",
            parameters = mapOf(
                "url" to ToolParameter("string", "要打开的网址", required = true),
            )
        ),
        ToolDefinition(
            name = "open-settings",
            description = "打开系统设置页面",
            parameters = mapOf(
                "page" to ToolParameter(
                    "string", "设置页面",
                    enum = listOf("wifi", "bluetooth", "display", "sound", "battery", "storage", "apps", "main")
                ),
            )
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        return when (toolName) {
            "launch" -> {
                val query = arguments["query"] ?: return "Error: missing query"
                launchApp(query)
            }
            "list-apps" -> {
                val filter = arguments["filter"] ?: ""
                listApps(filter)
            }
            "open-url" -> {
                val url = arguments["url"] ?: return "Error: missing url"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "已打开: $url"
            }
            "open-settings" -> {
                val page = arguments["page"] ?: "main"
                val action = when (page) {
                    "wifi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "display" -> Settings.ACTION_DISPLAY_SETTINGS
                    "sound" -> Settings.ACTION_SOUND_SETTINGS
                    "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    else -> Settings.ACTION_SETTINGS
                }
                val intent = Intent(action).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                "已打开设置: $page"
            }
            else -> "Unknown tool: $toolName"
        }
    }

    private fun launchApp(query: String): String {
        val pm = context.packageManager

        // Try as package name first
        val launchIntent = pm.getLaunchIntentForPackage(query)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            return "已启动: $query"
        }

        // Search by app name
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val matched = apps.find {
            pm.getApplicationLabel(it).toString().contains(query, ignoreCase = true)
        }

        if (matched != null) {
            val intent = pm.getLaunchIntentForPackage(matched.packageName)
            if (intent != null) {
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                return "已启动: ${pm.getApplicationLabel(matched)}"
            }
        }

        return "未找到应用: $query"
    }

    private fun listApps(filter: String): String {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .filter { filter.isBlank() || it.first.contains(filter, true) || it.second.contains(filter, true) }
            .sortedBy { it.first }
            .take(50)

        return if (apps.isEmpty()) "没有找到匹配的应用"
        else apps.joinToString("\n") { "${it.first} (${it.second})" }
    }
}
