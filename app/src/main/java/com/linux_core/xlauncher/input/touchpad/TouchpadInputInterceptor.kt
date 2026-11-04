package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.input.GestureScheduler
import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.TouchpadConfig
import com.linux_core.xlauncher.input.Viewport

/**
 * Top-level touch entry point and gesture decider, mirroring the Windows App's
 * `TouchpadInputInterceptor` + `TouchpadDeciderHandler`
 * (`com.microsoft.windowsapp.input.touchpad`): it routes a sample to the right
 * sub-handler — two fingers → [ScrollGestureHandler], one finger →
 * [MouseGestureHandler] (MOUSE mode) or [DirectGestureHandler] (TOUCH mode) —
 * and owns the shared [TouchpadPointerState]. Replaces
 * `LauncherActivity.onDesktopTouch`.
 */
class TouchpadInputInterceptor(
    private val sink: InputActionListener,
    scheduler: GestureScheduler,
    viewport: Viewport,
    config: () -> TouchpadConfig,
    private val state: TouchpadPointerState = TouchpadPointerState(),
) {
    private val direct = DirectGestureHandler(state, sink, viewport)
    private val mouse = MouseGestureHandler(state, sink, scheduler, viewport, config)
    private val scroll = ScrollGestureHandler(state, sink, scheduler, config)

    /** false = TOUCH (pointer follows finger), true = MOUSE (trackpad drag). */
    var mouseMode: Boolean
        get() = state.mouseMode
        set(value) { state.mouseMode = value }

    fun onTouch(e: TouchEvent): Boolean {
        // Two-finger drag scrolls, in both modes.
        if (e.pointerCount >= 2) {
            mouse.cancelLongPress()
            state.releaseGrab(sink)
            state.recentTapCount = 0
            // A second finger turns a TOUCH-mode press into a scroll: drop the
            // button we had already pressed, otherwise X keeps it held down.
            if (state.directPressed) {
                sink.sendButton(MouseButton.LEFT, false)
                state.directPressed = false
            }
            return scroll.handle(e)
        }
        // Ignore the tail of a scroll gesture once the second finger lifted.
        if (state.scrolling) {
            if (e.action == TouchAction.UP || e.action == TouchAction.CANCEL) {
                state.scrolling = false
                state.scrollAccum = 0f
            }
            return true
        }
        return if (state.mouseMode) mouse.handle(e) else direct.handle(e)
    }

    /**
     * Places the cursor (framebuffer pixels) and moves the sprite to match —
     * used when a connection opens and when entering MOUSE mode, exactly as
     * `LauncherActivity` recentred the cursor before.
     */
    fun setCursor(x: Int, y: Int) {
        state.cursorX = x
        state.cursorY = y
        sink.updateCursor(x, y)
    }
}
