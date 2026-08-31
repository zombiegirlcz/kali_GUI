package com.linux_core.xlauncher

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color

/**
 * Standalone X11 desktop launcher.
 *
 * Connects to the Linux-X11 X server started by the host app (`nh desktop start`,
 * display :1) over the X11 port (6000) and renders the framebuffer using OpenGL ES.
 * This app only renders — the actual X server and desktop session live in the proot
 * guest managed by `com.linux_core`.
 *
 * Connection overrides can be passed via intent extras: host, port.
 */
class LauncherActivity : Activity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private var client: X11Client? = null
    private var renderer: X11Renderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(2)

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val root = FrameLayout(this).apply {
            addView(glView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(24, 24, 24, 24) })
        }
        setContentView(root)

        val config = intent.extras?.let { e ->
            ConnectionConfig(
                e.getString("host") ?: ConnectionConfig.DEFAULT.host,
                e.getInt("port", ConnectionConfig.DEFAULT.port)
            )
        } ?: ConnectionConfig.DEFAULT

        status.text = "Connecting to ${config.host}:${config.port} …"
        startConnection(config)
    }

    private fun startConnection(config: ConnectionConfig) {
        Thread {
            val c = X11Client()
            client = c

            val listener = object : X11Client.Listener {
                override fun onConnected(width: Int, height: Int) = runOnUiThread {
                    // Initialize renderer with framebuffer dimensions
                    renderer = X11Renderer(c)
                    glView.setRenderer(renderer)
                    glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    status.visibility = View.GONE
                }

                override fun onFramebuffer(width: Int, height: Int, pixels: IntArray) {
                    renderer?.updateFramebuffer(width, height, pixels)
                }

                override fun onDisconnected() = runOnUiThread {
                    if (status.visibility != View.VISIBLE) {
                        status.visibility = View.VISIBLE
                        status.text = "Disconnected."
                    }
                }

                override fun onError(t: Throwable) = runOnUiThread {
                    status.visibility = View.VISIBLE
                    status.text = "Error: ${t.message}"
                }
            }

            c.connect(config, listener)
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        client?.disconnect()
        client = null
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        // Forward touch events to X11 client
        client?.let { c ->
            val x = (event.x * (renderer?.framebufferWidth ?: 1) / glView.width).toInt()
            val y = (event.y * (renderer?.framebufferHeight ?: 1) / glView.height).toInt()

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> c.sendPointerEvent(x, y, 1)
                android.view.MotionEvent.ACTION_UP -> c.sendPointerEvent(x, y, 0)
                android.view.MotionEvent.ACTION_MOVE -> c.sendPointerEvent(x, y, 1)
            }
        }
        return true
    }
}
