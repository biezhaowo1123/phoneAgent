package com.phoneagent.device

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages multiple DeviceController backends and routes commands
 * to the active one. Supports auto-fallback.
 */
class DeviceControllerManager(private val context: Context) {

    private val accessibilityCtrl = AccessibilityController(context)
    private val shellCtrl = ShellController(context, useRoot = false)
    private val rootCtrl = ShellController(context, useRoot = true)
    private val shizukuCtrl = ShizukuController(context)
    private val prefs = context.getSharedPreferences("device_control", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CONTROL_MODE = "control_mode"
        private const val KEY_PRECISION_MODE = "precision_mode"
    }

    private val _controlMode = MutableStateFlow(loadControlMode())
    val controlMode: StateFlow<ControlMode> = _controlMode
    private val _precisionMode = MutableStateFlow(prefs.getBoolean(KEY_PRECISION_MODE, false))
    val precisionMode: StateFlow<Boolean> = _precisionMode

    fun setControlMode(mode: ControlMode) {
        _controlMode.value = mode
        prefs.edit().putString(KEY_CONTROL_MODE, mode.name).apply()
    }

    fun setPrecisionMode(enabled: Boolean) {
        _precisionMode.value = enabled
        prefs.edit().putBoolean(KEY_PRECISION_MODE, enabled).apply()
    }

    /** Get the active controller based on current mode. */
    fun getActiveController(): DeviceController {
        val selected = controllerForMode(_controlMode.value)
        if (_controlMode.value == ControlMode.AUTO) return selected
        return if (selected.isAvailable) selected else autoSelect()
    }

    /**
     * Returns controllers ordered for retry.
     * [shellPreferred] is used by shell-like actions (device_shell/device_screenshot).
     */
    fun getControllersForRetry(shellPreferred: Boolean = false): List<DeviceController> {
        val preferredOrder = when {
            shellPreferred -> listOf(shizukuCtrl, rootCtrl, shellCtrl, accessibilityCtrl)
            _precisionMode.value -> listOf(accessibilityCtrl, shizukuCtrl, rootCtrl, shellCtrl)
            else -> listOf(shizukuCtrl, rootCtrl, accessibilityCtrl, shellCtrl)
        }

        val selected = getActiveController()
        val ordered = buildList {
            add(selected)
            preferredOrder.forEach { ctrl ->
                if (ctrl !== selected) add(ctrl)
            }
        }
        return ordered.filter { it.isAvailable }.ifEmpty { listOf(shellCtrl) }
    }

    /** Get all available controllers and their status. */
    fun getControllerStatus(): List<Pair<ControlMode, Boolean>> {
        return listOf(
            ControlMode.ACCESSIBILITY to accessibilityCtrl.isAvailable,
            ControlMode.SHELL to shellCtrl.isAvailable,
            ControlMode.ROOT to rootCtrl.isAvailable,
            ControlMode.SHIZUKU to shizukuCtrl.isAvailable,
        )
    }

    /**
     * Auto-select based on current strategy:
     * - normal: Shizuku > Root > Accessibility > Shell
     * - precision: Accessibility > Shizuku > Root > Shell
     */
    private fun autoSelect(): DeviceController {
        val order = if (_precisionMode.value) {
            listOf(accessibilityCtrl, shizukuCtrl, rootCtrl, shellCtrl)
        } else {
            listOf(shizukuCtrl, rootCtrl, accessibilityCtrl, shellCtrl)
        }
        return order.firstOrNull { it.isAvailable } ?: shellCtrl
    }

    private fun controllerForMode(mode: ControlMode): DeviceController {
        return when (mode) {
            ControlMode.ACCESSIBILITY -> accessibilityCtrl
            ControlMode.SHELL -> shellCtrl
            ControlMode.ROOT -> rootCtrl
            ControlMode.SHIZUKU -> shizukuCtrl
            ControlMode.AUTO -> autoSelect()
        }
    }

    private fun loadControlMode(): ControlMode {
        val raw = prefs.getString(KEY_CONTROL_MODE, ControlMode.AUTO.name) ?: ControlMode.AUTO.name
        return try {
            ControlMode.valueOf(raw)
        } catch (_: Exception) {
            ControlMode.AUTO
        }
    }
}
