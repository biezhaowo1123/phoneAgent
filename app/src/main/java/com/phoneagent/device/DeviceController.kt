package com.phoneagent.device

import kotlinx.serialization.Serializable

/**
 * Unified device control interface.
 * Multiple backends: Accessibility, Shell (ADB/Root), Shizuku.
 */
interface DeviceController {
    val name: String
    val isAvailable: Boolean

    suspend fun clickAt(x: Int, y: Int): Result<String>
    suspend fun clickByText(text: String): Result<String>
    suspend fun longPressAt(x: Int, y: Int): Result<String>
    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int = 500): Result<String>
    suspend fun inputText(text: String): Result<String>
    suspend fun pressKey(key: String): Result<String>
    suspend fun readScreen(): Result<String>
    suspend fun screenshot(path: String): Result<String>
    suspend fun runShellCommand(command: String): Result<String>
    suspend fun launchApp(packageName: String): Result<String>
    suspend fun forceStopApp(packageName: String): Result<String>
    suspend fun getScreenSize(): Pair<Int, Int>
}

@Serializable
enum class ControlMode(val displayName: String) {
    ACCESSIBILITY("无障碍服务"),
    SHELL("Shell (ADB)"),
    SHIZUKU("Shizuku"),
    ROOT("Root"),
    AUTO("自动选择"),
}
