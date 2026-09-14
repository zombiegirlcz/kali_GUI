package com.linux_core.xlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Standalone X11 desktop viewer.
 *
 * Connects to the X server that `nh desktop start` runs inside the proot guest
 * (Xvfb on display :0, TCP 127.0.0.1:6000) and renders its framebuffer with
 * OpenGL ES. Touch and key events are injected back through XTEST.
 *
 * Controls (floating bar, top-right):
 *   ⌨   show/hide the soft keyboard (keys are forwarded to the guest via XTEST)
 *   🖱   toggle between TOUCH (pointer follows finger) and MOUSE mode
 *        (finger drags the cursor, tap = left click, long-press = right click)
 *   Esc send ESC (Android IME has no Esc key)
 *
 * Connection overrides can be passed as intent extras: `host`, `port`.
 */
class LauncherActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var renderer: X11Renderer
    private lateinit var controls: LinearLayout
    private lateinit var modeButton: TextView
    private lateinit var imeProxy: EditText

    private var client: X11Client? = null
    private var hadError = false

    /** false = touch (pointer follows finger), true = mouse (drag cursor). */
    private var mouseMode = false

    /** Cursor position in framebuffer pixels, used in mouse mode. */
    private var cursorX = 0
    private var cursorY = 0

    // Gesture bookkeeping for mouse mode click detection.
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        glView = GLSurfaceView(this).apply { setEGLContextClientVersion(2) }
        renderer = X11Renderer(glView)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xB0000000.toInt())
            textSize = 14f
            setPadding(32, 24, 32, 24)
        }

        // 1x1 transparent EditText: gives the soft keyboard a focused target
        // whose key events we intercept in dispatchKeyEvent() and forward to X.
        imeProxy = EditText(this).apply {
            inputType = InputType.TYPE_NULL
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.TRANSPARENT)
            setCursorVisible(false)
        }

        controls = buildControls()

        val root = FrameLayout(this).apply {
            addView(glView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(imeProxy, FrameLayout.LayoutParams(1, 1))
            addView(status, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(24, 24, 24, 24) })
            addView(controls, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END).apply { setMargins(24, 24, 24, 24) })
        }
        setContentView(root)

        glView.setOnTouchListener { _, event -> onDesktopTouch(event) }

        val config = intent.extras?.let { e ->
            ConnectionConfig(
                    e.getString("host") ?: ConnectionConfig.DEFAULT.host,
                    e.getInt("port", ConnectionConfig.DEFAULT.port))
        } ?: ConnectionConfig.DEFAULT

        status.text = getString(R.string.status_connecting, config.host, config.port)
        startConnection(config)
    }

    /* ------------------------------------------------------------------ */
    /* Floating control bar                                               */
    /* ------------------------------------------------------------------ */

    private fun buildControls(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 4, 8, 4)
        }

        val kb = controlButton("⌨") { toggleKeyboard() }
        modeButton = controlButton(modeLabel()) { toggleMode() }
        val esc = controlButton("Esc") { sendEscape() }

        bar.addView(kb)
        bar.addView(modeButton)
        bar.addView(esc)
        return bar
    }

    private fun controlButton(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(28, 16, 28, 16)
            isClickable = true
            isFocusable = false
            setOnClickListener { onClick() }
        }

    private fun modeLabel(): String = if (mouseMode) "🖱" else "☝"

    private fun toggleMode() {
        mouseMode = !mouseMode
        modeButton.text = modeLabel()
        // Reset cursor to the middle of the screen when entering mouse mode.
        cursorX = renderer.framebufferWidth / 2
        cursorY = renderer.framebufferHeight / 2
        status.visibility = View.VISIBLE
        status.text = if (mouseMode) "Mouse mode: drag = move, tap = click, hold = right-click"
                       else "Touch mode: pointer follows finger"
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hadError && client != null) status.visibility = View.GONE
        }, 2000)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (imeProxy.hasFocus()) {
            imm.hideSoftInputFromWindow(imeProxy.windowToken, 0)
            glView.requestFocus()
        } else {
            imeProxy.requestFocus()
            imeProxy.text.clear()
            imm.showSoftInput(imeProxy, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendEscape() {
        client?.sendKey(X_KEY_ESCAPE, true)
        client?.sendKey(X_KEY_ESCAPE, false)
    }

    /**
     * All key events (physical + soft keyboard) go straight to X. The IME proxy
     * EditText must not swallow them, so we intercept before dispatch.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val x = xKeycode(event.keyCode)
        if (x != 0 && client != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) client?.sendKey(x, true)
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    client?.sendKey(x, false)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun startConnection(config: ConnectionConfig) {
        Thread({
            val c = X11Client()
            client = c

            val listener = object : X11Client.Listener {
                override fun onConnected(width: Int, height: Int) = runOnUiThread {
                    hadError = false
                    status.visibility = View.GONE
                    cursorX = width / 2
                    cursorY = height / 2
                    glView.requestRender()
                }

                override fun onFramebuffer(width: Int, height: Int, pixels: IntArray) {
                    renderer.updateFramebuffer(width, height, pixels)
                }

                override fun onCursor(cursor: X11Client.Cursor) {
                    renderer.updateCursor(cursor)
                }

                override fun onDisconnected() = runOnUiThread {
                    if (!isFinishing && !hadError) {
                        status.visibility = View.VISIBLE
                        status.text = getString(R.string.status_disconnected)
                    }
                }

                override fun onError(t: Throwable) = runOnUiThread {
                    Log.e(TAG, "X11 error", t)
                    hadError = true
                    status.visibility = View.VISIBLE
                    status.text = describeError(t)
                }
            }

            c.connect(config, listener)
        }, "x11-client").start()
    }

    /**
     * Builds a message that is actually useful. `Throwable.message` is null for
     * several exceptions (notably `EOFException`), which used to surface as the
     * infamous bare "Error: null".
     */
    private fun describeError(t: Throwable): String {
        val detail = t.message ?: t.javaClass.simpleName
        return when (t) {
            is ConnectException -> getString(R.string.error_no_server, detail)
            is SocketTimeoutException -> getString(R.string.error_timeout, detail)
            else -> getString(R.string.error_generic, detail)
        }
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                              */
    /* ------------------------------------------------------------------ */

    /** Maps a view coordinate to remote framebuffer pixels, or null if outside. */
    private fun toFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
        if (renderer.viewportWidth <= 0 || renderer.viewportHeight <= 0) return null
        val fx = (viewX - renderer.viewportX) / renderer.viewportWidth.toFloat()
        val fy = (viewY - renderer.viewportY) / renderer.viewportHeight.toFloat()
        if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return null
        val x = (fx * renderer.framebufferWidth).toInt().coerceIn(0, renderer.framebufferWidth - 1)
        val y = (fy * renderer.framebufferHeight).toInt().coerceIn(0, renderer.framebufferHeight - 1)
        return x to y
    }

    private fun onDesktopTouch(event: MotionEvent): Boolean {
        val c = client ?: return true
        return if (mouseMode) onMouseTouch(c, event) else onDirectTouch(c, event)
    }

    /** TOUCH mode: the X pointer follows the finger; down = press, up = release. */
    private fun onDirectTouch(c: X11Client, event: MotionEvent): Boolean {
        val (x, y) = toFramebuffer(event.x, event.y) ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> c.sendPointer(x, y, BUTTON_LEFT, true)
            MotionEvent.ACTION_MOVE -> c.sendPointer(x, y, BUTTON_LEFT, null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.sendPointer(x, y, BUTTON_LEFT, false)
        }
        return true
    }

    /**
     * MOUSE mode: dragging moves the cursor without pressing (motion only),
     * a short tap clicks the left button at the cursor, a long press clicks the
     * right button. This makes window dragging / right-click reachable on touch.
     */
    private fun onMouseTouch(c: X11Client, event: MotionEvent): Boolean {
        if (renderer.viewportWidth <= 0 || renderer.framebufferWidth <= 0) return true

        // Sensitivity: full viewport width maps to the whole screen.
        val dx = (event.x - downX) / renderer.viewportWidth.toFloat() * renderer.framebufferWidth
        val dy = (event.y - downY) / renderer.viewportHeight.toFloat() * renderer.framebufferHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val nx = (cursorX + dx).toInt().coerceIn(0, renderer.framebufferWidth - 1)
                val ny = (cursorY + dy).toInt().coerceIn(0, renderer.framebufferHeight - 1)
                if (nx != cursorX || ny != cursorY) {
                    cursorX = nx
                    cursorY = ny
                    moved = true
                    downX = event.x
                    downY = event.y
                    c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
                }
            }
            MotionEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - downTime
                val button = if (held >= LONG_PRESS_MS && !moved) BUTTON_RIGHT else BUTTON_LEFT
                // Always move first, then click in place.
                c.sendPointer(cursorX, cursorY, button, true)
                c.sendPointer(cursorX, cursorY, button, false)
            }
            MotionEvent.ACTION_CANCEL -> {
                // Nothing pressed; just leave the cursor where it is.
            }
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
    }

    private companion object {
        const val TAG = "LauncherActivity"
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 3
        const val LONG_PRESS_MS = 400L

        const val X_KEY_ESCAPE = 9

        /**
         * X keycodes for a US layout, indexed by `letter - 'a'`. The X physical
         * order follows QWERTY, not the alphabet, so a table is required.
         */
        val LETTER_KEYCODES = intArrayOf(
                38, // a
                56, // b
                54, // c
                40, // d
                26, // e
                41, // f
                42, // g
                43, // h
                31, // i
                44, // j
                45, // k
                46, // l
                58, // m
                57, // n
                32, // o
                33, // p
                24, // q
                27, // r
                39, // s
                28, // t
                30, // u
                55, // v
                25, // w
                53, // x
                29, // y
                52, // z
        )

        /** Android keycode -> X keycode, for the keys we care about. 0 = unmapped. */
        fun xKeycode(keyCode: Int): Int = when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                LETTER_KEYCODES[keyCode - KeyEvent.KEYCODE_A]
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                val digit = keyCode - KeyEvent.KEYCODE_0
                if (digit == 0) 19 else 9 + digit
            }
            KeyEvent.KEYCODE_ESCAPE -> 9
            KeyEvent.KEYCODE_ENTER -> 36
            KeyEvent.KEYCODE_DEL -> 22
            KeyEvent.KEYCODE_FORWARD_DEL -> 119
            KeyEvent.KEYCODE_TAB -> 23
            KeyEvent.KEYCODE_SPACE -> 65
            KeyEvent.KEYCODE_MINUS -> 20
            KeyEvent.KEYCODE_EQUALS -> 21
            KeyEvent.KEYCODE_LEFT_BRACKET -> 34
            KeyEvent.KEYCODE_RIGHT_BRACKET -> 35
            KeyEvent.KEYCODE_BACKSLASH -> 51
            KeyEvent.KEYCODE_SEMICOLON -> 47
            KeyEvent.KEYCODE_APOSTROPHE -> 48
            KeyEvent.KEYCODE_GRAVE -> 49
            KeyEvent.KEYCODE_COMMA -> 59
            KeyEvent.KEYCODE_PERIOD -> 60
            KeyEvent.KEYCODE_SLASH -> 61
            KeyEvent.KEYCODE_SHIFT_LEFT -> 50
            KeyEvent.KEYCODE_SHIFT_RIGHT -> 62
            KeyEvent.KEYCODE_CTRL_LEFT -> 37
            KeyEvent.KEYCODE_CTRL_RIGHT -> 105
            KeyEvent.KEYCODE_ALT_LEFT -> 64
            KeyEvent.KEYCODE_ALT_RIGHT -> 108
            KeyEvent.KEYCODE_DPAD_UP -> 111
            KeyEvent.KEYCODE_DPAD_DOWN -> 116
            KeyEvent.KEYCODE_DPAD_LEFT -> 113
            KeyEvent.KEYCODE_DPAD_RIGHT -> 114
            KeyEvent.KEYCODE_MOVE_HOME -> 110
            KeyEvent.KEYCODE_MOVE_END -> 115
            KeyEvent.KEYCODE_PAGE_UP -> 112
            KeyEvent.KEYCODE_PAGE_DOWN -> 117
            KeyEvent.KEYCODE_INSERT -> 118
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                // F1..F12 are 131..142 in Android, X has them at 67..76 and 95..96
                (keyCode - KeyEvent.KEYCODE_F1).let { if (it < 10) 67 + it else 95 + (it - 10) }
            else -> 0
        }
    }
}