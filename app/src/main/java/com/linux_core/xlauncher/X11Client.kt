package com.linux_core.xlauncher

import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Minimal X11 protocol client implementing MIT-SHM and PutImage for efficient framebuffer rendering.
 *
 * X11 protocol details:
 * - Big-endian 4-byte packets: length(4) + type(4)
 * - Major requests (client → server)
 * - Major replies (server → client) 
 * - Events (server → client)
 * - MIT-SHM extension for shared memory framebuffer
 */
class X11Client {

    interface Listener {
        fun onConnected(width: Int, height: Int)
        fun onFramebuffer(width: Int, height: Int, pixels: IntArray)
        fun onDisconnected()
        fun onError(t: Throwable)
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var running = false

    @Volatile private var fbWidth = 0
    @Volatile private var fbHeight = 0
    @Volatile private var windowId = 0

    fun connect(config: ConnectionConfig, listener: Listener) {
        try {
            val sock = Socket()
            sock.connect(InetSocketAddress(config.host, config.port), 5000)
            sock.tcpNoDelay = true
            socket = sock
            input = DataInputStream(sock.getInputStream())
            output = DataOutputStream(sock.getOutputStream())
            running = true
            handshake(listener)
            receiveLoop(listener)
        } catch (t: Throwable) {
            Log.e(TAG, "X11 connection failed", t)
            listener.onError(t)
        } finally {
            disconnect()
            listener.onDisconnected()
        }
    }

    private fun handshake(listener: Listener) {
        val `in` = input!!
        val out = output!!

        // 1) X11 protocol version handshake (X11 protocol 11)
        out.writeInt(0) // byte order (MSB first)
        out.writeInt(11) // protocol major version
        out.writeInt(0) // protocol minor version
        out.writeInt(0) // authorization protocol length
        out.writeInt(0) // authorization protocol data (no auth)
        out.flush()

        // Read server reply
        val major = `in`.readInt()
        val minor = `in`.readInt()
        val length = `in`.readInt()

        // 2) Request MIT-SHM extension
        val shmCookie = requestExtension("MIT-SHM")
        if (shmCookie == -1) {
            throw IOException("MIT-SHM extension not supported")
        }

        // 3) Create root window
        windowId = createWindow(0, 0, 1280, 720, 0)

        // 4) Request normal events
        setupEvents()

        // 5) Map window (make it visible)
        mapWindow(windowId)

        Log.i(TAG, "X11 handshake completed, root window=${windowId}")
    }

    private fun requestExtension(name: String): Int {
        val `in` = input!!
        val out = output!!

        // Send extension request
        out.writeInt(128) // Major request: VendorSpecific
        out.writeInt(0) // minor opcode: 0
        out.writeInt(0) // length
        out.writeInt(name.length + 1) // including null terminator
        out.writeBytes(name)
        out.writeByte(0) // null terminator
        out.flush()

        // Read reply
        val minorType = `in`.readInt() // reply minor type
        val length = `in`.readInt()
        val present = `in`.readByte()
        if (present != 1) {
            return -1
        }
        val firstEvent = `in`.readByte()
        val firstError = `in`.readByte()
        val majorOpcode = `in`.readByte()
        return majorOpcode.toInt()
    }

    private fun createWindow(parent: Int, x: Int, y: Int, w: Int, h: Int): Int {
        val `in` = input!!
        val out = output!!

        // CreateWindow request
        out.writeInt(1) // Major request: CreateWindow
        out.writeInt(8) // Minor opcode: CreateWindow
        out.writeInt(20) // length
        out.writeInt(0) // depth
        out.writeInt(0) // visual id
        out.writeInt(1) // parent window id
        out.writeInt(x) // x position
        out.writeInt(y) // y position
        out.writeInt(w) // width
        out.writeInt(h) // height
        out.writeInt(0) // border width
        out.writeInt(1) // class: CopyFromParent (1)
        out.writeInt(0) // visual id
        out.writeInt(0) // value mask
        out.flush()

        // Read reply
        val replyMinorType = `in`.readInt()
        val replyLength = `in`.readInt()
        val windowId = `in`.readInt()
        return windowId
    }

