package com.linux_core.xlauncher

import android.content.Context
import android.content.SharedPreferences

/** What a still/held finger in MOUSE mode does. */
enum class HoldAction {
    /**
     * Default scheme: tap = click. A second touch-down landing soon enough
     * and close enough to a completed tap grabs the left button immediately
     * (no timer) — "click, click, don't let go the second time" — for
     * dragging. A plain hold with no prior tap right-clicks instead (after
     * [LauncherSettings.longPressMs], same as two fingers held still).
     * Two fingers dragged scrolls.
     */
    TAP_TAP_HOLD,

    /** Any single-finger hold grabs the left button for dragging. No tap-counting; right-click is two-finger-hold only. */
    GRAB_DRAG,

    /** Any single-finger hold right-clicks. No tap-counting, no drag-by-holding. */
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

    /**
     * Delay in ms before a still finger/two fingers count as a hold. Used by
     * the single-finger hold timer (right-click on a plain hold under
     * [HoldAction.TAP_TAP_HOLD], right-click under [HoldAction.RIGHT_CLICK],
     * grab under [HoldAction.GRAB_DRAG]) and by the two-finger
     * hold-to-right-click gesture in all schemes. Only
     * [HoldAction.TAP_TAP_HOLD]'s tap-then-hold *grab* skips this delay.
     */
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
