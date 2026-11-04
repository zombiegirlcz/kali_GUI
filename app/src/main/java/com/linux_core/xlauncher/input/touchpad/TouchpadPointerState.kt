package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton

/**
 * All mutable gesture bookkeeping, gathered out of `LauncherActivity` into one
 * state object — the role the Windows App gives `TouchpadPointerState` /
 * `PinchState` in `com.microsoft.windowsapp.input.touchpad.state`.
 *
 * The handlers ([DirectGestureHandler], [MouseGestureHandler],
 * [ScrollGestureHandler]) and the [TouchpadInputInterceptor] decider all read
 * and write these fields; keeping them here (instead of as Activity fields) is
 * what lets the gesture machine run on a plain JVM.
 */
class TouchpadPointerState {

    /** false = TOUCH (pointer follows finger), true = MOUSE (trackpad drag). */
    var mouseMode = false

    /** Cursor position in framebuffer pixels (MOUSE mode). */
    var cursorX = 0
    var cursorY = 0

    // --- single-finger gesture bookkeeping (MOUSE mode) ---
    var downX = 0f
    var downY = 0f
    var downTime = 0L

    /** True once travel passed the tap slop; a drag must never fire a click. */
    var dragged = false
    var slopAnchorX = 0f
    var slopAnchorY = 0f

    /** True while a long-press grab holds the left button down. */
    var grabbing = false

    /** Tap-then-hold ("click, click, don't let go") bookkeeping. */
    var recentTapCount = 0
    var lastTapUpTime = 0L
    var lastTapX = 0f
    var lastTapY = 0f

    /** True once this gesture's hold already fired its right-click. */
    var rightClicked = false

    // --- two-finger bookkeeping (both modes) ---
    var scrolling = false
    var scrollLastY = 0f
    var scrollAccum = 0f
    var scrollAnchorY = 0f
    var twoFingerRightClicked = false

    /** True while TOUCH mode holds the left button (finger on screen). */
    var directPressed = false

    /** Releases a held long-press grab in place, if one is active. */
    fun releaseGrab(sink: InputActionListener) {
        if (grabbing) {
            sink.sendButton(MouseButton.LEFT, false)
            grabbing = false
        }
    }

    companion object {
        /** Scheduler token for the single-finger long-press timer. */
        val TOKEN_LONG_PRESS = Any()

        /** Scheduler token for the two-finger hold-to-right-click timer. */
        val TOKEN_TWO_FINGER_HOLD = Any()
    }
}
