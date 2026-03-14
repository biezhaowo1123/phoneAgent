package com.phoneagent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        return rootInActiveWindow?.packageName?.toString()
    }

    /** Get a text representation of the current screen. */
    fun readScreen(): String {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w("AgentA11y", "readScreen: rootInActiveWindow is null")
            return "(无法获取屏幕内容)"
        }
        val pkg = root.packageName ?: "unknown"
        val sb = StringBuilder()
        sb.appendLine("--- [$pkg] ---")
        traverseNode(root, sb, 0)
        Log.d("AgentA11y", "readScreen: pkg=$pkg, length=${sb.length}")
        return sb.toString()
    }

    private fun traverseNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast('.') ?: ""
        val clickable = if (node.isClickable) "[可点击]" else ""
        val editable = if (node.isEditable) "[可编辑]" else ""

        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val display = text.ifEmpty { desc }
            sb.appendLine("$indent$className $clickable$editable \"$display\" @[${rect.centerX()},${rect.centerY()}]")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, sb, depth + 1)
            child.recycle()
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

        // Prefer nodes with exact content-description match, then exact text match
        val exactDesc = nodes.filter { it.contentDescription?.toString() == text }
        val exactText = nodes.filter { it.text?.toString() == text }
        val prioritized = when {
            exactDesc.isNotEmpty() -> exactDesc
            exactText.isNotEmpty() -> exactText
            else -> nodes
        }

        // Among prioritized, prefer clickable nodes with smallest area
        val clickable = prioritized.filter { it.isClickable }
        val target = if (clickable.isNotEmpty()) {
            clickable.minByOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
        } else {
            // Fallback: find clickable parent of the best match (smallest area non-clickable)
            val best = prioritized.minByOrNull {
                val rect = Rect()
                it.getBoundsInScreen(rect)
                rect.width() * rect.height()
            }
            best?.let { findClickableParent(it) }
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

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
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
