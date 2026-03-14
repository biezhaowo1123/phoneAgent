package com.phoneagent.skill.builtin

import android.content.Context
import android.os.Environment
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.skill.Skill
import java.io.File

/** Skill: basic file system operations. */
class FileManagerSkill(private val context: Context) : Skill {

    override val id = "file"
    override val name = "文件管理"
    override val description = "读取、写入、列出文件和目录"

    private val baseDir = context.getExternalFilesDir(null)
        ?: context.filesDir

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "list",
            description = "列出目录内容",
            parameters = mapOf(
                "path" to ToolParameter("string", "相对路径，默认为根目录"),
            )
        ),
        ToolDefinition(
            name = "read",
            description = "读取文本文件内容",
            parameters = mapOf(
                "path" to ToolParameter("string", "文件相对路径", required = true),
            )
        ),
        ToolDefinition(
            name = "write",
            description = "写入文本到文件（覆盖）",
            parameters = mapOf(
                "path" to ToolParameter("string", "文件相对路径", required = true),
                "content" to ToolParameter("string", "文件内容", required = true),
            )
        ),
        ToolDefinition(
            name = "delete",
            description = "删除文件",
            parameters = mapOf(
                "path" to ToolParameter("string", "文件相对路径", required = true),
            )
        ),
        ToolDefinition(
            name = "storage-info",
            description = "获取存储空间信息",
        ),
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        return when (toolName) {
            "list" -> {
                val path = arguments["path"] ?: ""
                val dir = File(baseDir, path)
                if (!dir.exists() || !dir.isDirectory) return "目录不存在: $path"
                val files = dir.listFiles() ?: return "无法读取目录"
                files.joinToString("\n") { f ->
                    val suffix = if (f.isDirectory) "/" else " (${f.length()} bytes)"
                    "${f.name}$suffix"
                }.ifEmpty { "(空目录)" }
            }
            "read" -> {
                val path = arguments["path"] ?: return "Error: missing path"
                val file = File(baseDir, path)
                if (!file.exists()) return "文件不存在: $path"
                if (file.length() > 100_000) return "文件过大，超过100KB"
                file.readText()
            }
            "write" -> {
                val path = arguments["path"] ?: return "Error: missing path"
                val content = arguments["content"] ?: return "Error: missing content"
                val file = File(baseDir, path)
                file.parentFile?.mkdirs()
                file.writeText(content)
                "文件已写入: $path (${content.length} chars)"
            }
            "delete" -> {
                val path = arguments["path"] ?: return "Error: missing path"
                val file = File(baseDir, path)
                if (!file.exists()) return "文件不存在: $path"
                if (file.delete()) "已删除: $path"
                else "删除失败: $path"
            }
            "storage-info" -> {
                val stat = Environment.getExternalStorageDirectory()
                val total = stat.totalSpace / (1024 * 1024)
                val free = stat.freeSpace / (1024 * 1024)
                "存储空间: 总共 ${total}MB, 可用 ${free}MB"
            }
            else -> "Unknown tool: $toolName"
        }
    }
}
