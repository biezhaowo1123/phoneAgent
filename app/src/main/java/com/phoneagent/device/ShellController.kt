package com.phoneagent.device

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Device controller via shell commands.
 * Works with both regular shell (limited) and root shell (su).
 * When connected via ADB wireless debugging, many commands also work.
 */
class ShellController(
    private val context: Context,
    private val useRoot: Boolean = false,
) : DeviceController {

    override val name = if (useRoot) "Root Shell" else "Shell"

    override val isAvailable: Boolean
        get() = if (useRoot) checkRootAccess() else true

    private var screenWidth = 1080
    private var screenHeight = 2400

    override suspend fun clickAt(x: Int, y: Int): Result<String> {
        return exec("input tap $x $y").map { "已点击: ($x, $y)" }
    }

    override suspend fun clickByText(text: String): Result<String> {
        // Shell mode doesn't directly support text-based clicking.
        // Use uiautomator dump + parse as a workaround.
        val dumpResult = exec("uiautomator dump /data/local/tmp/ui_dump.xml")
        if (dumpResult.isFailure) return Result.failure(Exception("无法获取UI树"))

        val readResult = exec("cat /data/local/tmp/ui_dump.xml")
        val xml = readResult.getOrNull() ?: return Result.failure(Exception("无法读取UI转储"))

        // Parse bounds for matching text
        val pattern = """text="[^"]*${Regex.escape(text)}[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex()
        val match = pattern.find(xml)
            ?: return Result.failure(Exception("未找到包含 '$text' 的元素"))

        val (x1, y1, x2, y2) = match.destructured
        val cx = (x1.toInt() + x2.toInt()) / 2
        val cy = (y1.toInt() + y2.toInt()) / 2
        return clickAt(cx, cy)
    }

    override suspend fun longPressAt(x: Int, y: Int): Result<String> {
        return exec("input swipe $x $y $x $y 1000").map { "已长按: ($x, $y)" }
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Result<String> {
        return exec("input swipe $fromX $fromY $toX $toY $durationMs").map {
            "已滑动: ($fromX,$fromY) -> ($toX,$toY)"
        }
    }

    override suspend fun inputText(text: String): Result<String> {
        // Shell input text doesn't handle CJK well, use broadcast for that
        val escaped = text.replace(" ", "%s").replace("'", "\\'")
        return if (text.all { it.code < 128 }) {
            exec("input text '$escaped'").map { "已输入: $text" }
        } else {
            // For non-ASCII (Chinese etc.), use ADB broadcast or clipboard approach
            exec("am broadcast -a ADB_INPUT_TEXT --es msg '$text'").map { "已输入: $text" }
        }
    }

    override suspend fun pressKey(key: String): Result<String> {
        val keycode = when (key) {
            "back" -> "KEYCODE_BACK"
            "home" -> "KEYCODE_HOME"
            "recents" -> "KEYCODE_APP_SWITCH"
            "power" -> "KEYCODE_POWER"
            "volume_up" -> "KEYCODE_VOLUME_UP"
            "volume_down" -> "KEYCODE_VOLUME_DOWN"
            "enter" -> "KEYCODE_ENTER"
            "delete" -> "KEYCODE_DEL"
            "tab" -> "KEYCODE_TAB"
            "menu" -> "KEYCODE_MENU"
            "notifications" -> return exec("cmd statusbar expand-notifications").map { "已打开通知栏" }
            else -> "KEYCODE_${key.uppercase()}"
        }
        return exec("input keyevent $keycode").map { "已按下: $key" }
    }

    override suspend fun readScreen(): Result<String> {
        // Dump UI hierarchy via uiautomator
        val dump = exec("uiautomator dump /data/local/tmp/ui_dump.xml")
        if (dump.isFailure) return dump

        val result = exec("cat /data/local/tmp/ui_dump.xml")
        val xml = result.getOrNull() ?: return Result.failure(Exception("无法读取UI"))

        // Parse XML to readable text
        return Result.success(parseUiDump(xml))
    }

    override suspend fun screenshot(path: String): Result<String> {
        return exec("screencap -p $path").map { "截图已保存: $path" }
    }

    override suspend fun runShellCommand(command: String): Result<String> {
        return exec(command)
    }

    override suspend fun launchApp(packageName: String): Result<String> {
        return exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1").map {
            "已启动: $packageName"
        }
    }

    override suspend fun forceStopApp(packageName: String): Result<String> {
        return exec("am force-stop $packageName").map { "已停止: $packageName" }
    }

    override suspend fun getScreenSize(): Pair<Int, Int> {
        val result = exec("wm size")
        result.getOrNull()?.let { output ->
            val match = Regex("""(\d+)x(\d+)""").find(output)
            if (match != null) {
                screenWidth = match.groupValues[1].toInt()
                screenHeight = match.groupValues[2].toInt()
            }
        }
        return screenWidth to screenHeight
    }

    // ---------- Shell Execution ----------

    private suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val process = if (useRoot) {
                Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            } else {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            }

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0 || error.isEmpty()) {
                Result.success(output.ifEmpty { "OK" })
            } else {
                Log.w("ShellCtrl", "Command failed: $command -> $error")
                Result.failure(Exception(error))
            }
        } catch (e: Exception) {
            Log.e("ShellCtrl", "Shell exec error: $command", e)
            Result.failure(e)
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun parseUiDump(xml: String): String {
        val sb = StringBuilder()
        val nodePattern = """<node[^>]*""".toRegex()
        nodePattern.findAll(xml).forEach { match ->
            val attrs = match.value
            val text = extractAttr(attrs, "text")
            val desc = extractAttr(attrs, "content-desc")
            val cls = extractAttr(attrs, "class")?.substringAfterLast('.') ?: ""
            val clickable = extractAttr(attrs, "clickable") == "true"
            val bounds = extractAttr(attrs, "bounds") ?: ""

            if (text?.isNotEmpty() == true || desc?.isNotEmpty() == true || clickable) {
                val clickMark = if (clickable) "[可点击]" else ""
                val display = (text ?: desc ?: "").take(80)
                // Parse center coords from bounds "[x1,y1][x2,y2]"
                val coordMatch = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""").find(bounds)
                val coords = coordMatch?.let {
                    val cx = (it.groupValues[1].toInt() + it.groupValues[3].toInt()) / 2
                    val cy = (it.groupValues[2].toInt() + it.groupValues[4].toInt()) / 2
                    "@[$cx,$cy]"
                } ?: ""
                sb.appendLine("$cls $clickMark \"$display\" $coords")
            }
        }
        return sb.toString().ifEmpty { "(屏幕内容为空)" }
    }

    private fun extractAttr(attrs: String, name: String): String? {
        val pattern = """$name="([^"]*)"""".toRegex()
        return pattern.find(attrs)?.groupValues?.get(1)
    }
}