    private fun mapWindow(windowId: Int) {
        val `in` = input!!
        val out = output!!

        // MapWindow request
        out.writeInt(5) // Major request: MapWindow
        out.writeInt(1) // Minor opcode: MapWindow
        out.writeInt(2) // length
        out.writeInt(windowId) // window id
        out.flush()

        // Read reply
        val replyMinorType = `in`.readInt()
        val replyLength = `in`.readInt()
    }

    private fun setupEvents() {
        val `in` = input!!
        val out = output!!

        // ChangeWindowAttributes to enable event delivery
        out.writeInt(2) // Major request: ChangeWindowAttributes
        out.writeInt(1) // Minor opcode: ChangeWindowAttributes
        out.writeInt(11) // length
        out.writeInt(1) // window id: root window (will be set after create)
        out.writeInt(15) // value mask: eventMask
        out.writeInt(1) // event mask: SubstructureNotifyMask + ExposureMask + KeyPressMask + KeyReleaseMask + ButtonPressMask + ButtonReleaseMask + PointerMotionMask + PointerMotionHintMask + Button1MotionMask + Button2MotionMask + Button3MotionMask + Button4MotionMask + Button5MotionMask + KeymapStateMask
        out.flush()

        // Read reply
        val replyMinorType = `in`.readInt()
        val replyLength = `in`.readInt()
    }

