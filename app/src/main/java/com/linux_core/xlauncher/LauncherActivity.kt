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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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

    /**
     * True once the finger travelled further than [tapSlopPx] during the
     * gesture. A click is only delivered when this stays false, so releasing
     * after dragging the pointer does NOT fire a click.
     */
    private var dragged = false

    /** Anchor of the gesture start, used only for cumulative slop detection. */
    private var slopAnchorX = 0f
    private var slopAnchorY = 0f

    /** Touch slop in pixels (~12dp); below this a gesture still counts as a tap. */
    private val tapSlopPx: Float by lazy {
        (12f * resources.displayMetrics.density)
    }

    /** Pending long-press timer: hold still to grab (press & hold the button). */
    private var longPressRunnable: Runnable? = null

    /** True while a long-press grab is active, i.e. the left button is held. */
    private var grabbing = false

    /**
     * True once one finger completed a plain tap (down-up, no drag) within
     * [doubleTapTimeoutMs] and [doubleTapSlopPx] of itself; a stale value
     * (too slow or too far) resets to false on the next ACTION_DOWN. If the
     * *next* touch-down lands while this is still true, that touch grabs the
     * left button immediately — "click, click, and don't let go the second
     * time" — instead of waiting on any timer. A plain first touch (this
     * false) never grabs just by holding still — holding it instead
     * right-clicks after a delay (see [scheduleLongPress]), so it never
     * hijacks a drag that merely paused before moving.
     */
    private var recentTapCount = 0
    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    /** True once the current gesture's hold has already fired the right-click
     *  (GRAB_DRAG/RIGHT_CLICK [HoldAction] settings only — see [scheduleLongPress]). */
    private var rightClicked = false

    private val doubleTapTimeoutMs: Long by lazy {
        android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
    }
    private val doubleTapSlopPx: Int by lazy {
        android.view.ViewConfiguration.get(this).scaledDoubleTapSlop
    }

    /** Two-finger scroll bookkeeping (shared by both modes). */
    private var scrolling = false
    private var scrollLastY = 0f
    private var scrollAccum = 0f
    private var scrollAnchorY = 0f

    /**
     * Pending timer for "two fingers held still" -> right-click, armed in
     * MOUSE mode regardless of [HoldAction] (see [onScrollTouch]). Cancelled
     * the moment the two fingers actually move.
     */
    private var twoFingerHoldRunnable: Runnable? = null
    private var twoFingerRightClicked = false

    /**
     * True while the soft keyboard is actually showing. Kept in sync by a
     * global layout listener (see [trackKeyboardVisibility]), not just set
     * from the toggle button — the keyboard can also close via the back
     * gesture, "Done", or a tap outside, and a hand-toggled flag would then
     * desync from reality: the next tap on the button would see a stale
     * "visible" state, no-op a hide, and appear to do nothing.
     */
    private var keyboardVisible = false

    /** True while TOUCH mode holds the left button down (finger on screen). */
    private var directPressed = false

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

    private fun modeLabel(): String = if (mouseMode) "🖱" else "☝"

    private fun toggleMode() {
        mouseMode = !mouseMode
        modeButton.text = modeLabel()
        // Reset cursor to the middle of the screen when entering mouse mode.
        cursorX = renderer.framebufferWidth / 2
        cursorY = renderer.framebufferHeight / 2
        renderer.updatePointer(cursorX, cursorY)
        status.visibility = View.VISIBLE
        status.text = if (mouseMode) "Mouse mode: drag = move, tap = click, hold = right-click, tap-tap+hold = grab & drag, 2-finger drag = scroll"
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
        client?.sendKey(X_KEY_ESCAPE, true)
        client?.sendKey(X_KEY_ESCAPE, false)
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
                    cursorX = width / 2
                    cursorY = height / 2
                    renderer.updatePointer(cursorX, cursorY)
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
        // Two-finger drag scrolls, in both modes.
        if (event.pointerCount >= 2) {
            cancelLongPress()
            releaseGrab(c)
            recentTapCount = 0
            // A second finger turns a TOUCH-mode press into a scroll: drop the
            // button we had already pressed, otherwise X keeps it held down.
            if (directPressed) {
                c.sendButton(BUTTON_LEFT, false)
                directPressed = false
            }
            return onScrollTouch(c, event)
        }
        // Ignore the tail of a scroll gesture once the second finger lifted.
        if (scrolling) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL) {
                scrolling = false
                scrollAccum = 0f
            }
            return true
        }
        return if (mouseMode) onMouseTouch(c, event) else onDirectTouch(c, event)
    }

    /**
     * TWO-FINGER gesture: the average Y of both fingers drives mouse-wheel
     * button 4 (up) / 5 (down) once they actually move — dragging the fingers
     * down scrolls the view up, like a touchscreen. Holding both fingers
     * still instead (MOUSE mode, any [HoldAction] scheme) right-clicks at the
     * cursor after [LauncherSettings.longPressMs] — this is the only
     * right-click gesture under [HoldAction.GRAB_DRAG], and redundant with
     * (but harmless alongside) the single-finger hold under
     * [HoldAction.RIGHT_CLICK].
     */
    private fun onScrollTouch(c: X11Client, event: MotionEvent): Boolean {
        val y = (event.getY(0) + event.getY(1)) / 2f
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                scrolling = true
                scrollAccum = 0f
                scrollLastY = y
                scrollAnchorY = y
                twoFingerRightClicked = false
                if (mouseMode) {
                    scheduleTwoFingerHold(c)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrolling) {
                    scrolling = true
                    scrollLastY = y
                    scrollAnchorY = y
                    return true
                }
                if (twoFingerHoldRunnable != null &&
                        kotlin.math.abs(y - scrollAnchorY) > tapSlopPx) {
                    // Real movement: this is a scroll, not a held-still right-click.
                    cancelTwoFingerHold()
                }
                if (twoFingerRightClicked) return true
                val dy = y - scrollLastY
                scrollLastY = y
                scrollAccum += dy
                val notch = 24f * resources.displayMetrics.density
                // Finger up (dy < 0) => content scrolls down => wheel down (5).
                while (scrollAccum <= -notch) {
                    scrollAccum += notch
                    wheel(c, up = false)
                }
                while (scrollAccum >= notch) {
                    scrollAccum -= notch
                    wheel(c, up = true)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                cancelTwoFingerHold()
                scrolling = false
                scrollAccum = 0f
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelTwoFingerHold()
                scrolling = false
                scrollAccum = 0f
            }
        }
        return true
    }

    private fun scheduleTwoFingerHold(c: X11Client) {
        cancelTwoFingerHold()
        val r = Runnable {
            twoFingerRightClicked = true
            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
            c.sendButton(BUTTON_RIGHT, true)
            c.sendButton(BUTTON_RIGHT, false)
        }
        twoFingerHoldRunnable = r
        Handler(Looper.getMainLooper()).postDelayed(r, settings.longPressMs)
    }

    private fun cancelTwoFingerHold() {
        twoFingerHoldRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        twoFingerHoldRunnable = null
    }

    private fun wheel(c: X11Client, up: Boolean) {
        val button = if (up) BUTTON_WHEEL_UP else BUTTON_WHEEL_DOWN
        c.sendButton(button, true)
        c.sendButton(button, false)
    }

    /** TOUCH mode: the X pointer follows the finger; down = press, up = release. */
    private fun onDirectTouch(c: X11Client, event: MotionEvent): Boolean {
        val (x, y) = toFramebuffer(event.x, event.y) ?: return true
        renderer.updatePointer(x, y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                c.sendPointer(x, y, BUTTON_LEFT, true)
                directPressed = true
            }
            MotionEvent.ACTION_MOVE -> c.sendPointer(x, y, BUTTON_LEFT, null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                c.sendPointer(x, y, BUTTON_LEFT, false)
                directPressed = false
            }
        }
        return true
    }

    /**
     * MOUSE mode: trackpad-style — dragging moves the cursor relative to
     * where it already is (motion only, no button), it does not jump under
     * the finger and a plain hold never grabs just by being held (it
     * right-clicks instead — see below — so it never hijacks an ordinary
     * drag that merely paused for a moment before moving).
     *
     * With the default [HoldAction.TAP_TAP_HOLD] scheme: a short tap clicks
     * the left button; a second touch-down landing soon enough and close
     * enough to a completed tap grabs the left button *immediately* (no
     * timer) — "click, click, and don't let go the second time" — so
     * dragging that second touch moves/resizes windows. A hold with no
     * prior tap right-clicks instead, via [scheduleLongPress]. Two fingers
     * held still also right-click, and two fingers dragged scroll — both
     * handled in [onScrollTouch].
     *
     * The [HoldAction.GRAB_DRAG]/[HoldAction.RIGHT_CLICK] settings replace
     * the single-finger half of this with a plain hold-to-grab/right-click
     * for every hold (no tap needed first), for anyone who prefers that
     * over tap-counting.
     */
    private fun onMouseTouch(c: X11Client, event: MotionEvent): Boolean {
        if (renderer.viewportWidth <= 0 || renderer.framebufferWidth <= 0) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                slopAnchorX = event.x
                slopAnchorY = event.y
                downTime = System.currentTimeMillis()
                dragged = false
                grabbing = false
                rightClicked = false
                val sinceLastTap = downTime - lastTapUpTime
                val closeToLastTap = recentTapCount > 0 &&
                        sinceLastTap <= doubleTapTimeoutMs &&
                        distance(event.x, event.y, lastTapX, lastTapY) <= doubleTapSlopPx
                if (!closeToLastTap) recentTapCount = 0

                when (settings.holdAction) {
                    HoldAction.TAP_TAP_HOLD -> {
                        if (recentTapCount >= 1) {
                            // Second touch of "click, click, hold": grab now,
                            // don't wait for a timer — not letting go IS the signal.
                            grabbing = true
                            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
                            c.sendButton(BUTTON_LEFT, true)
                        } else {
                            // Plain first touch: no prior tap to grab off of,
                            // so a hold-still instead right-clicks (a drag
                            // before the timer fires cancels it, see below).
                            scheduleLongPress(c)
                        }
                    }
                    HoldAction.GRAB_DRAG, HoldAction.RIGHT_CLICK -> scheduleLongPress(c)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Cumulative travel since the gesture started decides tap vs drag.
                val slopX = event.x - slopAnchorX
                val slopY = event.y - slopAnchorY
                if (!dragged && (slopX * slopX + slopY * slopY) > tapSlopPx * tapSlopPx) {
                    dragged = true
                    cancelLongPress()
                    recentTapCount = 0
                }
                movePointer(c, event)
                // Re-anchor so the next MOVE only applies the new delta.
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress()
                if (grabbing) {
                    // End the grab: release the held button in place.
                    c.sendButton(BUTTON_LEFT, false)
                    grabbing = false
                    recentTapCount = 0
                } else if (rightClicked) {
                    // Already fired by the hold timer; nothing left to do.
                } else if (!dragged) {
                    // A tap clicks the left button at the cursor. A drag must
                    // never click, so only a slop-free gesture fires.
                    renderer.updatePointer(cursorX, cursorY)
                    c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
                    c.sendPointer(cursorX, cursorY, BUTTON_LEFT, true)
                    c.sendPointer(cursorX, cursorY, BUTTON_LEFT, false)
                    // Register this as a tap so a second touch landing close by,
                    // soon enough, grabs instead of clicking (tap-tap-hold).
                    recentTapCount = 1
                    lastTapUpTime = System.currentTimeMillis()
                    lastTapX = event.x
                    lastTapY = event.y
                }
                dragged = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                releaseGrab(c)
                dragged = false
                recentTapCount = 0
            }
        }
        return true
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** Applies one-finger motion to the cursor (pure motion, no button change). */
    private fun movePointer(c: X11Client, event: MotionEvent) {
        val dx = (event.x - downX) / renderer.viewportWidth.toFloat() * renderer.framebufferWidth
        val dy = (event.y - downY) / renderer.viewportHeight.toFloat() * renderer.framebufferHeight
        val nx = (cursorX + dx).toInt().coerceIn(0, renderer.framebufferWidth - 1)
        val ny = (cursorY + dy).toInt().coerceIn(0, renderer.framebufferHeight - 1)
        if (nx != cursorX || ny != cursorY) {
            cursorX = nx
            cursorY = ny
            renderer.updatePointer(cursorX, cursorY)
            // Pure motion: if the left button is held from a long-press grab,
            // X keeps it down across motion events.
            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
        }
    }

    /**
     * Plain single-finger hold-to-grab/right-click. Used by
     * [HoldAction.GRAB_DRAG] (grab) and [HoldAction.RIGHT_CLICK] (right-click)
     * for every hold, and by the default [HoldAction.TAP_TAP_HOLD] scheme
     * for a hold with no prior tap (right-click) — tap-then-hold instead
     * grabs immediately without this timer (see [onMouseTouch]).
     */
    private fun scheduleLongPress(c: X11Client) {
        cancelLongPress()
        val r = Runnable {
            if (!dragged && !grabbing && !rightClicked) {
                // Button events use the pointer's current position, so make
                // sure the X pointer really is at the on-screen cursor first:
                // on a fresh connection no motion has been sent yet and the
                // server pointer would still be at (0,0).
                c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
                if (settings.holdAction == HoldAction.GRAB_DRAG) {
                    grabbing = true
                    c.sendButton(BUTTON_LEFT, true)
                } else {
                    // RIGHT_CLICK setting, or TAP_TAP_HOLD's plain-hold case.
                    rightClicked = true
                    c.sendButton(BUTTON_RIGHT, true)
                    c.sendButton(BUTTON_RIGHT, false)
                }
            }
        }
        longPressRunnable = r
        Handler(Looper.getMainLooper()).postDelayed(r, settings.longPressMs)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun releaseGrab(c: X11Client) {
        if (grabbing) {
            c.sendButton(BUTTON_LEFT, false)
            grabbing = false
        }
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
        const val BUTTON_WHEEL_UP = 4
        const val BUTTON_WHEEL_DOWN = 5

        const val X_KEY_ESCAPE = 9

        /** Localhost REST bridge of the core app (Loopback: no Bearer token). */
        const val CORE_TERMINAL_URL = "http://127.0.0.1:1337/terminal/open"

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