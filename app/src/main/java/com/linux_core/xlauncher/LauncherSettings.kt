package com.linux_core.xlauncher

import android.content.Context
import android.content.SharedPreferences

/** What holding a finger still in MOUSE mode does. */
enum class HoldAction {
    /**
     * Plain hold (or hold after two taps) grabs the left button for dragging;
     * one tap then hold right-clicks instead, and one tap then a swipe
     * (instead of holding still) scrolls.
     */
    TAP_TAP_HOLD,

    /** Hold always grabs the left button for dragging. No right-click via hold. */
    GRAB_DRAG,

    /** Hold always right-clicks. No drag-by-holding. */
    RIGHT_CLICK,
}

/**
 * Persists the touch-input tuning knobs so they survive restarts, instead of
 * being fixed constants in [LauncherActivity].
 */
class LauncherSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var holdAction: HoldAction
        get() = try {
            HoldAction.valueOf(prefs.getString(KEY_HOLD_ACTION, null) ?: DEFAULT_HOLD_ACTION.name)
        } catch (_: IllegalArgumentException) {
            DEFAULT_HOLD_ACTION
        }
        set(value) = prefs.edit().putString(KEY_HOLD_ACTION, value.name).apply()

    /** Delay in ms before a still finger counts as a hold. */
    var longPressMs: Long
        get() = prefs.getInt(KEY_LONG_PRESS_MS, DEFAULT_LONG_PRESS_MS).toLong()
        set(value) = prefs.edit()
                .putInt(KEY_LONG_PRESS_MS, value.toInt().coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS))
                .apply()

    companion object {
        private const val PREFS_NAME = "xlauncher_settings"
        private const val KEY_HOLD_ACTION = "hold_action"
        private const val KEY_LONG_PRESS_MS = "long_press_ms"

        val DEFAULT_HOLD_ACTION = HoldAction.TAP_TAP_HOLD
        const val DEFAULT_LONG_PRESS_MS = 400
        const val MIN_LONG_PRESS_MS = 200
        const val MAX_LONG_PRESS_MS = 800
    }
}
