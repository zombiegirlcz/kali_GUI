package com.linux_core.xlauncher.input

import com.linux_core.xlauncher.HoldAction

/**
 * Pure-Kotlin input layer, modelled on the Microsoft "Windows App"
 * (`com.microsoft.rdc.androidx`, package `com.microsoft.windowsapp.input.*`).
 *
 * The reference app keeps every gesture/key decision out of its Activities and
 * in a dedicated, dependency-injected input module (interceptor → decider →
 * per-gesture handlers, each driving a thin `IOnInputActionListener` /
 * `IOnUiActionListener`). That layering is what makes its touch logic testable
 * without a device.
 *
 * This file mirrors that split for the X11 launcher: the handlers operate only
 * on these plain model types and on the [InputActionListener] / [GestureScheduler]
 * / [Viewport] abstractions, so the whole gesture state machine runs — and is
 * unit-tested — on a plain JVM. [com.linux_core.xlauncher.LauncherActivity] is
 * the only Android adapter: it converts `MotionEvent`/`KeyEvent` into these
 * types and implements the abstractions against `X11Client` and `X11Renderer`.
 */

/** X pointer button numbers (XTEST detail). */
object MouseButton {
    const val LEFT = 1
    const val RIGHT = 3
    const val WHEEL_UP = 4
    const val WHEEL_DOWN = 5
}

/**
 * The subset of `MotionEvent.actionMasked` the gesture handlers distinguish,
 * named instead of numeric so the core carries no Android dependency.
 */
enum class TouchAction { DOWN, MOVE, UP, CANCEL, POINTER_DOWN, POINTER_UP, OTHER }

/**
 * A device-independent touch sample. Mirrors just the `MotionEvent` fields the
 * handlers read: the primary pointer (index 0), the second pointer (index 1,
 * for two-finger scroll), the live pointer count and a wall-clock timestamp
 * (so tap/double-tap timing is deterministic and testable).
 */
data class TouchEvent(
    val action: TouchAction,
    val pointerCount: Int,
    val x: Float,
    val y: Float,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val timeMs: Long,
)

/**
 * Geometry of the rendered desktop, supplied by the renderer. The handlers use
 * it to map a view coordinate to a remote framebuffer pixel. Mirrors the
 * viewport/framebuffer accessors `X11Renderer` already exposes.
 */
interface Viewport {
    val viewportX: Int
    val viewportY: Int
    val viewportWidth: Int
    val viewportHeight: Int
    val framebufferWidth: Int
    val framebufferHeight: Int
}

/**
 * Tuning snapshot read per gesture. Everything that depends on Android
 * resources/`ViewConfiguration`/user settings is resolved by the Activity and
 * handed in here, keeping the handlers pure.
 */
data class TouchpadConfig(
    val holdAction: HoldAction,
    val longPressMs: Long,
    /** Travel (px) below which a gesture still counts as a tap, ~12dp. */
    val tapSlopPx: Float,
    /** Wheel notch distance (px) for two-finger scroll, ~24dp. */
    val scrollNotchPx: Float,
    val doubleTapTimeoutMs: Long,
    val doubleTapSlopPx: Int,
)

/**
 * Sink for everything a gesture produces. Split in spirit like the Windows App's
 * `IOnInputActionListener` (to the remote) + `IOnUiActionListener` (to the UI):
 * [sendPointer]/[sendButton] go to the X server via XTEST, [updateCursor] moves
 * the on-screen pointer sprite. The signatures match `X11Client` exactly so the
 * refactor is byte-for-byte behaviour-preserving on the wire.
 */
interface InputActionListener {
    /** Motion to (x,y) and, when [pressed] is non-null, press/release [button]. */
    fun sendPointer(x: Int, y: Int, button: Int, pressed: Boolean?)

    /** Press/release [button] at the server's current pointer position. */
    fun sendButton(button: Int, pressed: Boolean)

    /** Move the viewer's own pointer sprite (no wire traffic). */
    fun updateCursor(x: Int, y: Int)
}

/**
 * Abstraction over the single shared `Handler` used for the long-press and
 * two-finger-hold timers. Keyed by [token] so a scheduled timer can be
 * cancelled by the same identity that posted it — the real bug the shared
 * Handler fixed (`removeCallbacks` on a different Handler instance is a silent
 * no-op). Fakes drive it synchronously in tests.
 */
interface GestureScheduler {
    fun postDelayed(token: Any, delayMs: Long, action: () -> Unit)
    fun cancel(token: Any)
}
