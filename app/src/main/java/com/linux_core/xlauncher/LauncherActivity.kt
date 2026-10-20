package com.linux_core.xlauncher

import android.app.Activity
import android.content.Intent
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
 *   🌐  remote target: connect to an X server on another machine
 *        instead of the local guest (host/port, persisted)
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
    private lateinit var sessions: SavedSessions

    private var client: X11Client? = null
    private var hadError = false

    /** Target used when remote mode is off: intent extras, else the local guest. */
    private lateinit var localConfig: ConnectionConfig

    /**
     * Bumped on every (re)connect. The client runs on its own thread and its
     * callbacks can land after the user already switched targets, so each
     * listener ignores anything that is not from the current generation.
     */
    private var connectionGeneration = 0

    /** Target of the current connection, retried as-is by the auto-reconnect. */
    private var activeConfig: ConnectionConfig? = null

    /** Consecutive failed attempts; drives the backoff and resets on success. */
    private var reconnectAttempt = 0

    /** Last error text, kept so the retry countdown can keep showing the cause. */
    private var lastErrorText: String? = null

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var pendingReconnect: Runnable? = null

    /** Set in [onDestroy]: stops the retry loop from outliving the activity. */
    private var destroyed = false

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

    /**
     * Single Handler used to schedule AND cancel every gesture timer below
     * (long-press and two-finger-hold). A fresh Handler() has its own
     * identity, so removeCallbacks() on a different instance than the one
     * that posted the Runnable is a silent no-op — the timer fires anyway,
     * long after the finger already lifted. Must stay one shared instance
     * per timer for cancellation to actually work.
     */
    private val gestureTimerHandler = Handler(Looper.getMainLooper())

    /** Pending long-press timer: hold still to grab (press & hold the button). */
    private var longPressRunnable: Runnable? = null

    /** Single-tap left click, deferred by the double-tap window (see [schedulePendingTapClick]). */
    private var pendingTapClick: Runnable? = null

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
        sessions = SavedSessions(this)
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

        localConfig = intent.extras?.let { e ->
            ConnectionConfig(
                    e.getString("host") ?: ConnectionConfig.DEFAULT.host,
                    e.getInt("port", ConnectionConfig.DEFAULT.port))
        } ?: ConnectionConfig.DEFAULT

        if (!handleRdpIntent(intent)) {
            connectTo(settings.remoteConfig() ?: localConfig)
        }
    }

    /** A `.rdp` opened while the app already runs arrives here (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRdpIntent(intent)
    }

    /**
     * Opens the `.rdp` named by a VIEW intent: parses it, remembers it in
     * [sessions] and connects to its `full address`. Returns false when the
     * intent is not a VIEW, so the caller falls back to the normal target.
     */
    private fun handleRdpIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val uri = intent.data ?: return false

        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (t: Exception) {
            Log.e(TAG, "cannot read $uri", t)
            null
        }
        val config = text?.let { RdpFile.connectionConfig(it) }
        if (config == null) {
            status.visibility = View.VISIBLE
            status.text = "Soubor není platný .rdp (chybí řádek „full address“)\n$uri"
            return true
        }

        val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".rdp") ?: config.host
        sessions.add(SavedSessions.Entry(uri.toString(), name, config.host, config.port, System.currentTimeMillis()))
        connectTo(config)
        return true
    }

    /**
     * Drops any live connection and connects to [config]. Used both for the
     * initial connect and when the remote target changes, so switching hosts
     * never leaves two clients feeding the renderer.
     */
    private fun connectTo(config: ConnectionConfig, userInitiated: Boolean = true) {
        connectionGeneration++
        cancelPendingReconnect()
        if (userInitiated) {
            reconnectAttempt = 0
            lastErrorText = null
        }
        client?.disconnect()
        client = null
        activeConfig = config
        hadError = false
        grabbing = false
        directPressed = false
        cancelLongPress()
        cancelTwoFingerHold()
        status.visibility = View.VISIBLE
        status.text = getString(R.string.status_connecting, config.host, config.port)
        startConnection(config, connectionGeneration)
    }

    /**
     * Retries the current target after a dropped connection, with exponential
     * backoff capped at [MAX_RECONNECT_DELAY_MS]. Dropping out on a read
     * timeout is normal on a slow link (a full-screen GetImage is several MB),
     * and the stream cannot be resumed afterwards — reconnecting is the only
     * recovery, and doing it here means a stall no longer needs an app restart.
     */
    private fun scheduleReconnect() {
        val config = activeConfig ?: return
        if (destroyed || isFinishing) return

        reconnectAttempt++
        // The shift count must be clamped: retries are unbounded, and Long.shl
        // takes it mod 64, so attempt 65 would wrap back to a 1 s delay.
        val shift = (reconnectAttempt - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val delay = (INITIAL_RECONNECT_DELAY_MS shl shift)
                .coerceAtMost(MAX_RECONNECT_DELAY_MS)

        status.visibility = View.VISIBLE
        status.text = buildString {
            lastErrorText?.let { append(it).append("\n\n") }
            append(getString(
                    R.string.status_reconnecting,
                    config.host, config.port, delay / 1000, reconnectAttempt))
        }

        val r = Runnable {
            pendingReconnect = null
            connectTo(config, userInitiated = false)
        }
        pendingReconnect = r
        reconnectHandler.postDelayed(r, delay)
    }

    private fun cancelPendingReconnect() {
        pendingReconnect?.let { reconnectHandler.removeCallbacks(it) }
        pendingReconnect = null
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
        val remote = controlButton("🌐") { showRemoteDialog() }
        val saved = controlButton("📑") { showSavedSessionsDialog() }
        val gear = controlButton("⚙") { showSettingsDialog() }

        bar.addView(kb)
        bar.addView(modeButton)
        bar.addView(esc)
        bar.addView(term)
        bar.addView(remote)
        bar.addView(saved)
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

    /**
     * Lets the viewer point at an X server on another machine instead of the
     * one inside the local guest: a switch plus host/port, persisted in
     * [LauncherSettings]. Confirming reconnects immediately, so the change is
     * visible without restarting the app.
     */
    private fun showRemoteDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val toggle = android.widget.CheckBox(this).apply {
            text = "Připojit se ke vzdálenému X serveru"
            isChecked = settings.remoteEnabled
        }
        container.addView(toggle)

        val localHint = TextView(this).apply {
            setPadding(0, 8, 0, 24)
            text = "Vypnuto = lokální plocha v kontejneru (${localConfig.host}:${localConfig.port})"
        }
        container.addView(localHint)

        val hostField = EditText(this).apply {
            hint = "Adresa (např. 192.168.1.50)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.remoteHost)
        }
        container.addView(hostField)

        val portField = EditText(this).apply {
            hint = "Port (6000 + číslo displeje)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.remotePort.toString())
        }
        container.addView(portField)

        fun applyEnabled(enabled: Boolean) {
            hostField.isEnabled = enabled
            portField.isEnabled = enabled
        }
        applyEnabled(toggle.isChecked)
        toggle.setOnCheckedChangeListener { _, checked -> applyEnabled(checked) }

        val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("Vzdálená plocha")
                .setView(container)
                .setNegativeButton("Zrušit", null)
                .setPositiveButton("Připojit", null)
                .create()

        dialog.show()
        // Validation needs to keep the dialog open on bad input, which the
        // builder's own listener cannot do — it always dismisses.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val enabled = toggle.isChecked
            val host = hostField.text.toString().trim()
            val port = portField.text.toString().trim().toIntOrNull()

            if (enabled && host.isEmpty()) {
                hostField.error = "Zadej adresu"
                return@setOnClickListener
            }
            if (enabled && (port == null || port < LauncherSettings.MIN_PORT || port > LauncherSettings.MAX_PORT)) {
                portField.error = "Port 1–65535"
                return@setOnClickListener
            }

            settings.remoteEnabled = enabled
            settings.remoteHost = host
            if (port != null) settings.remotePort = port

            dialog.dismiss()
            connectTo(settings.remoteConfig() ?: localConfig)
        }
    }

    /**
     * Lists the `.rdp` files opened so far, newest first. Tapping one
     * reconnects to its target; the row dialog can also forget it. Forgetting
     * only drops the entry here, the `.rdp` file itself is never deleted.
     */
    private fun showSavedSessionsDialog() {
        val entries = sessions.all()
        if (entries.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = "Žádné uložené .rdp instance. Otevři .rdp soubor z file manageru."
            return
        }
        val labels = entries.map { "${it.name}\n${it.host}:${it.port}" }.toTypedArray()
        android.app.AlertDialog.Builder(this)
                .setTitle("Uložené instance (.rdp)")
                .setItems(labels) { _, which -> showSavedSessionActionDialog(entries[which]) }
                .setNegativeButton("Zavřít", null)
                .show()
    }

    private fun showSavedSessionActionDialog(entry: SavedSessions.Entry) {
        android.app.AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setMessage("${entry.host}:${entry.port}\n\n${entry.uri}")
                .setPositiveButton("Připojit") { _, _ -> connectTo(entry.config()) }
                .setNeutralButton("Smazat ze seznamu") { _, _ ->
                    sessions.remove(entry.uri)
                    status.visibility = View.VISIBLE
                    status.text = "Odebráno ze seznamu: ${entry.name}"
                }
                .setNegativeButton("Zrušit", null)
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

    private fun startConnection(config: ConnectionConfig, generation: Int) {
        Thread({
            val c = X11Client()
            if (generation != connectionGeneration) {
                c.disconnect()
                return@Thread
            }
            client = c

            val listener = object : X11Client.Listener {
                private fun stale(): Boolean = generation != connectionGeneration

                override fun onConnected(width: Int, height: Int) = runOnUiThread {
                    if (stale()) return@runOnUiThread
                    hadError = false
                    reconnectAttempt = 0
                    lastErrorText = null
                    status.visibility = View.GONE
                    cursorX = width / 2
                    cursorY = height / 2
                    renderer.updatePointer(cursorX, cursorY)
                    glView.requestRender()
                }

                override fun onFramebuffer(width: Int, height: Int, pixels: IntArray) {
                    if (stale()) return
                    renderer.updateFramebuffer(width, height, pixels)
                }

                // Partial update from the DAMAGE path: only this rectangle
                // changed, so the renderer patches the existing texture instead
                // of re-uploading the whole screen.
                override fun onFramebufferRegion(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
                    if (stale()) return
                    renderer.updateFramebufferRegion(x, y, w, h, pixels)
                }

                // X11Client's finally block always lands here, including
                // after onError, so this is the single place that retries.
                override fun onDisconnected() = runOnUiThread {
                    if (stale()) return@runOnUiThread
                    if (destroyed || isFinishing) return@runOnUiThread
                    if (!hadError) lastErrorText = getString(R.string.status_disconnected)
                    scheduleReconnect()
                }

                override fun onError(t: Throwable) = runOnUiThread {
                    Log.e(TAG, "X11 error", t)
                    if (stale()) return@runOnUiThread
                    hadError = true
                    lastErrorText = describeError(t)
                    status.visibility = View.VISIBLE
                    status.text = lastErrorText
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

    /**
     * Maps a view coordinate to remote framebuffer pixels, or null if outside
     * — or if there is nothing to map onto yet.
     *
     * Both guards are needed. Until the first frame arrives, the renderer
     * letterboxes nothing and reports the whole surface as the viewport, so a
     * viewport-only check passes while `framebufferWidth` is still 0 and the
     * `coerceIn(0, -1)` below throws. That is exactly the window after a
     * connection drops on a timeout and the app is restarted.
     */
    private fun toFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
        val fbW = renderer.framebufferWidth
        val fbH = renderer.framebufferHeight
        if (renderer.viewportWidth <= 0 || renderer.viewportHeight <= 0) return null
        if (fbW <= 0 || fbH <= 0) return null
        val fx = (viewX - renderer.viewportX) / renderer.viewportWidth.toFloat()
        val fy = (viewY - renderer.viewportY) / renderer.viewportHeight.toFloat()
        if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return null
        val x = (fx * fbW).toInt().coerceIn(0, fbW - 1)
        val y = (fy * fbH).toInt().coerceIn(0, fbH - 1)
        return x to y
    }

    private fun onDesktopTouch(event: MotionEvent): Boolean {
        val c = client ?: return true
        // Two-finger drag scrolls, in both modes.
        if (event.pointerCount >= 2) {
            cancelLongPress()
            cancelPendingTapClick()
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
        gestureTimerHandler.postDelayed(r, settings.longPressMs)
    }

    private fun cancelTwoFingerHold() {
        twoFingerHoldRunnable?.let { gestureTimerHandler.removeCallbacks(it) }
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
                            // The first tap's click was deferred, so drop it here
                            // instead of sending two clicks (= a double-click).
                            cancelPendingTapClick()
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
                    // A tap clicks the left button, but only after the double-tap
                    // window passes with no second touch: sending it now would
                    // make a quick second touch a second click (= double-click).
                    recentTapCount = 1
                    lastTapUpTime = System.currentTimeMillis()
                    lastTapX = event.x
                    lastTapY = event.y
                    schedulePendingTapClick(c)
                }
                dragged = false
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                cancelPendingTapClick()
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
        gestureTimerHandler.postDelayed(r, settings.longPressMs)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { gestureTimerHandler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun schedulePendingTapClick(c: X11Client) {
        cancelPendingTapClick()
        val r = Runnable {
            pendingTapClick = null
            recentTapCount = 0
            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, null)
            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, true)
            c.sendPointer(cursorX, cursorY, BUTTON_LEFT, false)
        }
        pendingTapClick = r
        gestureTimerHandler.postDelayed(r, doubleTapTimeoutMs)
    }

    private fun cancelPendingTapClick() {
        pendingTapClick?.let { gestureTimerHandler.removeCallbacks(it) }
        pendingTapClick = null
    }

    private fun releaseGrab(c: X11Client) {
        if (grabbing) {
            c.sendButton(BUTTON_LEFT, false)
            grabbing = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        // Stales every in-flight callback, so a client thread that is still
        // winding down cannot schedule a retry after the activity is gone.
        connectionGeneration++
        cancelPendingReconnect()
        client?.disconnect()
        client = null
    }

    private companion object {
        const val TAG = "LauncherActivity"

        const val INITIAL_RECONNECT_DELAY_MS = 1000L
        const val MAX_RECONNECT_DELAY_MS = 15000L

        /** Enough to pass [MAX_RECONNECT_DELAY_MS]; keeps the shift in range. */
        const val MAX_BACKOFF_SHIFT = 16
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