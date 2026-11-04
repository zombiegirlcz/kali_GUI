package com.linux_core.xlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.linux_core.xlauncher.input.GestureScheduler
import com.linux_core.xlauncher.input.InputActionListener
import com.linux_core.xlauncher.input.TouchAction
import com.linux_core.xlauncher.input.TouchEvent
import com.linux_core.xlauncher.input.TouchpadConfig
import com.linux_core.xlauncher.input.Viewport
import com.linux_core.xlauncher.input.key.X11KeyTable
import com.linux_core.xlauncher.input.touchpad.TouchpadInputInterceptor
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Standalone X11 desktop viewer.
 *
 * Connects to the X server that `nh desktop start` runs inside the proot guest
 * (Xvfb on display :0, TCP 127.0.0.1:6000) and renders its framebuffer with
 * OpenGL ES. Touch and key events are injected back through XTEST.
 *
 * Following the Microsoft "Windows App" (`com.microsoft.rdc.androidx`), the
 * gesture state machine and key mapping no longer live in this Activity: they
 * are the pure, unit-tested [com.linux_core.xlauncher.input] layer (modelled on
 * `com.microsoft.windowsapp.input.*`). This Activity is only the Android
 * adapter — it builds the UI, converts `MotionEvent`/`KeyEvent` into the layer's
 * model types, and implements [InputActionListener] / [GestureScheduler] /
 * [Viewport] against [X11Client] and [X11Renderer].
 *
 * Controls (floating bar, top-right):
 *   ⌨   show/hide the soft keyboard (keys are forwarded to the guest via XTEST)
 *   🖱   toggle between TOUCH (pointer follows finger) and MOUSE mode
 *        (trackpad-style: finger drags the cursor relative to where it
 *        already is, it does not jump under the finger. Clicks are gestures
 *        layered on top: tap = left click, tap-tap+hold (click, click, and
 *        don't let go the second time) = grab the left button so dragging
 *        moves/resizes windows, plain hold (no prior tap) = right click,
 *        two fingers held still = right click, two fingers dragged = scroll)
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
    private lateinit var settings: LauncherSettings
    private lateinit var interceptor: TouchpadInputInterceptor

    private var client: X11Client? = null
    private var hadError = false

    /**
     * Single Handler used to schedule AND cancel every gesture timer (long-press
     * and two-finger-hold) through [AndroidGestureScheduler]. A fresh Handler()
     * has its own identity, so removeCallbacks() on a different instance is a
     * silent no-op — the timer fires anyway, long after the finger lifted. Must
     * stay one shared instance for cancellation to actually work.
     */
    private val gestureTimerHandler = Handler(Looper.getMainLooper())

    /**
     * True while the soft keyboard is actually showing. Kept in sync by a
     * global layout listener (see [trackKeyboardVisibility]), not just set
     * from the toggle button — the keyboard can also close via the back
     * gesture, "Done", or a tap outside, and a hand-toggled flag would then
     * desync from reality: the next tap on the button would see a stale
     * "visible" state, no-op a hide, and appear to do nothing.
     */
    private var keyboardVisible = false

    /* ------------------------------------------------------------------ */
    /* Android adapters for the pure input layer                          */
    /* ------------------------------------------------------------------ */

    /** [Viewport] backed by the live renderer geometry. */
    private val rendererViewport = object : Viewport {
        override val viewportX get() = renderer.viewportX
        override val viewportY get() = renderer.viewportY
        override val viewportWidth get() = renderer.viewportWidth
        override val viewportHeight get() = renderer.viewportHeight
        override val framebufferWidth get() = renderer.framebufferWidth
        override val framebufferHeight get() = renderer.framebufferHeight
    }

    /** [InputActionListener] driving the X server (XTEST) and the pointer sprite. */
    private val inputSink = object : InputActionListener {
        override fun sendPointer(x: Int, y: Int, button: Int, pressed: Boolean?) {
            client?.sendPointer(x, y, button, pressed)
        }
        override fun sendButton(button: Int, pressed: Boolean) {
            client?.sendButton(button, pressed)
        }
        override fun updateCursor(x: Int, y: Int) {
            renderer.updatePointer(x, y)
        }
    }

    /** [GestureScheduler] over the single shared [gestureTimerHandler]. */
    private val gestureScheduler: GestureScheduler = AndroidGestureScheduler(gestureTimerHandler)

    /** Live tuning snapshot: user settings + Android resources/ViewConfiguration. */
    private fun currentConfig() = TouchpadConfig(
        holdAction = settings.holdAction,
        longPressMs = settings.longPressMs,
        tapSlopPx = 12f * resources.displayMetrics.density,
        scrollNotchPx = 24f * resources.displayMetrics.density,
        doubleTapTimeoutMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
        doubleTapSlopPx = ViewConfiguration.get(this).scaledDoubleTapSlop,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = LauncherSettings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        glView = GLSurfaceView(this).apply { setEGLContextClientVersion(2) }
        renderer = X11Renderer(glView)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        interceptor = TouchpadInputInterceptor(
            sink = inputSink,
            scheduler = gestureScheduler,
            viewport = rendererViewport,
            config = ::currentConfig,
        )

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
        trackKeyboardVisibility(root)

        glView.setOnTouchListener { _, event ->
            if (client == null) true else interceptor.onTouch(event.toTouchEvent())
        }

        val config = intent.extras?.let { e ->
            ConnectionConfig(
                    e.getString("host") ?: ConnectionConfig.DEFAULT.host,
                    e.getInt("port", ConnectionConfig.DEFAULT.port))
        } ?: ConnectionConfig.DEFAULT

        status.text = getString(R.string.status_connecting, config.host, config.port)
        startConnection(config)
    }

    /** Converts an Android [MotionEvent] into the input layer's [TouchEvent]. */
    private fun MotionEvent.toTouchEvent(): TouchEvent {
        val mapped = when (actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            else -> TouchAction.OTHER
        }
        val pc = pointerCount
        return TouchEvent(
            action = mapped,
            pointerCount = pc,
            x = getX(0),
            y = getY(0),
            x1 = if (pc > 1) getX(1) else 0f,
            y1 = if (pc > 1) getY(1) else 0f,
            timeMs = System.currentTimeMillis(),
        )
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
        val term = controlButton("⌘") { openTerminal() }
        val gear = controlButton("⚙") { showSettingsDialog() }

        bar.addView(kb)
        bar.addView(modeButton)
        bar.addView(esc)
        bar.addView(term)
        bar.addView(gear)
        return bar
    }

    /**
     * Lets the hold gesture in MOUSE mode (and its timing) be changed without
     * a rebuild: what a still finger does ([HoldAction]) and how long it must
     * stay still first ([LauncherSettings.longPressMs]).
     */
    private fun showSettingsDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val optionTapTapHold = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Ťuk, ťuk a napodruhé nepustit = tažení; podržení bez ťuknutí / dva prsty drž = pravý klik; dva prsty táhni = scroll"
        }
        val optionGrabDrag = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Podržení (i bez ťuknutí) vždy táhne — bez pravého kliku"
        }
        val optionRightClick = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = "Podržení (i bez ťuknutí) vždy = pravý klik — bez tažení podržením"
        }
        radioGroup.addView(optionTapTapHold)
        radioGroup.addView(optionGrabDrag)
        radioGroup.addView(optionRightClick)
        container.addView(radioGroup)

        radioGroup.check(when (settings.holdAction) {
            HoldAction.TAP_TAP_HOLD -> optionTapTapHold.id
            HoldAction.GRAB_DRAG -> optionGrabDrag.id
            HoldAction.RIGHT_CLICK -> optionRightClick.id
        })
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.holdAction = when (checkedId) {
                optionGrabDrag.id -> HoldAction.GRAB_DRAG
                optionRightClick.id -> HoldAction.RIGHT_CLICK
                else -> HoldAction.TAP_TAP_HOLD
            }
        }

        val delayLabel = TextView(this).apply {
            setPadding(0, 32, 0, 0)
        }
        fun updateDelayLabel(ms: Int) { delayLabel.text = "Doba podržení: $ms ms" }
        updateDelayLabel(settings.longPressMs.toInt())
        container.addView(delayLabel)

        val seekBar = android.widget.SeekBar(this).apply {
            max = LauncherSettings.MAX_LONG_PRESS_MS - LauncherSettings.MIN_LONG_PRESS_MS
            progress = settings.longPressMs.toInt() - LauncherSettings.MIN_LONG_PRESS_MS
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val ms = LauncherSettings.MIN_LONG_PRESS_MS + progress
                updateDelayLabel(ms)
                if (fromUser) settings.longPressMs = ms.toLong()
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
        })
        container.addView(seekBar)

        android.app.AlertDialog.Builder(this)
                .setTitle("Nastavení ovládání myši")
                .setView(container)
                .setPositiveButton("Hotovo", null)
                .show()
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

    private fun modeLabel(): String = if (interceptor.mouseMode) "🖱" else "☝"

    private fun toggleMode() {
        interceptor.mouseMode = !interceptor.mouseMode
        modeButton.text = modeLabel()
        // Reset cursor to the middle of the screen when entering mouse mode.
        interceptor.setCursor(renderer.framebufferWidth / 2, renderer.framebufferHeight / 2)
        status.visibility = View.VISIBLE
        status.text = if (interceptor.mouseMode) "Mouse mode: drag = move, tap = click, hold = right-click, tap-tap+hold = grab & drag, 2-finger drag = scroll"
                       else "Touch mode: pointer follows finger"
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hadError && client != null) status.visibility = View.GONE
        }, 2000)
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // keyboardVisible reflects reality (see trackKeyboardVisibility), not
        // just our own last action, so this stays correct even if the
        // keyboard was closed by the back gesture, "Done", or a tap outside.
        if (keyboardVisible) {
            imm.hideSoftInputFromWindow(imeProxy.windowToken, 0)
            imeProxy.clearFocus()
            glView.requestFocus()
        } else {
            imeProxy.requestFocus()
            imeProxy.text.clear()
            imeProxy.post {
                imm.showSoftInput(imeProxy, InputMethodManager.SHOW_FORCED)
            }
        }
    }

    /**
     * Keeps [keyboardVisible] in sync with the soft keyboard's actual on-screen
     * state, by comparing the window's visible display frame against the root
     * view's full height (the standard cross-version keyboard-visibility
     * heuristic; IME window-inset visibility APIs only exist from API 30 and
     * this app's minSdk is 28). Anything that can close the keyboard without
     * going through [toggleKeyboard] — the back gesture, "Done", tapping
     * outside — is caught here instead of leaving a stale hand-tracked flag.
     */
    private fun trackKeyboardVisibility(root: View) {
        val frame = Rect()
        root.viewTreeObserver.addOnGlobalLayoutListener {
            root.getWindowVisibleDisplayFrame(frame)
            val screenHeight = root.rootView.height
            if (screenHeight <= 0) return@addOnGlobalLayoutListener
            val hiddenHeight = screenHeight - frame.bottom
            val visibleNow = hiddenHeight > screenHeight * 0.15
            if (visibleNow != keyboardVisible) {
                keyboardVisible = visibleNow
                if (!visibleNow) {
                    // Closed by something other than our button: drop the
                    // proxy's focus so the next show starts from a clean state.
                    imeProxy.clearFocus()
                    glView.requestFocus()
                }
            }
        }
    }

    private fun sendEscape() {
        client?.sendKey(X11KeyTable.ESCAPE, true)
        client?.sendKey(X11KeyTable.ESCAPE, false)
    }

    /**
     * Switches back to the core app terminal.
     *
     * TerminalActivity is deliberately `exported="false"`, so an external app
     * cannot start it. We ask the already running core app over its localhost
     * REST bridge instead (loopback requests need no Bearer token).
     */
    private fun openTerminal() {
        Thread({
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(CORE_TERMINAL_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 3000
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val code = conn.responseCode
                Log.i(TAG, "openTerminal -> HTTP $code")
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
            } catch (t: Throwable) {
                Log.e(TAG, "openTerminal failed", t)
                runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "Nelze otevřít terminál (běží aplikace com.linux_core?)\n${t.message ?: t.javaClass.simpleName}"
                }
            } finally {
                conn?.disconnect()
            }
        }, "open-terminal").start()
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
                    interceptor.setCursor(width / 2, height / 2)
                    glView.requestRender()
                }

                override fun onFramebuffer(width: Int, height: Int, pixels: IntArray) {
                    renderer.updateFramebuffer(width, height, pixels)
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

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
    }

    /**
     * Android keycode -> X keycode, for the keys we care about. 0 = unmapped.
     * The pure per-key tables live in [X11KeyTable] (the Windows App's
     * `AsciiToScanCodeMappings` role); this `when` is the thin Android
     * `KeyEvent` dispatch (its `KeyEventExtKt` role).
     */
    private fun xKeycode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            X11KeyTable.letter(keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            X11KeyTable.digit(keyCode - KeyEvent.KEYCODE_0)
        KeyEvent.KEYCODE_ESCAPE -> X11KeyTable.ESCAPE
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
            X11KeyTable.functionKey(keyCode - KeyEvent.KEYCODE_F1)
        else -> 0
    }

    private companion object {
        const val TAG = "LauncherActivity"

        /** Localhost REST bridge of the core app (Loopback: no Bearer token). */
        const val CORE_TERMINAL_URL = "http://127.0.0.1:1337/terminal/open"
    }
}

/**
 * [GestureScheduler] over a single shared [Handler]. Tracks each token's
 * posted [Runnable] so [cancel] removes exactly the instance that was posted —
 * the reason the launcher uses one shared Handler rather than ad-hoc ones.
 */
private class AndroidGestureScheduler(private val handler: Handler) : GestureScheduler {
    private val pending = HashMap<Any, Runnable>()

    override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
        cancel(token)
        val r = Runnable {
            pending.remove(token)
            action()
        }
        pending[token] = r
        handler.postDelayed(r, delayMs)
    }

    override fun cancel(token: Any) {
        pending.remove(token)?.let { handler.removeCallbacks(it) }
    }
}
