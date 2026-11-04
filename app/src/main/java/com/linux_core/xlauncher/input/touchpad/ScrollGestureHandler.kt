package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.input.GestureScheduler
import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.TouchpadConfig
import kotlin.math.abs

/**
 * TWO-FINGER gesture handler (both modes), mirroring the dedicated scroll/hold
 * handlers in `com.microsoft.windowsapp.input.touchpad.handler`. The average Y
 * of the two fingers drives mouse-wheel buttons 4/5 once they move; holding
 * both still in MOUSE mode right-clicks at the cursor after
 * [TouchpadConfig.longPressMs]. Logic preserved verbatim from
 * `LauncherActivity.onScrollTouch` + `scheduleTwoFingerHold`/`wheel`.
 */
class ScrollGestureHandler(
    private val state: TouchpadPointerState,
    private val sink: InputActionListener,
    private val scheduler: GestureScheduler,
    private val config: () -> TouchpadConfig,
) {
    /** Whether a two-finger hold-to-right-click timer is currently armed. */
    private var holdPending = false

    fun handle(e: TouchEvent): Boolean {
        val y = (e.y + e.y1) / 2f
        when (e.action) {
            TouchAction.POINTER_DOWN -> {
                state.scrolling = true
                state.scrollAccum = 0f
                state.scrollLastY = y
                state.scrollAnchorY = y
                state.twoFingerRightClicked = false
                if (state.mouseMode) scheduleHold()
            }
            TouchAction.MOVE -> {
                if (!state.scrolling) {
                    state.scrolling = true
                    state.scrollLastY = y
                    state.scrollAnchorY = y
                    return true
                }
                if (holdPending && abs(y - state.scrollAnchorY) > config().tapSlopPx) {
                    // Real movement: this is a scroll, not a held-still right-click.
                    cancelHold()
                }
                if (state.twoFingerRightClicked) return true
                val dy = y - state.scrollLastY
                state.scrollLastY = y
                state.scrollAccum += dy
                val notch = config().scrollNotchPx
                // Finger up (dy < 0) => content scrolls down => wheel down (5).
                while (state.scrollAccum <= -notch) {
                    state.scrollAccum += notch
                    wheel(up = false)
                }
                while (state.scrollAccum >= notch) {
                    state.scrollAccum -= notch
                    wheel(up = true)
                }
            }
            TouchAction.POINTER_UP -> {
                cancelHold()
                state.scrolling = false
                state.scrollAccum = 0f
            }
            TouchAction.UP, TouchAction.CANCEL -> {
                cancelHold()
                state.scrolling = false
                state.scrollAccum = 0f
            }
            else -> {}
        }
        return true
    }

    private fun scheduleHold() {
        cancelHold()
        holdPending = true
        scheduler.postDelayed(TouchpadPointerState.TOKEN_TWO_FINGER_HOLD, config().longPressMs) {
            holdPending = false
            state.twoFingerRightClicked = true
            sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, null)
            sink.sendButton(MouseButton.RIGHT, true)
            sink.sendButton(MouseButton.RIGHT, false)
        }
    }

    /** Cancels a pending two-finger hold timer (idempotent). */
    fun cancelHold() {
        if (holdPending) {
            scheduler.cancel(TouchpadPointerState.TOKEN_TWO_FINGER_HOLD)
            holdPending = false
        }
    }

    private fun wheel(up: Boolean) {
        val button = if (up) MouseButton.WHEEL_UP else MouseButton.WHEEL_DOWN
        sink.sendButton(button, true)
        sink.sendButton(button, false)
    }
}
