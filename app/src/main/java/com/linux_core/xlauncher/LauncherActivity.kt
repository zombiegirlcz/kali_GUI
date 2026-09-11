package com.linux_core.xlauncher

import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
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
 * Connection overrides can be passed as intent extras: `host`, `port`.
 */
class LauncherActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var renderer: X11Renderer
    private var client: X11Client? = null
    private var hadError = false

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

        val root = FrameLayout(this).apply {
            addView(glView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(24, 24, 24, 24) })
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

    private fun startConnection(config: ConnectionConfig) {
        Thread({
            val c = X11Client()
            client = c

            val listener = object : X11Client.Listener {
                override fun onConnected(width: Int, height: Int) = runOnUiThread {
                    hadError = false
                    status.visibility = View.GONE
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

    /** Maps a touch on the (letterboxed) view back to remote screen pixels. */
    private fun onDesktopTouch(event: MotionEvent): Boolean {
        val c = client ?: return true
        if (renderer.viewportWidth <= 0 || renderer.viewportHeight <= 0) return true

        val fx = (event.x - renderer.viewportX) / renderer.viewportWidth.toFloat()
        val fy = (event.y - renderer.viewportY) / renderer.viewportHeight.toFloat()
        if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return true

        val x = (fx * renderer.framebufferWidth).toInt().coerceIn(0, renderer.framebufferWidth - 1)
        val y = (fy * renderer.framebufferHeight).toInt().coerceIn(0, renderer.framebufferHeight - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> c.sendPointer(x, y, BUTTON_LEFT, true)
            MotionEvent.ACTION_MOVE -> c.sendPointer(x, y, BUTTON_LEFT, null)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> c.sendPointer(x, y, BUTTON_LEFT, false)
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val x = xKeycode(keyCode)
        if (x == 0) return super.onKeyDown(keyCode, event)
        client?.sendKey(x, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val x = xKeycode(keyCode)
        if (x == 0) return super.onKeyUp(keyCode, event)
        client?.sendKey(x, false)
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
