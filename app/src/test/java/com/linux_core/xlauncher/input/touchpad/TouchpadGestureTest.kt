package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.HoldAction
import com.linux_core.xlauncher.input.GestureScheduler
import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.MouseButton
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.TouchpadConfig
import com.linux_core.xlauncher.input.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour-preservation tests for the gesture state machine extracted from
 * `LauncherActivity`. They run on a plain JVM precisely because the handlers
 * depend only on the input layer's abstractions — the reason the Windows App
 * keeps its touch logic out of the Activity.
 */
class TouchpadGestureTest {

    private class FakeSink : InputActionListener {
        val log = mutableListOf<String>()
        override fun sendPointer(x: Int, y: Int, button: Int, pressed: Boolean?) { log += "P $x,$y,$button,$pressed" }
        override fun sendButton(button: Int, pressed: Boolean) { log += "B $button,$pressed" }
        override fun updateCursor(x: Int, y: Int) { log += "C $x,$y" }
    }

    private class FakeScheduler : GestureScheduler {
        val actions = HashMap<Any, () -> Unit>()
        override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) { actions[token] = action }
        override fun cancel(token: Any) { actions.remove(token) }
        fun fire(token: Any) { actions.remove(token)?.invoke() }
    }

    private val viewport = object : Viewport {
        override val viewportX = 0
        override val viewportY = 0
        override val viewportWidth = 1000
        override val viewportHeight = 1000
        override val framebufferWidth = 1000
        override val framebufferHeight = 1000
    }

    private fun cfg(hold: HoldAction = HoldAction.TAP_TAP_HOLD) = TouchpadConfig(
        holdAction = hold, longPressMs = 400L, tapSlopPx = 12f, scrollNotchPx = 24f,
        doubleTapTimeoutMs = 300L, doubleTapSlopPx = 20,
    )

    private fun ev(a: TouchAction, x: Float, y: Float, pc: Int = 1, t: Long = 0L, x1: Float = 0f, y1: Float = 0f) =
        TouchEvent(a, pc, x, y, x1, y1, t)

    private fun interceptor(sink: FakeSink, sched: FakeScheduler, mouse: Boolean = true, hold: HoldAction = HoldAction.TAP_TAP_HOLD) =
        TouchpadInputInterceptor(sink, sched, viewport, config = { cfg(hold) }).also { it.mouseMode = mouse }

    @Test fun mouseTapClicksLeftAtCursor() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.DOWN, 100f, 100f, t = 1000))
        i.onTouch(ev(TouchAction.UP, 100f, 100f, t = 1050))
        assertEquals(listOf(
            "C 0,0", "P 0,0,${MouseButton.LEFT},null",
            "P 0,0,${MouseButton.LEFT},true", "P 0,0,${MouseButton.LEFT},false",
        ), s.log)
    }

    @Test fun mouseDragMovesCursorWithoutClicking() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.DOWN, 100f, 100f, t = 1000))
        i.onTouch(ev(TouchAction.MOVE, 130f, 100f, t = 1010))
        i.onTouch(ev(TouchAction.UP, 130f, 100f, t = 1020))
        assertEquals(listOf("C 30,0", "P 30,0,${MouseButton.LEFT},null"), s.log)
        assertTrue("a drag must never click", s.log.none { it.contains(",true") || it.contains(",false") })
    }

    @Test fun mousePlainHoldRightClicks() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.DOWN, 100f, 100f, t = 1000))
        sc.fire(TouchpadPointerState.TOKEN_LONG_PRESS)
        assertEquals(listOf(
            "P 0,0,${MouseButton.LEFT},null",
            "B ${MouseButton.RIGHT},true", "B ${MouseButton.RIGHT},false",
        ), s.log)
        i.onTouch(ev(TouchAction.UP, 100f, 100f, t = 1500))
        assertEquals("up after a fired hold does nothing extra", 3, s.log.size)
    }

    @Test fun tapThenHoldGrabsAndDragReleasesOnUp() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.DOWN, 100f, 100f, t = 1000))
        i.onTouch(ev(TouchAction.UP, 100f, 100f, t = 1050)) // tap -> recentTapCount = 1
        s.log.clear()
        i.onTouch(ev(TouchAction.DOWN, 105f, 105f, t = 1100)) // close & soon -> immediate grab
        assertEquals(listOf("P 0,0,${MouseButton.LEFT},null", "B ${MouseButton.LEFT},true"), s.log)
        i.onTouch(ev(TouchAction.UP, 105f, 105f, t = 1150))
        assertEquals("B ${MouseButton.LEFT},false", s.log.last())
    }

    @Test fun twoFingerDragDownScrollsWheelUp() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.POINTER_DOWN, 100f, 300f, pc = 2, t = 1000, x1 = 120f, y1 = 300f))
        i.onTouch(ev(TouchAction.MOVE, 100f, 330f, pc = 2, t = 1010, x1 = 120f, y1 = 330f))
        // Fingers down => content scrolls up => wheel up (button 4), like a touchscreen.
        assertTrue(s.log.contains("B ${MouseButton.WHEEL_UP},true"))
    }

    @Test fun twoFingerHoldStillRightClicks() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc)
        i.onTouch(ev(TouchAction.POINTER_DOWN, 100f, 300f, pc = 2, t = 1000, x1 = 120f, y1 = 300f))
        sc.fire(TouchpadPointerState.TOKEN_TWO_FINGER_HOLD)
        assertTrue(s.log.contains("B ${MouseButton.RIGHT},true"))
        assertTrue(s.log.contains("B ${MouseButton.RIGHT},false"))
    }

    @Test fun touchModePressesOnDownReleasesOnUp() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc, mouse = false)
        i.onTouch(ev(TouchAction.DOWN, 200f, 200f, t = 1000))
        i.onTouch(ev(TouchAction.UP, 200f, 200f, t = 1010))
        assertEquals(listOf(
            "C 200,200", "P 200,200,${MouseButton.LEFT},true",
            "C 200,200", "P 200,200,${MouseButton.LEFT},false",
        ), s.log)
    }

    @Test fun grabDragHoldGrabsWithoutPriorTap() {
        val s = FakeSink(); val sc = FakeScheduler(); val i = interceptor(s, sc, hold = HoldAction.GRAB_DRAG)
        i.onTouch(ev(TouchAction.DOWN, 100f, 100f, t = 1000))
        sc.fire(TouchpadPointerState.TOKEN_LONG_PRESS)
        assertEquals(listOf("P 0,0,${MouseButton.LEFT},null", "B ${MouseButton.LEFT},true"), s.log)
    }
}
