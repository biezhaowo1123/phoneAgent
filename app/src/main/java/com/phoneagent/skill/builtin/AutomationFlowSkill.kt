package com.phoneagent.skill.builtin

import android.content.Context
import android.util.Log
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.device.AgentAccessibilityService
import com.phoneagent.device.DeviceController
import com.phoneagent.engine.AgentEngine
import com.phoneagent.skill.Skill
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * Skill: Local on-device automation flow executor.
 * Executes JSON steps directly on phone via existing device controllers.
 */
class AutomationFlowSkill(private val context: Context) : Skill {

    override val id = "flow"
    override val name = "本地流程执行"
    override val description = "在手机本地按JSON步骤执行点击/输入/滑动/按键/断言，支持超时与重试"

    private val deviceManager get() = AgentEngine.getInstance(context).deviceManager
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "run_workflow",
            description = "执行本地自动化流程JSON。所有动作在手机端执行，无需电脑。",
            parameters = mapOf(
                "script_json" to ToolParameter(
                    type = "string",
                    description = "流程JSON，格式: {\"steps\":[{\"action\":\"launch_app\",\"package\":\"com.tencent.mm\"}, ...]}",
                    required = true
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "执行模式：precise(精准)、balanced(平衡)、fast(极速)",
                    enum = listOf("precise", "balanced", "fast")
                ),
                "timeout_ms" to ToolParameter(
                    type = "string",
                    description = "整条流程超时时间（毫秒，可选，默认45000）"
                ),
                "continue_on_error" to ToolParameter(
                    type = "string",
                    description = "失败是否继续执行后续步骤（true/false，可选）"
                ),
            )
        )
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        return when (toolName) {
            "run_workflow" -> runWorkflowTool(arguments)
            else -> "Unknown tool: $toolName"
        }
    }

    private suspend fun runWorkflowTool(arguments: Map<String, String>): String {
        val raw = arguments["script_json"]?.trim().orEmpty()
        if (raw.isBlank()) return "Error: missing script_json"

        val mode = parseMode(arguments["mode"])
        val globalTimeout = arguments["timeout_ms"]?.toLongOrNull()?.coerceIn(3_000L, 180_000L) ?: 45_000L
        val argContinueOnError = parseBoolean(arguments["continue_on_error"])

        val spec = try {
            json.decodeFromString(FlowSpec.serializer(), raw)
        } catch (e: Exception) {
            return "Error: script_json 解析失败 - ${e.message}"
        }

        if (spec.steps.isEmpty()) return "Error: workflow steps is empty"

        val defaults = spec.defaults ?: FlowDefaults()
        val defaultStepTimeout = defaults.stepTimeoutMs?.coerceIn(400L, 30_000L) ?: 6_000L
        val defaultRetries = (defaults.retries ?: 0).coerceIn(0, 6)
        val defaultRetryDelay = defaults.retryDelayMs?.coerceIn(0L, 5_000L) ?: 200L
        val continueOnErrorGlobal = argContinueOnError ?: defaults.continueOnError ?: false

        return withTimeoutOrNull(globalTimeout) {
            executeFlow(
                spec = spec,
                mode = mode,
                defaultStepTimeout = defaultStepTimeout,
                defaultRetries = defaultRetries,
                defaultRetryDelay = defaultRetryDelay,
                continueOnErrorGlobal = continueOnErrorGlobal,
            )
        } ?: "Error: workflow timeout (${globalTimeout}ms)"
    }

    private suspend fun executeFlow(
        spec: FlowSpec,
        mode: ExecuteMode,
        defaultStepTimeout: Long,
        defaultRetries: Int,
        defaultRetryDelay: Long,
        continueOnErrorGlobal: Boolean,
    ): String {
        var ok = 0
        var fail = 0
        val logs = mutableListOf<String>()

        for ((index, step) in spec.steps.withIndex()) {
            val stepNo = index + 1
            val actionName = step.action.trim().ifBlank { "unknown" }
            val retries = (step.retries ?: defaultRetries).coerceIn(0, 6)
            val retryDelay = (step.retryDelayMs ?: defaultRetryDelay).coerceIn(0L, 5_000L)
            val stepTimeout = (step.timeoutMs ?: defaultStepTimeout).coerceIn(400L, 30_000L)
            val continueOnError = step.continueOnError ?: continueOnErrorGlobal

            var success: String? = null
            var lastError = "unknown"

            for (attempt in 0..retries) {
                val result = withTimeoutOrNull(stepTimeout) {
                    performStep(step, mode)
                } ?: Result.failure(Exception("step timeout ${stepTimeout}ms"))

                if (result.isSuccess) {
                    success = result.getOrNull().orEmpty().ifBlank { "OK" }
                    break
                }

                lastError = result.exceptionOrNull()?.message ?: "unknown"
                if (attempt < retries) {
                    delay(retryDelay)
                }
            }

            if (success != null) {
                ok++
                logs.add("[OK] step#$stepNo $actionName -> $success")
            } else {
                fail++
                logs.add("[FAIL] step#$stepNo $actionName -> $lastError")
                if (!continueOnError) {
                    return buildString {
                        appendLine("Error: workflow stopped at step#$stepNo ($actionName)")
                        appendLine("Reason: $lastError")
                        appendLine()
                        appendLine("Recent logs:")
                        logs.takeLast(10).forEach { appendLine(it) }
                    }.trimEnd()
                }
            }
        }

        return buildString {
            appendLine("Workflow finished: success=$ok, failed=$fail")
            appendLine("Recent logs:")
            logs.takeLast(12).forEach { appendLine(it) }
        }.trimEnd()
    }

    private suspend fun performStep(step: FlowStep, mode: ExecuteMode): Result<String> {
        return when (normalizeAction(step.action)) {
            "sleep", "wait", "wait_ms" -> {
                val ms = (step.waitMs ?: 200L).coerceIn(0L, 30_000L)
                delay(ms)
                Result.success("waited ${ms}ms")
            }

            "launch_app", "open_app" -> {
                val pkg = step.packageName?.trim().orEmpty()
                if (pkg.isBlank()) return Result.failure(Exception("missing package"))
                runOnControllers(mode, "launch_app") { ctrl -> ctrl.launchApp(pkg) }
            }

            "stop_app", "force_stop_app" -> {
                val pkg = step.packageName?.trim().orEmpty()
                if (pkg.isBlank()) return Result.failure(Exception("missing package"))
                runOnControllers(mode, "stop_app", shellPreferred = true) { ctrl -> ctrl.forceStopApp(pkg) }
            }

            "click_text" -> {
                val text = step.text?.trim().orEmpty()
                if (text.isBlank()) return Result.failure(Exception("missing text"))

                val bottomOnly = step.bottomOnly == true
                if (bottomOnly) {
                    val a11y = AgentAccessibilityService.getInstance()
                    val ratio = (step.minYRatio ?: 0.72).coerceIn(0.3, 0.98).toFloat()
                    if (a11y?.clickByTextNearBottom(text, ratio) == true) {
                        return Result.success("clicked text near bottom: $text")
                    }
                }
                runOnControllers(mode, "click_text", uiPreferred = true) { ctrl -> ctrl.clickByText(text) }
            }

            "click_xy", "tap_xy", "tap" -> {
                val x = step.x ?: return Result.failure(Exception("missing x"))
                val y = step.y ?: return Result.failure(Exception("missing y"))
                runOnControllers(mode, "click_xy", uiPreferred = true) { ctrl -> ctrl.clickAt(x, y) }
            }

            "input_text", "type_text", "type" -> {
                val text = step.text ?: return Result.failure(Exception("missing text"))
                runOnControllers(mode, "input_text", shellPreferred = false) { ctrl ->
                    val primary = ctrl.inputText(text)
                    if (primary.isSuccess) primary else ctrl.runShellCommand(buildShellInputCommand(text))
                }
            }

            "press", "press_key" -> {
                val key = step.key?.trim().orEmpty()
                if (key.isBlank()) return Result.failure(Exception("missing key"))
                runOnControllers(mode, "press", shellPreferred = false) { ctrl -> ctrl.pressKey(key) }
            }

            "swipe", "swipe_direction", "swipe_xy" -> {
                if (step.fromX != null && step.fromY != null && step.toX != null && step.toY != null) {
                    val durationMs = (step.durationMs ?: 300).coerceIn(80, 2_500)
                    return runOnControllers(mode, "swipe_xy", uiPreferred = true) { ctrl ->
                        ctrl.swipe(step.fromX, step.fromY, step.toX, step.toY, durationMs)
                    }
                }

                val direction = step.direction?.trim()?.lowercase()
                    ?: return Result.failure(Exception("missing direction or swipe coordinates"))
                swipeByDirection(direction, mode, (step.durationMs ?: 300).coerceIn(80, 2_500))
            }

            "shell", "run_shell" -> {
                val command = step.command?.trim().orEmpty()
                if (command.isBlank()) return Result.failure(Exception("missing command"))
                runOnControllers(mode, "shell", shellPreferred = true) { ctrl -> ctrl.runShellCommand(command) }
            }

            "screenshot" -> {
                val path = step.path ?: "/sdcard/Pictures/flow_${System.currentTimeMillis()}.png"
                runOnControllers(mode, "screenshot", shellPreferred = true) { ctrl -> ctrl.screenshot(path) }
            }

            "assert_contains", "assert_text" -> {
                val expected = step.expectText?.trim().orEmpty()
                if (expected.isBlank()) return Result.failure(Exception("missing expect_text"))
                val screen = readScreen(mode)
                if (screen.contains(expected)) {
                    Result.success("assert ok: contains '$expected'")
                } else {
                    Result.failure(Exception("assert failed: '$expected' not found"))
                }
            }

            else -> Result.failure(Exception("unsupported action: ${step.action}"))
        }
    }

    private suspend fun swipeByDirection(
        direction: String,
        mode: ExecuteMode,
        durationMs: Int,
    ): Result<String> {
        val (w, h) = getScreenSize(mode)
        val cx = w / 2
        val cy = h / 2
        val offset = min(w, h) / 3

        return runOnControllers(mode, "swipe_direction", uiPreferred = true) { ctrl ->
            when (direction) {
                "up" -> ctrl.swipe(cx, cy + offset, cx, cy - offset, durationMs)
                "down" -> ctrl.swipe(cx, cy - offset, cx, cy + offset, durationMs)
                "left" -> ctrl.swipe(cx + offset, cy, cx - offset, cy, durationMs)
                "right" -> ctrl.swipe(cx - offset, cy, cx + offset, cy, durationMs)
                else -> Result.failure(Exception("unsupported direction: $direction"))
            }
        }
    }

    private suspend fun readScreen(mode: ExecuteMode): String {
        val a11y = AgentAccessibilityService.getInstance()?.readScreen().orEmpty()
        if (a11y.isNotBlank()) return a11y

        val shellText = runOnControllers(mode, "read_screen", shellPreferred = true) { ctrl ->
            ctrl.readScreen()
        }.getOrNull().orEmpty()
        return if (shellText.isNotBlank()) shellText else a11y
    }

    private suspend fun getScreenSize(mode: ExecuteMode): Pair<Int, Int> {
        for (ctrl in controllersFor(mode)) {
            if (!ctrl.isAvailable) continue
            return try {
                ctrl.getScreenSize()
            } catch (_: Exception) {
                1080 to 2340
            }
        }
        return 1080 to 2340
    }

    private fun controllersFor(
        mode: ExecuteMode,
        shellPreferred: Boolean = false,
        uiPreferred: Boolean = false,
    ): List<DeviceController> {
        val base = deviceManager.getControllersForRetry(shellPreferred)
        val ordered = if (uiPreferred) {
            val a11yFirst = base.filter { isAccessibilityController(it) }
            val others = base.filterNot { isAccessibilityController(it) }
            a11yFirst + others
        } else {
            base
        }

        return when (mode) {
            ExecuteMode.FAST -> listOf(ordered.firstOrNull() ?: deviceManager.getActiveController())
            ExecuteMode.BALANCED -> ordered.take(2)
            ExecuteMode.PRECISE -> ordered
        }.distinctBy { it.name }
    }

    private suspend fun runOnControllers(
        mode: ExecuteMode,
        actionName: String,
        shellPreferred: Boolean = false,
        uiPreferred: Boolean = false,
        block: suspend (DeviceController) -> Result<String>,
    ): Result<String> {
        var lastError: Throwable = Exception("$actionName failed")
        val controllers = controllersFor(mode, shellPreferred, uiPreferred)

        for (ctrl in controllers) {
            if (!ctrl.isAvailable) continue
            val result = try {
                block(ctrl)
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (result.isSuccess) return result

            lastError = result.exceptionOrNull() ?: lastError
            if (mode == ExecuteMode.FAST) break
            Log.w("AutomationFlowSkill", "$actionName failed on ${ctrl.name}: ${lastError.message}")
        }
        return Result.failure(lastError)
    }

    private fun isAccessibilityController(ctrl: DeviceController): Boolean {
        return ctrl.name.contains("无障碍") || ctrl.name.contains("accessibility", ignoreCase = true)
    }

    private fun normalizeAction(action: String): String {
        return action.trim().lowercase().replace("-", "_")
    }

    private fun buildShellInputCommand(text: String): String {
        val escaped = text.replace("'", "'\\''")
        return if (text.all { it.code < 128 }) {
            "input text '${escaped.replace(" ", "%s")}'"
        } else {
            "am broadcast -a ADB_INPUT_TEXT --es msg '$escaped'"
        }
    }

    private fun parseMode(raw: String?): ExecuteMode {
        return when (raw?.trim()?.lowercase()) {
            "fast", "极速" -> ExecuteMode.FAST
            "balanced", "平衡" -> ExecuteMode.BALANCED
            else -> ExecuteMode.PRECISE
        }
    }

    private fun parseBoolean(raw: String?): Boolean? {
        return when (raw?.trim()?.lowercase()) {
            "true", "1", "yes", "y", "on", "是", "开启" -> true
            "false", "0", "no", "n", "off", "否", "关闭" -> false
            else -> null
        }
    }

    private enum class ExecuteMode { PRECISE, BALANCED, FAST }

    @Serializable
    private data class FlowSpec(
        val steps: List<FlowStep> = emptyList(),
        val defaults: FlowDefaults? = null,
    )

    @Serializable
    private data class FlowDefaults(
        @SerialName("step_timeout_ms")
        val stepTimeoutMs: Long? = null,
        val retries: Int? = null,
        @SerialName("retry_delay_ms")
        val retryDelayMs: Long? = null,
        @SerialName("continue_on_error")
        val continueOnError: Boolean? = null,
    )

    @Serializable
    private data class FlowStep(
        val action: String,
        val text: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        @SerialName("from_x")
        val fromX: Int? = null,
        @SerialName("from_y")
        val fromY: Int? = null,
        @SerialName("to_x")
        val toX: Int? = null,
        @SerialName("to_y")
        val toY: Int? = null,
        val direction: String? = null,
        val key: String? = null,
        @SerialName("package")
        val packageName: String? = null,
        val command: String? = null,
        val path: String? = null,
        @SerialName("wait_ms")
        val waitMs: Long? = null,
        @SerialName("expect_text")
        val expectText: String? = null,
        @SerialName("timeout_ms")
        val timeoutMs: Long? = null,
        val retries: Int? = null,
        @SerialName("retry_delay_ms")
        val retryDelayMs: Long? = null,
        @SerialName("continue_on_error")
        val continueOnError: Boolean? = null,
        @SerialName("duration_ms")
        val durationMs: Int? = null,
        @SerialName("bottom_only")
        val bottomOnly: Boolean? = null,
        @SerialName("min_y_ratio")
        val minYRatio: Double? = null,
    )
}
