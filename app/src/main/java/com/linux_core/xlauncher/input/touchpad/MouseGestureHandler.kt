package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.HoldAction
import com.linux_core.xlauncher.input.GestureScheduler
import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.TouchpadConfig
import com.linux_core.xlauncher.input.Viewport
import kotlin.math.sqrt

/**
 * MOUSE (trackpad) mode single-finger handler: dragging moves the cursor
 * relative to where it already is; taps and holds become clicks per the chosen
 * [HoldAction]. The single-finger counterpart to the Windows App's gesture
 * handlers in `com.microsoft.windowsapp.input.touchpad.handler`. Logic preserved
 * verbatim from `LauncherActivity.onMouseTouch` + `movePointer` +
 * `scheduleLongPress`.
 */
class MouseGestureHandler(
    private val state: TouchpadPointerState,
    private val sink: InputActionListener,
    private val scheduler: GestureScheduler,
    private val viewport: Viewport,
    private val config: () -> TouchpadConfig,
) {
    fun handle(e: TouchEvent): Boolean {
        if (viewport.viewportWidth <= 0 || viewport.framebufferWidth <= 0) return true

        when (e.action) {
            TouchAction.DOWN -> {
                state.downX = e.x
                state.downY = e.y
                state.slopAnchorX = e.x
                state.slopAnchorY = e.y
                state.downTime = e.timeMs
                state.dragged = false
                state.grabbing = false
                state.rightClicked = false
                val cfg = config()
                val sinceLastTap = state.downTime - state.lastTapUpTime
                val closeToLastTap = state.recentTapCount > 0 &&
                        sinceLastTap <= cfg.doubleTapTimeoutMs &&
                        distance(e.x, e.y, state.lastTapX, state.lastTapY) <= cfg.doubleTapSlopPx
                if (!closeToLastTap) state.recentTapCount = 0

                when (cfg.holdAction) {
                    HoldAction.TAP_TAP_HOLD -> {
                        if (state.recentTapCount >= 1) {
                            // Second touch of "click, click, hold": grab now,
                            // don't wait for a timer — not letting go IS the signal.
                            state.grabbing = true
                            sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, null)
                            sink.sendButton(MouseButton.LEFT, true)
                        } else {
                            // Plain first touch: a hold-still right-clicks instead.
                            scheduleLongPress(cfg)
                        }
                    }
                    HoldAction.GRAB_DRAG, HoldAction.RIGHT_CLICK -> scheduleLongPress(cfg)
                }
            }
            TouchAction.MOVE -> {
                // Cumulative travel since the gesture started decides tap vs drag.
                val slopX = e.x - state.slopAnchorX
                val slopY = e.y - state.slopAnchorY
                val tapSlopPx = config().tapSlopPx
                if (!state.dragged && (slopX * slopX + slopY * slopY) > tapSlopPx * tapSlopPx) {
                    state.dragged = true
                    cancelLongPress()
                    state.recentTapCount = 0
                }
                movePointer(e)
                // Re-anchor so the next MOVE only applies the new delta.
                state.downX = e.x
                state.downY = e.y
            }
            TouchAction.UP -> {
                cancelLongPress()
                if (state.grabbing) {
                    sink.sendButton(MouseButton.LEFT, false)
                    state.grabbing = false
                    state.recentTapCount = 0
                } else if (state.rightClicked) {
                    // Already fired by the hold timer; nothing left to do.
                } else if (!state.dragged) {
                    // A tap clicks the left button at the cursor.
                    sink.updateCursor(state.cursorX, state.cursorY)
                    sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, null)
                    sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, true)
                    sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, false)
                    // Register the tap so a close, quick second touch grabs instead.
                    state.recentTapCount = 1
                    state.lastTapUpTime = e.timeMs
                    state.lastTapX = e.x
                    state.lastTapY = e.y
                }
                state.dragged = false
            }
            TouchAction.CANCEL -> {
                cancelLongPress()
                state.releaseGrab(sink)
                state.dragged = false
                state.recentTapCount = 0
            }
            else -> {}
        }
        return true
    }

    /** Applies one-finger motion to the cursor (pure motion, no button change). */
    private fun movePointer(e: TouchEvent) {
        val dx = (e.x - state.downX) / viewport.viewportWidth.toFloat() * viewport.framebufferWidth
        val dy = (e.y - state.downY) / viewport.viewportHeight.toFloat() * viewport.framebufferHeight
        val nx = (state.cursorX + dx).toInt().coerceIn(0, viewport.framebufferWidth - 1)
        val ny = (state.cursorY + dy).toInt().coerceIn(0, viewport.framebufferHeight - 1)
        if (nx != state.cursorX || ny != state.cursorY) {
            state.cursorX = nx
            state.cursorY = ny
            sink.updateCursor(state.cursorX, state.cursorY)
            // Pure motion: a held long-press grab keeps the left button down.
            sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, null)
        }
    }

    private fun scheduleLongPress(cfg: TouchpadConfig) {
        cancelLongPress()
        scheduler.postDelayed(TouchpadPointerState.TOKEN_LONG_PRESS, cfg.longPressMs) {
            if (!state.dragged && !state.grabbing && !state.rightClicked) {
                // Button events use the pointer's current position, so put the X
                // pointer at the on-screen cursor first (a fresh connection has
                // sent no motion yet, so the server pointer is still at 0,0).
                sink.sendPointer(state.cursorX, state.cursorY, MouseButton.LEFT, null)
                if (cfg.holdAction == HoldAction.GRAB_DRAG) {
                    state.grabbing = true
                    sink.sendButton(MouseButton.LEFT, true)
                } else {
                    // RIGHT_CLICK setting, or TAP_TAP_HOLD's plain-hold case.
                    state.rightClicked = true
                    sink.sendButton(MouseButton.RIGHT, true)
                    sink.sendButton(MouseButton.RIGHT, false)
                }
            }
        }
    }

    fun cancelLongPress() = scheduler.cancel(TouchpadPointerState.TOKEN_LONG_PRESS)

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
