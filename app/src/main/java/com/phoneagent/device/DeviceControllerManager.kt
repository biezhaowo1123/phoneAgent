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

    private val _controlMode = MutableStateFlow(ControlMode.AUTO)
    val controlMode: StateFlow<ControlMode> = _controlMode

    fun setControlMode(mode: ControlMode) {
        _controlMode.value = mode
    }

    /** Get the active controller based on current mode. */
    fun getActiveController(): DeviceController {
        return when (_controlMode.value) {
            ControlMode.ACCESSIBILITY -> accessibilityCtrl
            ControlMode.SHELL -> shellCtrl
            ControlMode.ROOT -> rootCtrl
            ControlMode.SHIZUKU -> shizukuCtrl
            ControlMode.AUTO -> autoSelect()
        }
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

    /** Auto-select the best available controller. Priority: Shizuku > Root > Accessibility > Shell */
    private fun autoSelect(): DeviceController {
        return when {
            shizukuCtrl.isAvailable -> shizukuCtrl
            rootCtrl.isAvailable -> rootCtrl
            accessibilityCtrl.isAvailable -> accessibilityCtrl
            else -> shellCtrl
        }
    }
}
