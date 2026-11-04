package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.Viewport

/**
 * TOUCH mode: the X pointer follows the finger — down presses the left button,
 * move drags it, up releases. A [TouchpadSubGestureHandler] equivalent to the
 * Windows App's per-gesture touchpad handlers. Logic preserved verbatim from
 * `LauncherActivity.onDirectTouch`.
 */
class DirectGestureHandler(
    private val state: TouchpadPointerState,
    private val sink: InputActionListener,
    private val viewport: Viewport,
) {
    fun handle(e: TouchEvent): Boolean {
        val fb = viewport.toFramebuffer(e.x, e.y) ?: return true
        val (x, y) = fb
        sink.updateCursor(x, y)
        when (e.action) {
            TouchAction.DOWN -> {
                sink.sendPointer(x, y, MouseButton.LEFT, true)
                state.directPressed = true
            }
            TouchAction.MOVE -> sink.sendPointer(x, y, MouseButton.LEFT, null)
            TouchAction.UP, TouchAction.CANCEL -> {
                sink.sendPointer(x, y, MouseButton.LEFT, false)
                state.directPressed = false
            }
            else -> {}
        }
        return true
    }
}