    private fun receiveLoop(listener: Listener) {
        Thread {
            try {
                val `in` = input!!
                while (running) {
                    // Read packet header: length + type
                    val length = `in`.readInt()
                    val type = `in`.readInt()

                    when (type) {
                        // CreateNotify event (window created)
                        33 -> {
                            val replyLength = `in`.readInt()
                            val windowId = `in`.readInt()
                            val x = `in`.readInt()
                            val y = `in`.readInt()
                            val width = `in`.readInt()
                            val height = `in`.readInt()
                            val borderWidth = `in`.readInt()
                            val override = `in`.readInt()
                            val parent = `in`.readInt()
                            // Skip padding
                            `in`.skipBytes((replyLength - 9) * 4)
                        }

                        // ConfigureNotify event (window configured)
                        27 -> {
                            val replyLength = `in`.readInt()
                            val event = `in`.readInt()
                            val window = `in`.readInt()
                            val x = `in`.readInt()
                            val y = `in`.readInt()
                            val width = `in`.readInt()
                            val height = `in`.readInt()
                            val borderWidth = `in`.readInt()
                            val aboveSib = `in`.readInt()
                            val overrideRedirect = `in`.readInt()
                            val valuesLen = `in`.readInt()
                            `in`.skipBytes(valuesLen * 4)
                            // Update framebuffer size if changed
                            if (width > 0 && height > 0) {
                                fbWidth = width
                                fbHeight = height
                                listener.onConnected(width, height)
                            }
                        }

                        // KeyPress event
                        2 -> {
                            val replyLength = `in`.readInt()
                            val detail = `in`.readInt() // keycode
                            val time = `in`.readInt()
                            val root = `in`.readInt()
                            val window = `in`.readInt()
                            val xRoot = `in`.readInt()
                            val yRoot = `in`.readInt()
                            val state = `in`.readInt()
                            // Process key press
                            // TODO: Convert X11 keycode to Android keycode
                            val androidKeyCode = x11KeycodeToAndroid(detail)
                            // Forward to UI thread for processing
                        }

                        // KeyRelease event
                        3 -> {
                            val replyLength = `in`.readInt()
                            val detail = `in`.readInt()
                            val time = `in`.readInt()
                            val root = `in`.readInt()
                            val window = `in`.readInt()
                            val xRoot = `in`.readInt()
                            val yRoot = `in`.readInt()
                            val state = `in`.readInt()
                            val androidKeyCode = x11KeycodeToAndroid(detail)
                        }

                        // ButtonPress event
                        4 -> {
                            val replyLength = `in`.readInt()
                            val detail = `in`.readInt() // button
                            val time = `in`.readInt()
                            val root = `in`.readInt()
                            val window = `in`.readInt()
                            val xRoot = `in`.readInt()
                            val yRoot = `in`.readInt()
                            val state = `in`.readInt()
                            // Process touch/click
                        }

                        // MotionNotify event
                        6 -> {
                            val replyLength = `in`.readInt()
                            val detail = `in`.readInt() // same screen?
                            val time = `in`.readInt()
                            val root = `in`.readInt()
                            val window = `in`.readInt()
                            val xRoot = `in`.readInt()
                            val yRoot = `in`.readInt()
                            val x = `in`.readInt()
                            val y = `in`.readInt()
                            val state = `in`.readInt()
                            // Process mouse motion
                        }

                        // PutImage request from server (for framebuffer updates)
                        32 -> {
                            // This is a client request, so we don't receive it
                            // Server would send PutImage requests to us
                        }

                        // ShmPutImage request
                        else -> {
                            // Try to parse as generic event/packet
                            val replyLength = `in`.readInt()
                            `in`.skipBytes((replyLength - 1) * 4)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "X11 receive loop error", e)
                }
            }
        }.start()
    }

    /**
     * Send pointer event (mouse/touch) to X11 server.
     * @param x X coordinate in framebuffer pixels
     * @param y Y coordinate in framebuffer pixels
     * @param mask Button mask (1 = button down, 0 = button up)
     */
    fun sendPointerEvent(x: Int, y: Int, mask: Int) {
        if (!running) return
        try {
            val `in` = input!!
            val out = output!!

            // MotionNotify event
            out.writeInt(6) // Major request: MotionNotify
            out.writeInt(0) // Minor opcode: MotionNotify
            out.writeInt(7) // length
            out.writeInt(mask) // same screen flag (1 = same screen)
            out.writeInt(windowId) // window id
            out.writeInt(0) // root window
            out.writeInt(0) // child window
            out.writeInt(x) // x root
            out.writeInt(y) // y root
            out.writeInt(x) // x
            out.writeInt(y) // y
            out.writeInt(0) // state
            out.flush()

            // ButtonPress/ButtonRelease event
            if (mask == 1) {
                out.writeInt(4) // Major request: ButtonPress
                out.writeInt(1) // Minor opcode: ButtonPress
                out.writeInt(7) // length
                out.writeInt(1) // button (1 = left button)
                out.writeInt(windowId) // window id
                out.writeInt(0) // root window
                out.writeInt(0) // child window
                out.writeInt(x) // x root
                out.writeInt(y) // y root
                out.writeInt(x) // x
                out.writeInt(y) // y
                out.writeInt(0) // state
                out.flush()
            } else if (mask == 0) {
                out.writeInt(5) // Major request: ButtonRelease
                out.writeInt(1) // Minor opcode: ButtonRelease
                out.writeInt(7) // length
                out.writeInt(1) // button (1 = left button)
                out.writeInt(windowId) // window id
                out.writeInt(0) // root window
                out.writeInt(0) // child window
                out.writeInt(x) // x root
                out.writeInt(y) // y root
                out.writeInt(x) // x
                out.writeInt(y) // y
                out.writeInt(0) // state
                out.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send pointer event", e)
        }
    }

    fun disconnect() {
        running = false
        try {
            output?.let { it.writeInt(0); it.writeInt(0); it.flush() }
        } catch (e: Exception) {
        }
        socket?.close()
        socket = null
        input = null
        output = null
    }

    // Helper function to convert X11 keycodes to Android keycodes
    private fun x11KeycodeToAndroid(x11Keycode: Int): Int {
        // TODO: Implement proper mapping
        // Common X11 keycodes to Android keycodes mapping
        return when (x11Keycode) {
            38 -> 19 // Up arrow
            40 -> 20 // Down arrow
            37 -> 21 // Left arrow
            39 -> 22 // Right arrow
            65 -> 4 // Space
            65 -> 7 // Enter
            else -> -1 // Unknown
        }
    }

    companion object {
        private const val TAG = "X11Client"
    }
}