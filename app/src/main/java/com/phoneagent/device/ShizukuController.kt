package com.phoneagent.device

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Device controller via Shizuku.
 * Shizuku provides ADB-level shell access without root.
 *
 * Requires:
 * 1. Shizuku app installed and running
 * 2. User granted permission to this app in Shizuku
 */
class ShizukuController(private val context: Context) : DeviceController {

    override val name = "Shizuku"

    private var screenWidth = 1080
    private var screenHeight = 2400

    override val isAvailable: Boolean
        get() {
            if (!isShizukuInstalled()) return false
            return try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }

    /** Request Shizuku permission. Call from Activity. */
    fun requestPermission(requestCode: Int = 1) {
        try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Exception) {
            Log.w("ShizukuCtrl", "Cannot request Shizuku permission: ${e.message}")
        }
    }

    override suspend fun clickAt(x: Int, y: Int): Result<String> {
        return execShizuku("input tap $x $y").map { "已点击: ($x, $y)" }
    }

    override suspend fun clickByText(text: String): Result<String> {
        val dump = execShizuku("uiautomator dump /data/local/tmp/ui_dump.xml")
        if (dump.isFailure) return dump

        val result = execShizuku("cat /data/local/tmp/ui_dump.xml")
        val xml = result.getOrNull() ?: return Result.failure(Exception("无法读取UI"))

        val pattern = """text="[^"]*${Regex.escape(text)}[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""".toRegex()
        val match = pattern.find(xml)
            ?: return Result.failure(Exception("未找到包含 '$text' 的元素"))

        val (x1, y1, x2, y2) = match.destructured
        val cx = (x1.toInt() + x2.toInt()) / 2
        val cy = (y1.toInt() + y2.toInt()) / 2
        return clickAt(cx, cy)
    }

    override suspend fun longPressAt(x: Int, y: Int): Result<String> {
        return execShizuku("input swipe $x $y $x $y 1000").map { "已长按: ($x, $y)" }
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Result<String> {
        return execShizuku("input swipe $fromX $fromY $toX $toY $durationMs").map {
            "已滑动: ($fromX,$fromY) -> ($toX,$toY)"
        }
    }

    override suspend fun inputText(text: String): Result<String> {
        val escaped = text.replace(" ", "%s")
        return if (text.all { it.code < 128 }) {
            execShizuku("input text '$escaped'").map { "已输入: $text" }
        } else {
            execShizuku("am broadcast -a ADB_INPUT_TEXT --es msg '$text'").map { "已输入: $text" }
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
            "notifications" -> return execShizuku("cmd statusbar expand-notifications").map { "已打开通知栏" }
            else -> "KEYCODE_${key.uppercase()}"
        }
        return execShizuku("input keyevent $keycode").map { "已按下: $key" }
    }

    override suspend fun readScreen(): Result<String> {
        val dump = execShizuku("uiautomator dump /data/local/tmp/ui_dump.xml")
        if (dump.isFailure) {
            Log.w("ShizukuCtrl", "uiautomator dump failed: ${dump.exceptionOrNull()?.message}")
            return dump
        }
        Log.d("ShizukuCtrl", "uiautomator dump result: ${dump.getOrNull()}")

        // Small delay to let the file be written
        kotlinx.coroutines.delay(500)

        val result = execShizuku("cat /data/local/tmp/ui_dump.xml")
        val xml = result.getOrNull()
        if (xml.isNullOrEmpty() || xml == "OK") {
            Log.w("ShizukuCtrl", "UI dump file empty or not found, xml=$xml")
            return Result.failure(Exception("无法读取UI dump"))
        }
        Log.d("ShizukuCtrl", "UI dump length: ${xml.length}")
        val parsed = parseUiDump(xml)
        return Result.success(parsed)
    }

    override suspend fun screenshot(path: String): Result<String> {
        return execShizuku("screencap -p $path").map { "截图已保存: $path" }
    }

    override suspend fun runShellCommand(command: String): Result<String> {
        return execShizuku(command)
    }

    override suspend fun launchApp(packageName: String): Result<String> {
        return execShizuku("monkey -p $packageName -c android.intent.category.LAUNCHER 1").map {
            "已启动: $packageName"
        }
    }

    override suspend fun forceStopApp(packageName: String): Result<String> {
        return execShizuku("am force-stop $packageName").map { "已停止: $packageName" }
    }

    override suspend fun getScreenSize(): Pair<Int, Int> {
        val result = execShizuku("wm size")
        result.getOrNull()?.let { output ->
            val match = Regex("""(\d+)x(\d+)""").find(output)
            if (match != null) {
                screenWidth = match.groupValues[1].toInt()
                screenHeight = match.groupValues[2].toInt()
            }
        }
        return screenWidth to screenHeight
    }

    // ---------- Shizuku execution ----------

    /**
     * Execute command via Shizuku's newProcess API (private static, accessed via reflection).
     * Returns a ShizukuRemoteProcess (extends java.lang.Process).
     */
    private suspend fun execShizuku(command: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isAvailable) {
            return@withContext Result.failure(Exception("Shizuku 未授权或未运行，请检查 Shizuku 应用"))
        }

        try {
            Log.d("ShizukuCtrl", "exec: $command")
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            // Read stdout and stderr in parallel to avoid deadlock
            var output = ""
            var errorOutput = ""
            val stdoutReader = Thread {
                output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            }
            val stderrReader = Thread {
                errorOutput = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            }
            stdoutReader.start()
            stderrReader.start()

            val exitCode = process.waitFor()
            stdoutReader.join(5000)
            stderrReader.join(5000)

            Log.d("ShizukuCtrl", "exec result: exit=$exitCode, outLen=${output.length}, errLen=${errorOutput.length}")

            if (exitCode == 0) {
                Result.success(output.ifEmpty { "OK" })
            } else {
                val msg = errorOutput.ifEmpty { output }
                Log.w("ShizukuCtrl", "exec fail: exit=$exitCode, msg=$msg")
                Result.failure(Exception("Exit $exitCode: $msg"))
            }
        } catch (e: Exception) {
            Log.e("ShizukuCtrl", "Shizuku exec error: $command", e)
            Result.failure(e)
        }
    }

    private fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
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
