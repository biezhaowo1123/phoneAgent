package com.phoneagent.skill.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.phoneagent.ai.ToolDefinition
import com.phoneagent.ai.ToolParameter
import com.phoneagent.device.AgentAccessibilityService
import com.phoneagent.device.DeviceController
import com.phoneagent.engine.AgentEngine
import com.phoneagent.skill.Skill
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Skill: highly optimized instant-message sender for WeChat / QQ.
 * Goal: precise and fast multi-step automation.
 */
class InstantMessageSkill(private val context: Context) : Skill {

    override val id = "im"
    override val name = "即时消息"
    override val description = "自动发送微信/QQ消息，支持精准/平衡/极速模式"

    private val deviceManager get() = AgentEngine.getInstance(context).deviceManager

    override fun getTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "send_message",
            description = "自动在微信或QQ给指定联系人发送消息（高精度+高速度）",
            parameters = mapOf(
                "app" to ToolParameter(
                    "string",
                    "应用类型：wechat 或 qq",
                    required = true,
                    enum = listOf("wechat", "qq")
                ),
                "to" to ToolParameter("string", "联系人名称", required = true),
                "message" to ToolParameter("string", "消息内容", required = true),
                "mode" to ToolParameter(
                    "string",
                    "执行模式：precise(精准), balanced(平衡), fast(极速)",
                    enum = listOf("precise", "balanced", "fast")
                ),
            )
        )
    )

    override suspend fun executeTool(toolName: String, arguments: Map<String, String>): String {
        return when (toolName) {
            "send_message" -> {
                val app = arguments["app"] ?: return "Error: missing app"
                val to = arguments["to"]?.trim().orEmpty()
                val message = arguments["message"]?.trim().orEmpty()
                if (to.isBlank()) return "Error: missing to"
                if (message.isBlank()) return "Error: missing message"

                val mode = parseMode(arguments["mode"])
                val profile = resolveProfile(app)
                    ?: return "Error: app must be 'wechat' or 'qq'"
                withTimeoutOrNull(28_000L) {
                    sendMessage(profile, to, message, mode)
                } ?: "Error: 发送流程超时（已自动停止），请重试并优先使用 mode=fast 或 mode=balanced"
            }
            else -> "Unknown tool: $toolName"
        }
    }

    private enum class ExecuteMode { PRECISE, BALANCED, FAST }

    private data class AppProfile(
        val id: String,
        val packageName: String,
        val searchKeywords: List<String>,
        val inputKeywords: List<String>,
        val sendKeywords: List<String>,
        val openChatKeywords: List<String>,
        val searchPageMarkers: List<String>,
        val searchIconPoint: Pair<Int, Int>,
        val searchInputPoint: Pair<Int, Int>,
        val firstResultPoint: Pair<Int, Int>,
        val messageInputPoint: Pair<Int, Int>,
        val sendPoint: Pair<Int, Int>,
    )

    private val wechatProfile = AppProfile(
        id = "wechat",
        packageName = "com.tencent.mm",
        searchKeywords = listOf("搜索", "Search"),
        inputKeywords = listOf("发送消息", "发消息", "切换到键盘"),
        sendKeywords = listOf("发送", "Send"),
        openChatKeywords = listOf("发消息", "进入聊天"),
        searchPageMarkers = listOf("聊天记录", "公众号", "小程序", "搜索指定内容", "搜一搜", "更多联系人"),
        searchIconPoint = 870 to 150,
        searchInputPoint = 540 to 130,
        firstResultPoint = 540 to 560,
        messageInputPoint = 450 to 2200,
        sendPoint = 990 to 2200,
    )

    private val qqProfile = AppProfile(
        id = "qq",
        packageName = "com.tencent.mobileqq",
        searchKeywords = listOf("搜索", "找人", "查找"),
        inputKeywords = listOf("消息", "发送消息", "发消息"),
        sendKeywords = listOf("发送", "Send"),
        openChatKeywords = listOf("发消息", "进入聊天", "发送消息"),
        searchPageMarkers = listOf("找人", "找群", "频道", "搜索结果", "更多结果"),
        searchIconPoint = 960 to 150,
        searchInputPoint = 540 to 180,
        firstResultPoint = 540 to 360,
        messageInputPoint = 450 to 2200,
        sendPoint = 980 to 2200,
    )

    private suspend fun sendMessage(
        profile: AppProfile,
        to: String,
        message: String,
        mode: ExecuteMode,
    ): String {
        if (AgentAccessibilityService.getInstance() == null) {
            return "Error: 即时消息技能需要无障碍服务，请先在设置中开启"
        }

        runOnControllers(mode, "launch ${profile.id}") { ctrl ->
            ctrl.launchApp(profile.packageName)
        }.getOrElse { return "Error: 启动${profile.id}失败 - ${it.message}" }
        waitUi(mode, 800)

        var enteredChat = false

        // Fast path: chat list already contains contact.
        if (clickByText(mode, to)) {
            waitUi(mode, 260)
            enteredChat = true
        }
        if (!enteredChat) {
            enteredChat = openSearchAndSelectContact(profile, to, mode)
        }
        if (!enteredChat) {
            return "Error: 未找到联系人 '$to'，或未能进入聊天窗口。请确认联系人名称准确。"
        }
        waitUi(mode, 360)

        if (!focusMessageInput(profile, mode)) {
            // If still on profile/search page, try opening chat then re-focus.
            tapOpenChatButton(profile, mode)
            waitUi(mode, 220)
            if (!focusMessageInput(profile, mode)) {
                // Final quick recovery: exit one page and re-open target.
                pressKey(mode, "back")
                waitUi(mode, 200)
                if (clickSearchResultByTextPreferList(profile, to) || clickByText(mode, to)) {
                    waitUi(mode, 260)
                }
                if (!focusMessageInput(profile, mode)) {
                    return "Error: 无法定位消息输入框"
                }
            }
        }
        waitUi(mode, 160)

        if (!inputText(mode, message)) {
            return "Error: 输入消息失败"
        }
        waitUi(mode, 140)

        if (!clickSend(profile, mode)) {
            return "Error: 未找到发送按钮"
        }
        waitUi(mode, 180)

        if (mode == ExecuteMode.PRECISE) {
            val screen = readScreen(mode)
            if (isScreenReadable(screen)) {
                val token = message.replace("\\s+".toRegex(), "").take(8)
                if (token.length >= 2 && !screen.replace("\\s+".toRegex(), "").contains(token)) {
                    // Retry once in precise mode to maximize reliability.
                    if (focusMessageInput(profile, mode) && inputText(mode, message) && clickSend(profile, mode)) {
                        waitUi(mode, 220)
                    } else {
                        return "Warning: 已尝试发送，但未通过校验确认。请检查聊天窗口。"
                    }
                }
            }
        }
        return "已在${if (profile.id == "wechat") "微信" else "QQ"}向 $to 发送消息"
    }

    private suspend fun openSearchAndSelectContact(
        profile: AppProfile,
        contact: String,
        mode: ExecuteMode,
    ): Boolean {
        val screenSize = getScreenSize(mode)
        val searchIcon = scalePoint(profile.searchIconPoint, screenSize)
        val searchInput = scalePoint(profile.searchInputPoint, screenSize)
        val resultPoints = buildSearchResultTapPoints(profile.firstResultPoint, screenSize)

        // Open search UI.
        val openedSearch = profile.searchKeywords.any { clickByText(mode, it) } ||
            clickAt(mode, searchIcon.first, searchIcon.second)
        if (!openedSearch) return false
        waitUi(mode, 180)

        // Ensure focus on search box then input contact name.
        if (!clickAt(mode, searchInput.first, searchInput.second)) return false
        waitUi(mode, 90)
        if (!inputText(mode, contact)) return false
        waitUi(mode, 160)

        // Confirm search / open result.
        pressKey(mode, "enter")
        waitSearchResults(mode)

        // If WeChat exposes "更多联系人", go deeper first.
        if (profile.id == "wechat") {
            if (clickByText(mode, "更多联系人")) {
                waitUi(mode, 260)
            }
        }

        if (trySelectContact(profile, contact, mode, screenSize) { clickSearchResultByTextPreferList(profile, contact) }) {
            return true
        }

        if (trySelectContact(profile, contact, mode, screenSize) { clickByText(mode, contact) }) {
            return true
        }

        if (trySelectContact(profile, contact, mode, screenSize) {
            clickContactByScreenshotOcr(profile, contact, mode, screenSize)
        }) {
            return true
        }

        if (trySelectContact(profile, contact, mode, screenSize) {
            clickResultByClickableArea(profile, mode, screenSize)
        }) {
            return true
        }

        if (trySelectContact(profile, contact, mode, screenSize) {
            clickContactFromUiDump(contact, mode, screenSize)
        }) {
            return true
        }

        // Coordinate fallback: try avatar side + text side for first 3 rows.
        for (point in resultPoints) {
            if (trySelectContact(profile, contact, mode, screenSize) {
                    clickAt(mode, point.first, point.second)
                }) {
                return true
            }
        }

        if (trySelectContact(profile, contact, mode, screenSize) {
                clickSearchResultByTextPreferList(profile, contact) || clickByText(mode, contact)
            }) {
            return true
        }

        if (confirmSelectionOutcome(profile, contact, mode, screenSize)) {
            return true
        }

        // Still in search page: back once and retry from conversation list.
        if (isLikelyStillSearchPage(profile, mode, screenSize) == true) {
            pressKey(mode, "back")
            waitUi(mode, 180)
            if (trySelectContact(profile, contact, mode, screenSize) {
                    clickSearchResultByTextPreferList(profile, contact) || clickByText(mode, contact)
                }) {
                return true
            }
        }

        return false
    }

    private suspend fun focusMessageInput(profile: AppProfile, mode: ExecuteMode): Boolean {
        val screenSize = getScreenSize(mode)
        val messageInput = scalePoint(profile.messageInputPoint, screenSize)
        val a11y = AgentAccessibilityService.getInstance()
        val editableCenter = a11y?.findBottomEditableCenter(minYRatio = 0.42f)
        if (editableCenter != null && clickAt(mode, editableCenter.first, editableCenter.second)) {
            return true
        }

        if (a11y != null) {
            for (keyword in profile.inputKeywords) {
                if (a11y.clickByTextNearBottom(keyword, minYRatio = 0.60f)) {
                    return true
                }
            }
        }

        return clickAt(mode, messageInput.first, messageInput.second)
    }

    private suspend fun clickSend(profile: AppProfile, mode: ExecuteMode): Boolean {
        val screenSize = getScreenSize(mode)
        val send = scalePoint(profile.sendPoint, screenSize)
        val a11y = AgentAccessibilityService.getInstance()

        if (a11y != null) {
            for (keyword in profile.sendKeywords) {
                if (a11y.clickByTextNearBottom(keyword, minYRatio = 0.70f)) {
                    return true
                }
            }

            val editableCenter = a11y.findBottomEditableCenter(minYRatio = 0.42f)
            if (editableCenter != null) {
                val rowY = editableCenter.second.coerceIn(
                    (screenSize.second * 0.55f).toInt(),
                    (screenSize.second * 0.96f).toInt()
                )
                val dynamicPoints = listOf(
                    ((screenSize.first * 0.95f).toInt() to rowY),
                    ((screenSize.first * 0.90f).toInt() to rowY),
                    ((screenSize.first * 0.98f).toInt() to rowY),
                ).map { (x, y) ->
                    x.coerceIn(0, (screenSize.first - 1).coerceAtLeast(0)) to
                        y.coerceIn(0, (screenSize.second - 1).coerceAtLeast(0))
                }.distinct()
                for ((x, y) in dynamicPoints) {
                    if (clickAt(mode, x, y)) return true
                }
            }
        }

        val fallbackPoints = listOf(
            send,
            (send.first - (screenSize.first * 0.06f).toInt()) to send.second,
            (screenSize.first * 0.95f).toInt() to (screenSize.second * 0.88f).toInt(),
            (screenSize.first * 0.95f).toInt() to (screenSize.second * 0.82f).toInt(),
        ).map { (x, y) ->
            x.coerceIn(0, (screenSize.first - 1).coerceAtLeast(0)) to
                y.coerceIn(0, (screenSize.second - 1).coerceAtLeast(0))
        }.distinct()

        for ((x, y) in fallbackPoints) {
            if (clickAt(mode, x, y)) return true
        }
        return false
    }

    private fun resolveProfile(app: String): AppProfile? {
        val normalized = app.trim().lowercase()
        return when {
            normalized in setOf("wechat", "weixin", "wx", "微信") -> wechatProfile
            normalized in setOf("qq", "腾讯qq") -> qqProfile
            else -> null
        }
    }

    private fun parseMode(raw: String?): ExecuteMode {
        return when (raw?.trim()?.lowercase()) {
            "fast", "极速" -> ExecuteMode.FAST
            "balanced", "平衡" -> ExecuteMode.BALANCED
            else -> ExecuteMode.PRECISE
        }
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
            Log.w("InstantMessageSkill", "$actionName failed on ${ctrl.name}: ${lastError.message}")
        }
        return Result.failure(lastError)
    }

    private suspend fun clickByText(mode: ExecuteMode, text: String): Boolean {
        val a11y = AgentAccessibilityService.getInstance()
        if (a11y?.clickByText(text) == true) return true
        return runOnControllers(mode, "clickByText($text)", uiPreferred = true) { it.clickByText(text) }.isSuccess
    }

    private suspend fun clickAt(mode: ExecuteMode, x: Int, y: Int): Boolean {
        return runOnControllers(mode, "clickAt($x,$y)", uiPreferred = true) { it.clickAt(x, y) }.isSuccess
    }

    private suspend fun pressKey(mode: ExecuteMode, key: String): Boolean {
        return runOnControllers(mode, "pressKey($key)") { it.pressKey(key) }.isSuccess
    }

    private suspend fun inputText(mode: ExecuteMode, text: String): Boolean {
        // Prefer accessibility ACTION_SET_TEXT first to avoid shell append behavior
        // in search boxes and improve Chinese input reliability.
        val a11y = AgentAccessibilityService.getInstance()
        if (a11y?.inputText(text) == true) return true

        val inputResult = runOnControllers(mode, "inputText", shellPreferred = false) { ctrl ->
            val primary = ctrl.inputText(text)
            if (primary.isSuccess) {
                primary
            } else {
                val fallbackA11y = AgentAccessibilityService.getInstance()
                if (fallbackA11y?.inputText(text) == true) {
                    Result.success("input via a11y")
                } else {
                    ctrl.runShellCommand(buildShellInputCommand(text))
                }
            }
        }
        return inputResult.isSuccess
    }

    private suspend fun readScreen(mode: ExecuteMode): String {
        val a11yScreen = AgentAccessibilityService.getInstance()?.readScreen().orEmpty()
        if (a11yScreen.isNotBlank()) return a11yScreen
        if (mode == ExecuteMode.FAST) return a11yScreen
        val screen = runOnControllers(ExecuteMode.FAST, "readScreen", shellPreferred = true, uiPreferred = true) { it.readScreen() }
            .getOrNull()
            .orEmpty()
        if (screen.isNotBlank()) return screen
        return a11yScreen
    }

    private suspend fun trySelectContact(
        profile: AppProfile,
        contact: String,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
        selector: suspend () -> Boolean,
    ): Boolean {
        if (!selector()) return false
        waitUi(mode, 220)
        return confirmSelectionOutcome(profile, contact, mode, screenSize)
    }

    private suspend fun confirmSelectionOutcome(
        profile: AppProfile,
        contact: String,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
    ): Boolean {
        if (tapOpenChatButton(profile, mode)) {
            waitUi(mode, 260)
            return true
        }

        val searchState = isLikelyStillSearchPage(profile, mode, screenSize)
        if (searchState == true) return false

        val chatState = isLikelyChatPage(profile, contact, mode)
        if (chatState == true) return true

        return searchState == false
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

    private fun buildShellInputCommand(text: String): String {
        val escaped = text.replace("'", "'\\''")
        return if (text.all { it.code < 128 }) {
            "input text '${escaped.replace(" ", "%s")}'"
        } else {
            "am broadcast -a ADB_INPUT_TEXT --es msg '$escaped'"
        }
    }

    private fun scalePoint(basePoint: Pair<Int, Int>, screenSize: Pair<Int, Int>): Pair<Int, Int> {
        val (w, h) = screenSize
        val x = (basePoint.first * (w / 1080f)).toInt().coerceIn(0, (w - 1).coerceAtLeast(0))
        val y = (basePoint.second * (h / 2340f)).toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
        return x to y
    }

    private fun buildSearchResultTapPoints(
        firstResultBasePoint: Pair<Int, Int>,
        screenSize: Pair<Int, Int>,
    ): List<Pair<Int, Int>> {
        val first = scalePoint(firstResultBasePoint, screenSize)
        val step = (180 * (screenSize.second / 2340f)).toInt().coerceAtLeast(120)
        val maxY = (screenSize.second - 1).coerceAtLeast(0)
        val rows = listOf(
            first.second,
            (first.second + step).coerceAtMost(maxY),
            (first.second + step * 2).coerceAtMost(maxY),
        )
        val xCandidates = listOf(
            (screenSize.first * 0.20f).toInt(),
            (screenSize.first * 0.42f).toInt(),
            (screenSize.first * 0.62f).toInt(),
        ).map { it.coerceIn(0, (screenSize.first - 1).coerceAtLeast(0)) }

        val points = mutableListOf<Pair<Int, Int>>()
        rows.forEach { y ->
            xCandidates.forEach { x -> points.add(x to y) }
        }
        return points
    }

    private suspend fun tapOpenChatButton(profile: AppProfile, mode: ExecuteMode): Boolean {
        for (keyword in profile.openChatKeywords) {
            if (clickByText(mode, keyword)) {
                waitUi(mode, 260)
                return true
            }
        }
        return false
    }

    private fun clickSearchResultByTextPreferList(profile: AppProfile, contact: String): Boolean {
        val a11y = AgentAccessibilityService.getInstance() ?: return false
        val ratio = if (profile.id == "wechat") 0.24f else 0.18f
        return a11y.clickSearchResultByText(contact, topExclusionRatio = ratio)
    }

    private suspend fun clickContactFromUiDump(
        contact: String,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
    ): Boolean {
        val xml = withTimeoutOrNull(1_400L) {
            runOnControllers(mode, "uiautomatorDumpSearch", shellPreferred = true) { ctrl ->
                val dumpPath = "/sdcard/im_search_dump.xml"
                ctrl.runShellCommand("uiautomator dump $dumpPath >/dev/null 2>&1; cat $dumpPath; rm $dumpPath")
            }.getOrNull().orEmpty()
        }.orEmpty()
        if (xml.isBlank() || xml == "OK" || !xml.contains("<node")) return false

        val point = findPointByKeywordFromUiDump(xml, contact, screenSize) ?: return false
        return clickAt(mode, point.first, point.second)
    }

    private fun findPointByKeywordFromUiDump(
        xml: String,
        keyword: String,
        screenSize: Pair<Int, Int>,
    ): Pair<Int, Int>? {
        val nodeRegex = Regex("""<node\s+([^>]+?)/?>""")
        val boundsRegex = Regex("""bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"""")
        val minY = (screenSize.second * 0.20f).toInt()
        val centerX = screenSize.first / 2
        val candidates = mutableListOf<UiCandidate>()

        nodeRegex.findAll(xml).forEach { m ->
            val attrs = m.groupValues[1]
            val text = extractXmlAttr(attrs, "text")
            val desc = extractXmlAttr(attrs, "content-desc")
            val matchText = text ?: desc ?: ""
            if (matchText.isBlank()) return@forEach
            if (!matchText.contains(keyword)) return@forEach

            val b = boundsRegex.find(attrs) ?: return@forEach
            val x1 = b.groupValues[1].toIntOrNull() ?: return@forEach
            val y1 = b.groupValues[2].toIntOrNull() ?: return@forEach
            val x2 = b.groupValues[3].toIntOrNull() ?: return@forEach
            val y2 = b.groupValues[4].toIntOrNull() ?: return@forEach
            val cx = (x1 + x2) / 2
            val cy = (y1 + y2) / 2
            if (cy < minY) return@forEach

            val exact = (text == keyword || desc == keyword)
            val score = (if (exact) 1000 else 0) - abs(cx - centerX) - (cy - minY)
            candidates.add(UiCandidate(cx, cy, score))
        }

        return candidates.maxByOrNull { it.score }?.let { it.x to it.y }
    }

    private data class UiCandidate(
        val x: Int,
        val y: Int,
        val score: Int,
    )

    private fun extractXmlAttr(attrs: String, name: String): String? {
        val p = Regex("""$name="([^"]*)"""")
        return p.find(attrs)?.groupValues?.get(1)
    }

    private suspend fun clickResultByClickableArea(
        profile: AppProfile,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
    ): Boolean {
        val a11y = AgentAccessibilityService.getInstance() ?: return false
        val (w, h) = screenSize
        val topRatio = if (profile.id == "wechat") 0.24f else 0.20f
        val top = (h * topRatio).toInt()
        val bottom = (h * 0.86f).toInt()
        val left = (w * 0.04f).toInt()
        val right = (w * 0.96f).toInt()

        val points = a11y.findClickableNodesInArea(left, top, right, bottom)
            .filter { (_, y) -> y in top..bottom }
            .distinct()
            .sortedWith(
                compareBy<Pair<Int, Int>> { it.second }
                    .thenBy { abs(it.first - (w / 2)) }
            )

        for ((x, y) in points.take(8)) {
            if (clickAt(mode, x, y)) return true
        }
        return false
    }

    private suspend fun clickContactByScreenshotOcr(
        profile: AppProfile,
        contact: String,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
    ): Boolean {
        val a11y = AgentAccessibilityService.getInstance() ?: return false
        val bitmap = withTimeoutOrNull(1_400L) { a11y.takeScreenshotBitmap() } ?: return false
        return try {
            val point = withTimeoutOrNull(2_100L) {
                findPointByOcr(bitmap, profile, contact, screenSize)
            } ?: return false
            Log.i(
                "InstantMessageSkill",
                "OCR match for '$contact' at [${point.first},${point.second}]"
            )
            clickAt(mode, point.first, point.second)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun findPointByOcr(
        bitmap: Bitmap,
        profile: AppProfile,
        keyword: String,
        screenSize: Pair<Int, Int>,
    ): Pair<Int, Int>? {
        val candidates = withTimeoutOrNull(1_600L) { recognizeTextCandidates(bitmap) }.orEmpty()
        if (candidates.isEmpty()) return null

        val topRatio = if (profile.id == "wechat") 0.22f else 0.18f
        val minY = (screenSize.second * topRatio).toInt()
        val maxY = (screenSize.second * 0.90f).toInt()
        val centerX = screenSize.first / 2
        val expectedY = scalePoint(profile.firstResultPoint, screenSize).second

        val winner = candidates
            .mapNotNull { candidate ->
                if (candidate.centerY !in minY..maxY) return@mapNotNull null
                val score = scoreOcrCandidate(
                    keyword = keyword,
                    candidateText = candidate.text,
                    centerX = centerX,
                    expectedY = expectedY,
                    candidateX = candidate.centerX,
                    candidateY = candidate.centerY,
                )
                if (score <= 0) return@mapNotNull null
                OcrCandidate(candidate.centerX, candidate.centerY, score, candidate.text)
            }
            .maxByOrNull { it.score }

        return winner?.let { it.x to it.y }
    }

    private suspend fun recognizeTextCandidates(bitmap: Bitmap): List<OcrTextCandidate> {
        val recognizer = TextRecognition.getClient(
            ChineseTextRecognizerOptions.Builder().build()
        )
        return suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val candidates = mutableListOf<OcrTextCandidate>()
                    text.textBlocks.forEach { block ->
                        block.lines.forEach { line ->
                            addOcrCandidate(candidates, line.text, line.boundingBox)
                        }
                        if (block.lines.isEmpty()) {
                            addOcrCandidate(candidates, block.text, block.boundingBox)
                        }
                    }
                    recognizer.close()
                    if (cont.isActive) cont.resume(candidates)
                }
                .addOnFailureListener { e ->
                    Log.w("InstantMessageSkill", "OCR recognition failed: ${e.message}")
                    recognizer.close()
                    if (cont.isActive) cont.resume(emptyList())
                }
            cont.invokeOnCancellation { recognizer.close() }
        }
    }

    private fun addOcrCandidate(list: MutableList<OcrTextCandidate>, text: String?, rect: Rect?) {
        val value = text?.trim().orEmpty()
        if (value.isBlank()) return
        if (rect == null) return
        list.add(
            OcrTextCandidate(
                text = value,
                centerX = rect.centerX(),
                centerY = rect.centerY(),
            )
        )
    }

    private fun scoreOcrCandidate(
        keyword: String,
        candidateText: String,
        centerX: Int,
        expectedY: Int,
        candidateX: Int,
        candidateY: Int,
    ): Int {
        val target = normalizeContactToken(keyword)
        val candidate = normalizeContactToken(candidateText)
        if (target.isBlank() || candidate.isBlank()) return 0

        val exact = target == candidate
        val contains = candidate.contains(target)
        val reverseContains = target.contains(candidate)
        val distance = levenshteinDistance(target, candidate)
        val distanceThreshold = when {
            target.length <= 2 -> 0
            target.length <= 4 -> 1
            else -> 2
        }

        if (!exact && !contains && !reverseContains && distance > distanceThreshold) {
            return 0
        }

        var score = when {
            exact -> 2400
            contains -> 1900
            reverseContains -> 1500
            else -> 1100
        }
        score -= distance * 250
        score -= abs(candidateX - centerX)
        score -= abs(candidateY - expectedY) / 2
        return score
    }

    private fun normalizeContactToken(text: String): String {
        return text.lowercase()
            .replace("\\s+".toRegex(), "")
            .replace("""[^\p{L}\p{N}]""".toRegex(), "")
    }

    private suspend fun isLikelyStillSearchPage(
        profile: AppProfile,
        mode: ExecuteMode,
        screenSize: Pair<Int, Int>,
    ): Boolean? {
        val byA11y = isLikelySearchPage(profile, mode)
        if (byA11y == true) return true

        val byOcr = isLikelySearchPageByOcr(profile, screenSize)
        if (byOcr != null) return byOcr

        return byA11y
    }

    private suspend fun isLikelySearchPageByOcr(
        profile: AppProfile,
        screenSize: Pair<Int, Int>,
    ): Boolean? {
        val a11y = AgentAccessibilityService.getInstance() ?: return null
        val bitmap = withTimeoutOrNull(1_300L) { a11y.takeScreenshotBitmap() } ?: return null
        return try {
            val candidates = withTimeoutOrNull(1_600L) { recognizeTextCandidates(bitmap) }.orEmpty()
            if (candidates.isEmpty()) return null

            val minY = (screenSize.second * 0.06f).toInt()
            val maxY = (screenSize.second * 0.60f).toInt()
            val merged = normalizeText(
                candidates
                    .filter { it.centerY in minY..maxY }
                    .joinToString(separator = " ") { it.text }
            )
            if (merged.isBlank()) return null

            val searchMarkers = (profile.searchPageMarkers + profile.searchKeywords + listOf("更多联系人"))
                .map { normalizeText(it) }
            val chatMarkers = (profile.sendKeywords + profile.inputKeywords + listOf("按住说话", "切换到按住说话", "表情", "加号"))
                .map { normalizeText(it) }

            val hasSearchMarker = searchMarkers.any { marker -> marker.isNotBlank() && merged.contains(marker) }
            val hasChatMarker = chatMarkers.any { marker -> marker.isNotBlank() && merged.contains(marker) }

            when {
                hasSearchMarker && !hasChatMarker -> true
                hasChatMarker && !hasSearchMarker -> false
                else -> null
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)

        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                val deletion = previous[j + 1] + 1
                val insertion = current[j] + 1
                val substitution = previous[j] + cost
                current[j + 1] = minOf(deletion, insertion, substitution)
            }
            for (k in previous.indices) {
                previous[k] = current[k]
            }
        }
        return previous[b.length]
    }

    private data class OcrTextCandidate(
        val text: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class OcrCandidate(
        val x: Int,
        val y: Int,
        val score: Int,
        val text: String,
    )

    private fun isAccessibilityController(ctrl: DeviceController): Boolean {
        return ctrl.name.contains("无障碍") || ctrl.name.contains("accessibility", ignoreCase = true)
    }

    private suspend fun isLikelySearchPage(profile: AppProfile, mode: ExecuteMode): Boolean? {
        val raw = readScreen(mode)
        if (!isScreenReadable(raw)) return null
        val normalized = normalizeText(raw)
        return profile.searchPageMarkers.any { marker -> normalized.contains(normalizeText(marker)) }
    }

    private suspend fun isLikelyChatPage(
        profile: AppProfile,
        contact: String,
        mode: ExecuteMode,
    ): Boolean? {
        val raw = readScreen(mode)
        if (!isScreenReadable(raw)) return null
        val normalized = normalizeText(raw)

        val hasSend = profile.sendKeywords.any { normalized.contains(normalizeText(it)) }
        val hasInputMarker = profile.inputKeywords.any { normalized.contains(normalizeText(it)) }
        val hasSearchMarker = profile.searchPageMarkers.any { normalized.contains(normalizeText(it)) }
        val contactToken = normalizeText(contact).take(4)
        val hasContact = contactToken.length >= 2 && normalized.contains(contactToken)

        return (hasSend && hasInputMarker) || (hasSend && hasContact) || (hasSend && !hasSearchMarker)
    }

    private fun normalizeText(text: String): String {
        return text.replace("\\s+".toRegex(), "").lowercase()
    }

    private fun isScreenReadable(raw: String): Boolean {
        if (raw.isBlank()) return false
        if (raw.length < 40) return false
        if (raw.contains("无法获取屏幕内容")) return false
        if (raw.contains("屏幕内容无法通过无障碍服务读取")) return false
        return true
    }

    private suspend fun waitUi(mode: ExecuteMode, baseMs: Long) {
        val ms = when (mode) {
            ExecuteMode.FAST -> (baseMs * 0.60f).toLong()
            ExecuteMode.BALANCED -> (baseMs * 0.90f).toLong()
            ExecuteMode.PRECISE -> (baseMs * 1.15f).toLong()
        }.coerceAtLeast(50L)
        delay(ms)
    }

    private suspend fun waitSearchResults(mode: ExecuteMode) {
        val baseMs = when (mode) {
            ExecuteMode.FAST -> 380L
            ExecuteMode.BALANCED -> 620L
            ExecuteMode.PRECISE -> 820L
        }
        delay(baseMs)
    }
}
