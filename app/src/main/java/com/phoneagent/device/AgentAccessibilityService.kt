package com.phoneagent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Accessibility Service for phone UI automation.
 * Provides: screen reading, click, scroll, input text, gestures.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: AgentAccessibilityService? = null

        fun getInstance(): AgentAccessibilityService? = instance

        val isRunning: StateFlow<Boolean> get() = _isRunning
        private val _isRunning = MutableStateFlow(false)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true
        Log.i("AgentA11y", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events can be processed for screen monitoring if needed
    }

    override fun onInterrupt() {
        Log.w("AgentA11y", "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        _isRunning.value = false
        super.onDestroy()
    }

    // ---------- Screen Reading ----------

    /** Get the package name of the currently active (foreground) window. */
    fun getActivePackage(): String? {
        rootInActiveWindow?.packageName?.toString()?.let { return it }
        windows?.forEach { w ->
            w.root?.packageName?.toString()?.let { return it }
        }
        return null
    }

    /** Get a text representation of the current screen. */
    fun readScreen(): String {
        val roots = collectWindowRoots()
        if (roots.isEmpty()) {
            Log.w("AgentA11y", "readScreen: rootInActiveWindow is null")
            return "(无法获取屏幕内容)"
        }
        val sb = StringBuilder()
        var totalVisited = 0
        var totalEmitted = 0
        roots.forEachIndexed { index, root ->
            val pkg = root.packageName ?: "unknown"
            sb.appendLine("--- [window#$index][$pkg] ---")
            val stats = traverseNode(root, sb, 0, maxDepth = 14, maxNodes = 500)
            totalVisited += stats.visited
            totalEmitted += stats.emitted

            if (stats.emitted == 0) {
                val clickablePoints = collectClickablePoints(root, maxCount = 24).distinct()
                if (clickablePoints.isNotEmpty()) {
                    sb.appendLine("  [可点击坐标候选]")
                    clickablePoints.take(12).forEach { (x, y) ->
                        sb.appendLine("  点位 @[$x,$y]")
                    }
                }
            }
        }

        // If rich text is still unavailable (common in some apps), provide actionable coordinates.
        if (totalEmitted == 0 || sb.length < 80) {
            val clickablePoints = roots
                .flatMap { collectClickablePoints(it, maxCount = 40) }
                .distinct()
            if (clickablePoints.isNotEmpty()) {
                sb.appendLine("--- [可点击坐标候选] ---")
                clickablePoints.forEach { (x, y) ->
                    sb.appendLine("点位 @[$x,$y]")
                }
            }
        }

        Log.d(
            "AgentA11y",
            "readScreen: windows=${roots.size}, visited=$totalVisited, emitted=$totalEmitted, length=${sb.length}"
        )
        return sb.toString()
    }

    private data class TraverseStats(
        val visited: Int,
        val emitted: Int,
    )

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int,
        maxDepth: Int,
        maxNodes: Int,
    ): TraverseStats {
        if (depth > maxDepth || maxNodes <= 0) return TraverseStats(0, 0)

        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName?.substringAfterLast('/')
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val clickable = if (node.isClickable) "[可点击]" else ""
        val editable = if (node.isEditable) "[可编辑]" else ""
        val focusable = if (node.isFocusable) "[可聚焦]" else ""

        var emitted = 0
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val display = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                !viewId.isNullOrBlank() -> "#$viewId"
                else -> "<$className>"
            }
            sb.appendLine(
                "$indent$className $clickable$editable$focusable \"$display\" " +
                    "@[${rect.centerX()},${rect.centerY()}]"
            )
            emitted++
        }

        var visited = 1
        if (visited >= maxNodes) return TraverseStats(visited, emitted)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childStats = traverseNode(
                node = child,
                sb = sb,
                depth = depth + 1,
                maxDepth = maxDepth,
                maxNodes = maxNodes - visited,
            )
            visited += childStats.visited
            emitted += childStats.emitted
            child.recycle()
            if (visited >= maxNodes) break
        }
        return TraverseStats(visited, emitted)
    }

    /**
     * Capture current display screenshot as bitmap for OCR fallback.
     * Returns null when capture is unavailable or fails.
     */
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w("AgentA11y", "takeScreenshotBitmap: requires API 30+")
            return null
        }

        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                Log.w("AgentA11y", "takeScreenshotBitmap: wrap buffer failed", e)
                                null
                            } finally {
                                hardwareBuffer.close()
                            }
                            if (cont.isActive) cont.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w("AgentA11y", "takeScreenshotBitmap failed, code=$errorCode")
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w("AgentA11y", "takeScreenshotBitmap exception", e)
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    // ---------- UI Actions ----------

    /** Click at screen coordinates. */
    fun clickAt(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /** Long press at coordinates. */
    fun longPressAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Swipe from one point to another. */
    fun swipe(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 500) {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** Find a node by text or content-description and click it. */
    fun clickByText(text: String): Boolean {
        return clickByTextInternal(text, topExclusionRatio = 0f, preferOutsideTop = false)
    }

    /**
     * Search-result oriented click:
     * avoid top search bar area and prefer non-input candidates.
     */
    fun clickSearchResultByText(text: String, topExclusionRatio: Float = 0.22f): Boolean {
        return clickByTextInternal(text, topExclusionRatio = topExclusionRatio, preferOutsideTop = true)
    }

    /**
     * Bottom-area oriented click:
     * useful for send buttons to avoid clicking same text in chat history.
     */
    fun clickByTextNearBottom(text: String, minYRatio: Float = 0.72f): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) {
            return false
        }

        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val minY = rootRect.top + (rootRect.height() * minYRatio).toInt()

        val candidates = nodes.mapNotNull { n ->
            val target = if (n.isClickable) n else findClickableParent(n)
            target?.let {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                val exactMatch = (n.text?.toString() == text || n.contentDescription?.toString() == text)
                BottomClickCandidate(
                    target = it,
                    rect = rect,
                    exactMatch = exactMatch,
                )
            }
        }

        val inBottom = candidates.filter { it.rect.centerY() >= minY }
        val pool = if (inBottom.isNotEmpty()) inBottom else candidates
        val target = pool.sortedWith(
            compareByDescending<BottomClickCandidate> { it.exactMatch }
                .thenByDescending { it.rect.centerX() }
                .thenByDescending { it.rect.centerY() }
        ).firstOrNull()?.target ?: return false

        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickByTextInternal(
        text: String,
        topExclusionRatio: Float,
        preferOutsideTop: Boolean,
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (nodes.isNullOrEmpty()) {
            Log.d("AgentA11y", "clickByText('$text'): not found in pkg=${root.packageName}")
            return false
        }

        // Log all candidates for debugging
        for (n in nodes) {
            val rect = Rect()
            n.getBoundsInScreen(rect)
            Log.d("AgentA11y", "clickByText('$text'): candidate text='${n.text}' desc='${n.contentDescription}' class=${n.className} clickable=${n.isClickable} bounds=$rect")
        }

        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val topCutY = rootRect.top + (rootRect.height() * topExclusionRatio).toInt()

        // Prefer nodes with exact content-description match, then exact text match.
        val exactDesc = nodes.filter { it.contentDescription?.toString() == text }
        val exactText = nodes.filter { it.text?.toString() == text }
        val prioritized = when {
            exactDesc.isNotEmpty() -> exactDesc
            exactText.isNotEmpty() -> exactText
            else -> nodes
        }

        val filtered = prioritized.filterNot { isInputLike(it) }.ifEmpty { prioritized }
        val candidates = filtered.mapNotNull { n ->
            val target = if (n.isClickable) n else findClickableParent(n)
            target?.let {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                ClickCandidate(
                    target = it,
                    rect = rect,
                    inputLike = isInputLike(n) || isInputLike(it),
                )
            }
        }
        val scopedCandidates = if (preferOutsideTop) {
            val outsideTop = candidates.filter { it.rect.centerY() > topCutY }
            if (outsideTop.isNotEmpty()) outsideTop else candidates
        } else {
            candidates
        }

        val target = if (scopedCandidates.isNotEmpty()) {
            scopedCandidates.sortedWith(
                compareByDescending<ClickCandidate> { it.rect.centerY() > topCutY || !preferOutsideTop }
                    .thenBy { it.inputLike }
                    .thenByDescending { it.rect.width() * it.rect.height() }
            ).firstOrNull()?.target
        } else {
            null
        }

        if (target != null) {
            val rect = Rect()
            target.getBoundsInScreen(rect)
            Log.d("AgentA11y", "clickByText('$text'): clicking at $rect pkg=${root.packageName}")
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        Log.d("AgentA11y", "clickByText('$text'): no clickable target in pkg=${root.packageName}")
        return false
    }

    private data class ClickCandidate(
        val target: AccessibilityNodeInfo,
        val rect: Rect,
        val inputLike: Boolean,
    )

    private data class BottomClickCandidate(
        val target: AccessibilityNodeInfo,
        val rect: Rect,
        val exactMatch: Boolean,
    )

    private fun collectWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        windows?.forEach { w ->
            w.root?.let { root ->
                val exists = roots.any { it === root }
                if (!exists) roots.add(root)
            }
        }
        return roots
    }

    private fun collectClickablePoints(root: AccessibilityNodeInfo, maxCount: Int): List<Pair<Int, Int>> {
        val points = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && points.size < maxCount) {
            val node = queue.removeFirst()
            if (node.isClickable || node.isLongClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                points.add(rect.centerX() to rect.centerY())
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.addLast(child)
            }
        }
        return points
    }

    /** Input text into editable field in the active window. */
    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = findFocusedEditable(root) ?: findAnyEditable(root)
        if (target != null) {
            if (!target.isFocused) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            Log.d("AgentA11y", "inputText('$text'): found editable in pkg=${root.packageName}")
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
        Log.d("AgentA11y", "inputText('$text'): no editable in pkg=${root.packageName}")
        return false
    }

    /** Press back button. */
    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** Press home button. */
    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** Open recent apps. */
    fun openRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    /** Open notification shade. */
    fun openNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    /** Open quick settings. */
    fun openQuickSettings() {
        performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
    }

    /** Scroll down in current view. */
    fun scrollDown(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findScrollableNode(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
    }

    /** Scroll up in current view. */
    fun scrollUp(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findScrollableNode(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) ?: false
    }

    // ---------- Helpers ----------

    /** Find all clickable nodes within a screen area. Returns list of (centerX, centerY). */
    fun findClickableNodesInArea(left: Int, top: Int, right: Int, bottom: Int): List<Pair<Int, Int>> {
        val root = rootInActiveWindow ?: return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        collectClickableInArea(root, left, top, right, bottom, result)
        return result
    }

    /** Find the lowest editable input center in current screen, usually chat input box. */
    fun findBottomEditableCenter(minYRatio: Float = 0.45f): Pair<Int, Int>? {
        val root = rootInActiveWindow ?: return null
        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val minY = rootRect.top + (rootRect.height() * minYRatio).toInt()

        val candidates = mutableListOf<Pair<Int, Int>>()
        collectEditableCenters(root, minY, candidates)
        return candidates.maxWithOrNull(
            compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first }
        )
    }

    private fun collectClickableInArea(
        node: AccessibilityNodeInfo, left: Int, top: Int, right: Int, bottom: Int,
        result: MutableList<Pair<Int, Int>>
    ) {
        if (node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.centerX() in left..right && rect.centerY() in top..bottom) {
                result.add(rect.centerX() to rect.centerY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableInArea(child, left, top, right, bottom, result)
        }
    }

    private fun collectEditableCenters(
        node: AccessibilityNodeInfo,
        minY: Int,
        result: MutableList<Pair<Int, Int>>,
    ) {
        if (node.isEditable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.centerY() >= minY) {
                result.add(rect.centerX() to rect.centerY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableCenters(child, minY, result)
        }
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    private fun isInputLike(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString().orEmpty()
        return node.isEditable || cls.contains("EditText", ignoreCase = true)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            if (result != null) return result
        }
        return null
    }

    private fun findAnyEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findAnyEditable(child)
            if (result != null) return result
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
        }
        return null
    }
}
