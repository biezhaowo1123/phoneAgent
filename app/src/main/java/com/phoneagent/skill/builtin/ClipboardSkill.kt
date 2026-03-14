package com.phoneagent.skill.builtin

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Skill: clipboard read/write. */
class ClipboardSkill(private val context: Context) : Skill {

    override val id = "clipboard"
    override val name = "剪贴板"
    override val description = "读取和写入系统剪贴板"

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "read",
            description = "读取剪贴板内容",
        ),
        ToolDefinition(
            name = "write",
            description = "写入内容到剪贴板",
            parameters = mapOf(
                "text" to ToolParameter("string", "要复制的文本", required = true),
            )
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return withContext(Dispatchers.Main) {
            when (toolName) {
                "read" -> {
                    val clip = cm.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).text?.toString() ?: "(剪贴板为空)"
                    } else {
                        "(剪贴板为空)"
                    }
                }
                "write" -> {
                    val text = arguments["text"] ?: return@withContext "Error: missing text"
                    cm.setPrimaryClip(ClipData.newPlainText("PhoneAgent", text))
                    "已复制到剪贴板: ${text.take(50)}..."
                }
                else -> "Unknown tool: $toolName"
            }
        }
    }
}
