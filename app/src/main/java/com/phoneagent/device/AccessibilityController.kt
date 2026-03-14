package com.phoneagent.device

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Wraps the existing AgentAccessibilityService into the DeviceController interface.
 * This allows uniform usage across all control modes.
 */
class AccessibilityController(private val context: Context) : DeviceController {

    override val name = "无障碍服务"

    override val isAvailable: Boolean
        get() = AgentAccessibilityService.getInstance() != null

    private val a11y get() = AgentAccessibilityService.getInstance()

    override suspend fun clickAt(x: Int, y: Int): Result<String> {
        val svc = a11y ?: return unavailable()
        svc.clickAt(x.toFloat(), y.toFloat())
        return Result.success("已点击: ($x, $y)")
    }

    override suspend fun clickByText(text: String): Result<String> {
        val svc = a11y ?: return unavailable()
        return if (svc.clickByText(text)) {
            Result.success("已点击: $text")
        } else {
            Result.failure(Exception("未找到可点击的元素: $text"))
        }
    }

    override suspend fun longPressAt(x: Int, y: Int): Result<String> {
        val svc = a11y ?: return unavailable()
        svc.longPressAt(x.toFloat(), y.toFloat())
        return Result.success("已长按: ($x, $y)")
    }

    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): Result<String> {
        val svc = a11y ?: return unavailable()
        svc.swipe(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat(), durationMs.toLong())
        return Result.success("已滑动: ($fromX,$fromY) -> ($toX,$toY)")
    }

    override suspend fun inputText(text: String): Result<String> {
        val svc = a11y ?: return unavailable()
        return if (svc.inputText(text)) {
            Result.success("已输入: $text")
        } else {
            Result.failure(Exception("未找到可编辑的输入框"))
        }
    }

    override suspend fun pressKey(key: String): Result<String> {
        val svc = a11y ?: return unavailable()
        when (key) {
            "back" -> svc.pressBack()
            "home" -> svc.pressHome()
            "recents" -> svc.openRecents()
            "notifications" -> svc.openNotifications()
            "quick_settings" -> svc.openQuickSettings()
            else -> return Result.failure(Exception("不支持的按键: $key"))
        }
        return Result.success("已按下: $key")
    }

    override suspend fun readScreen(): Result<String> {
        val svc = a11y ?: return unavailable()
        return Result.success(svc.readScreen())
    }

    override suspend fun screenshot(path: String): Result<String> {
        // Accessibility service can't take screenshots via file save
        // Use shell fallback
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "screencap -p $path"))
                process.waitFor()
                Result.success("截图已保存: $path")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun runShellCommand(command: String): Result<String> {
        // Accessibility service doesn't support shell; use basic Runtime
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                Result.success(output.ifEmpty { "OK" })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun launchApp(packageName: String): Result<String> {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return Result.failure(Exception("未找到应用: $packageName"))
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return Result.success("已启动: $packageName")
    }

    override suspend fun forceStopApp(packageName: String): Result<String> {
        // Accessibility can't force stop; use shell
        return runShellCommand("am force-stop $packageName")
    }

    override suspend fun getScreenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun <T> unavailable(): Result<T> =
        Result.failure(Exception("无障碍服务未开启"))
}
