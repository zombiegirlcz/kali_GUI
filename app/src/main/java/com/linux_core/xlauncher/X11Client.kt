package com.linux_core.xlauncher

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small but *real* X11 protocol client.
 *
 * It implements just enough of the core protocol and the XTEST extension to
 * drive the remote desktop that `nh desktop start` runs inside the proot
 * guest (Xvfb on display :0, TCP 127.0.0.1:6000):
 *
 *   - connection setup (handshake) + setup-reply parsing
 *   - `GetImage` (ZPixmap) on the root window -> framebuffer polling
 *   - XTEST `FakeInput` -> pointer / button / key injection
 *
 * Notes on the wire format (all of this was verified against Xvfb 21.1.16):
 *
 *  * The setup request is 12 bytes and every field after the first must use
 *    the byte order announced in the first byte. We announce `'B'` (MSB first)
 *    so [DataInputStream]/[DataOutputStream] can be used as-is, because they
 *    are big-endian by default.
 *
 *  * `GetImage` is the portable way to grab the screen. MIT-SHM is advertised
 *    by the server but is *not* usable here: it passes a shared-memory id over
 *    the socket and cannot cross a TCP connection (FD passing is required).
 *
 *  * XTEST `FakeInput` is request opcode 132 / minor opcode **2** and its body
 *    is a complete 32-byte `xEvent` (so the request is 36 bytes, length 9).
 *    `rootX`/`rootY` are INT16 at event offsets 20/22.
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
    private val running = AtomicBoolean(false)

    /** Protects request writes: the frame loop and the UI thread share the socket. */
    private val writeLock = Any()

    /** Root window id of screen 0, taken from the setup reply. */
    @Volatile
    var rootWindow: Int = 0
        private set

    @Volatile
    var screenWidth: Int = 0
        private set

    @Volatile
    var screenHeight: Int = 0
        private set

    /** Bits per pixel of the root window's pixmap format (24/32). */
    private var bitsPerPixel = 32

    /** True when the server sends ZPixmap data least-significant-byte first. */
    private var imageLsbFirst = false

    /** Double buffered framebuffers, swapped on every published frame. */
    private var frameA: IntArray? = null
    private var frameB: IntArray? = null
    private var publishA = true

    /**
     * Connects, performs the setup handshake and then blocks in the framebuffer
     * polling loop. Call from a background thread.
     */
    fun connect(config: ConnectionConfig, listener: Listener) {
        try {
            val sock = Socket()
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(config.host, config.port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = READ_TIMEOUT_MS
            socket = sock
            input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            running.set(true)

            handshake(listener)
            frameLoop(listener)
        } catch (t: Throwable) {
            // A deliberate disconnect() must not surface as an error.
            if (running.get()) {
                Log.e(TAG, "X11 session failed", t)
                listener.onError(t)
            }
        } finally {
            closeQuietly()
            listener.onDisconnected()
        }
    }

    /* ------------------------------------------------------------------ */
    /* Handshake                                                          */
    /* ------------------------------------------------------------------ */

    private fun handshake(listener: Listener) {
        val out = output!!
        val inp = input!!

        synchronized(writeLock) {
            out.writeByte(0x42)   // 'B' -> MSB first
            out.writeByte(0)      // unused
            out.writeShort(11)    // protocol major
            out.writeShort(0)     // protocol minor
            out.writeShort(0)     // auth protocol name length
            out.writeShort(0)     // auth protocol data length
            out.writeShort(0)     // unused
            out.flush()
        }

        val status = inp.readUnsignedByte()

        // A "Failed" reply has a different shape than a successful one:
        //   status(1) reason-length(1) major(2) minor(2) reason(n) pad
        if (status == FAILED) {
            val reasonLen = inp.readUnsignedByte()
            inp.readUnsignedShort()
            inp.readUnsignedShort()
            val reason = ByteArray(reasonLen)
            inp.readFully(reason)
            inp.skipBytes(pad4(reasonLen))
            throw IOException("X server refused the connection: ${String(reason)}")
        }
        if (status != SUCCESS) {
            throw IOException("X server requested authentication (status $status)")
        }

        inp.readUnsignedByte()                 // unused
        val major = inp.readUnsignedShort()
        val minor = inp.readUnsignedShort()
        val extraWords = inp.readUnsignedShort()

        if (extraWords <= 0) {
            throw IOException("X server returned an empty setup reply")
        }

        val setup = ByteArray(extraWords * 4)
        inp.readFully(setup)
        parseSetup(setup)

        Log.i(TAG, "handshake ok (v$major.$minor): root=${hex(rootWindow)} " +
                "${screenWidth}x$screenHeight depth bpp=$bitsPerPixel lsb=$imageLsbFirst")

        if (screenWidth <= 0 || screenHeight <= 0) {
            throw IOException("X server reported an empty screen (${screenWidth}x$screenHeight)")
        }
        listener.onConnected(screenWidth, screenHeight)
    }

    /**
     * Parses the setup reply body:
     *
     *   4 release, 4 resource-id-base, 4 resource-id-mask, 4 motion-buffer,
     *   2 vendor length, 2 max request length, 1 #screens, 1 #formats,
     *   1 image byte order, 1 bit order, 1 scanline unit, 1 scanline pad,
     *   1 min keycode, 1 max keycode, 4 unused, vendor, formats, screens.
     */
    private fun parseSetup(b: ByteArray) {
        var p = 0

        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int {
            val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            p += 2
            return v
        }
        fun u32(): Int {
            val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                    ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            p += 4
            return v
        }

        u32()                       // release number
        u32()                       // resource id base
        u32()                       // resource id mask
        u32()                       // motion buffer size
        val vendorLen = u16()
        u16()                       // maximum request length
        val screenCount = u8()
        val formatCount = u8()
        imageLsbFirst = u8() == 1   // 0 = MSBFirst, 1 = LSBFirst
        u8()                        // bitmap format bit order
        u8()                        // bitmap format scanline unit
        u8()                        // bitmap format scanline pad
        u8()                        // min keycode
        u8()                        // max keycode
        p += 4                      // unused
        p += vendorLen + pad4(vendorLen)

        // Pixmap formats: 1 depth, 1 bits-per-pixel, 2 scanline-pad, 4 unused.
        val bppByDepth = HashMap<Int, Int>(formatCount)
        for (i in 0 until formatCount) {
            val depth = u8()
            val bpp = u8()
            u16()                   // scanline pad
            p += 4
            bppByDepth[depth] = bpp
        }

        if (screenCount <= 0) throw IOException("X server has no screens")

        // Screen 0: root, colormap, white, black, input masks, w, h, ...
        rootWindow = u32()
        u32()                       // default colormap
        u32()                       // white pixel
        u32()                       // black pixel
        u32()                       // current input masks
        screenWidth = u16()
        screenHeight = u16()
        u16()                       // width in mm
        u16()                       // height in mm
        u16()                       // min maps
        u16()                       // max maps
        u32()                       // root visual
        u8()                        // backing store
        u8()                        // save unders
        val rootDepth = u8()
        u8()                        // number of allowed depths

        bitsPerPixel = bppByDepth[rootDepth] ?: 32
    }

    /* ------------------------------------------------------------------ */
    /* Framebuffer polling                                                */
    /* ------------------------------------------------------------------ */

    private fun frameLoop(listener: Listener) {
        val w = screenWidth
        val h = screenHeight
        val bytesPerPixel = bitsPerPixel / 8
        val rowBytes = w * bytesPerPixel

        val raw = ByteArray(rowBytes * h)
        val previous = ByteArray(raw.size)
        var havePrevious = false

        frameA = IntArray(w * h)
        frameB = IntArray(w * h)

        while (running.get()) {
            val started = System.currentTimeMillis()

            val bytes = getImage(raw, w, h)
            if (bytes > 0) {
                var changed = !havePrevious
                if (!changed) {
                    // Cheap dirty check: no repaint unless something moved.
                    var i = 0
                    while (i < bytes) {
                        if (raw[i] != previous[i]) { changed = true; break }
                        i++
                    }
                }
                if (changed) {
                    System.arraycopy(raw, 0, previous, 0, bytes)
                    havePrevious = true
                    publishFrame(raw, w, h, listener)
                }
            }

            val elapsed = System.currentTimeMillis() - started
            val sleep = FRAME_INTERVAL_MS - elapsed
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }

    /**
     * Reads the first byte of the next packet, skipping any 32-byte events that
     * arrive interleaved with replies. Returns [X_REPLY] (1) or [X_ERROR] (0).
     */
    private fun readReplyType(inp: DataInputStream): Int {
        while (true) {
            val type = inp.readUnsignedByte()
            if (type == X_REPLY || type == X_ERROR) return type
            // Any other value is an event: skip the remaining 31 bytes.
            inp.skipBytes(31)
        }
    }

    /**
     * Sends a `GetImage` request for the whole root window and reads the reply
     * into [raw]. Returns the number of image bytes, or 0 when the server
     * answered with an error.
     */
    private fun getImage(raw: ByteArray, w: Int, h: Int): Int {
        val out = output!!
        val inp = input!!

        synchronized(writeLock) {
            out.writeByte(OP_GET_IMAGE)
            out.writeByte(ZPIXMAP)
            out.writeShort(5)              // 4 + 4 + 2*4 + 4 = 20 bytes
            out.writeInt(rootWindow)
            out.writeShort(0)              // x
            out.writeShort(0)              // y
            out.writeShort(w)
            out.writeShort(h)
            out.writeInt(0x00FFFFFF)       // plane mask
            out.flush()
        }

        // Events are interleaved with replies on the same stream: every X11
        // event (e.g. MappingNotify when XFCE changes the keymap, or Expose)
        // is a full 32-byte packet. We did not select any event mask, but the
        // server still delivers MappingNotify to every client — so we must
        // drain events until the actual reply (1) or error (0) arrives.
        // (This was the real "unexpected reply type 34" failure: type 34 is
        // MappingNotify, not a malformed reply.)
        val type = readReplyType(inp)

        // Reply header (32 bytes) - a failed request also produces 32 bytes,
        // so the stream stays aligned either way.
        inp.readUnsignedByte()             // depth
        inp.readUnsignedShort()            // sequence
        val length = inp.readInt()         // image data, in 4-byte units
        inp.readInt()                      // visual id
        inp.skipBytes(20)

        if (type == X_ERROR) {
            Log.w(TAG, "GetImage rejected by the server (depth/pixmap mismatch?)")
            return 0
        }
        if (type != X_REPLY) {
            throw IOException("GetImage: unexpected reply type $type")
        }

        val bytes = length * 4
        if (bytes <= 0 || bytes > raw.size) {
            throw IOException("GetImage: implausible image size $bytes (buffer ${raw.size})")
        }
        inp.readFully(raw, 0, bytes)
        return bytes
    }

    /** Converts the raw ZPixmap bytes into Android ARGB and hands them over. */
    private fun publishFrame(raw: ByteArray, w: Int, h: Int, listener: Listener) {
        val buffer = if (publishA) frameA!! else frameB!!
        publishA = !publishA

        when (bitsPerPixel) {
            32 -> {
                var i = 0
                var o = 0
                val n = w * h
                while (o < n) {
                    val b0 = raw[i].toInt() and 0xFF
                    val b1 = raw[i + 1].toInt() and 0xFF
                    val b2 = raw[i + 2].toInt() and 0xFF
                    i += 4
                    val r: Int
                    val g: Int
                    val b: Int
                    if (imageLsbFirst) {
                        b = b0; g = b1; r = b2
                    } else {
                        r = b0; g = b1; b = b2
                    }
                    buffer[o++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
            16 -> {
                // RGB565 -> ARGB8888
                var i = 0
                var o = 0
                val n = w * h
                while (o < n) {
                    val lo = raw[i].toInt() and 0xFF
                    val hi = raw[i + 1].toInt() and 0xFF
                    i += 2
                    val v = if (imageLsbFirst) (hi shl 8) or lo else (lo shl 8) or hi
                    val r = ((v shr 11) and 0x1F) * 255 / 31
                    val g = ((v shr 5) and 0x3F) * 255 / 63
                    val b = (v and 0x1F) * 255 / 31
                    buffer[o++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
            else -> throw IOException("Unsupported bits-per-pixel $bitsPerPixel")
        }

        listener.onFramebuffer(w, h, buffer)
    }

    /* ------------------------------------------------------------------ */
    /* Input (XTEST FakeInput)                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Moves the pointer and, when [pressed] is not null, also presses or
     * releases [button].
     */
    fun sendPointer(x: Int, y: Int, button: Int, pressed: Boolean?) {
        inject(EV_MOTION, 0, x, y)
        if (pressed != null) {
            inject(if (pressed) EV_BUTTON_PRESS else EV_BUTTON_RELEASE, button, x, y)
        }
    }

    fun sendKey(keycode: Int, pressed: Boolean) {
        inject(if (pressed) EV_KEY_PRESS else EV_KEY_RELEASE, keycode, 0, 0)
    }

    /**
     * XTEST `FakeInput`: 4 byte request header followed by a complete 32 byte
     * `xEvent`:
     *
     *   type(1) detail(1) sequence(2) time(4) root(4) event(4) child(4)
     *   rootX(2) rootY(2) eventX(2) eventY(2) state(2) sameScreen(1) pad(1)
     */
    private fun inject(eventType: Int, detail: Int, x: Int, y: Int) {
        if (!running.get()) return
        val out = output ?: return
        try {
            synchronized(writeLock) {
                out.writeByte(XTEST_OPCODE)
                out.writeByte(XTEST_FAKE_INPUT)
                out.writeShort(9)          // 4 header + 32 event bytes
                out.writeByte(eventType)
                out.writeByte(detail)
                out.writeShort(0)          // sequence
                out.writeInt(0)            // time = CurrentTime
                out.writeInt(rootWindow)   // root
                out.writeInt(0)            // event window
                out.writeInt(0)            // child window
                out.writeShort(x)          // root-x
                out.writeShort(y)          // root-y
                out.writeShort(x)          // event-x
                out.writeShort(y)          // event-y
                out.writeShort(0)          // state (no modifiers)
                out.writeByte(0)           // same-screen
                out.writeByte(0)           // pad
                out.flush()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to inject X input", t)
        }
    }

    /* ------------------------------------------------------------------ */

    /** Stops the loops and closes the socket. Safe to call more than once. */
    fun disconnect() {
        running.set(false)
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            socket?.close()
        } catch (_: Throwable) {
            // ignored
        }
        socket = null
        input = null
        output = null
    }

    private companion object {
        const val TAG = "X11Client"

        const val SUCCESS = 1
        const val FAILED = 0

        const val X_REPLY = 1
        const val X_ERROR = 0

        const val OP_GET_IMAGE = 73
        const val ZPIXMAP = 2

        const val XTEST_OPCODE = 132
        const val XTEST_FAKE_INPUT = 2

        const val EV_KEY_PRESS = 2
        const val EV_KEY_RELEASE = 3
        const val EV_BUTTON_PRESS = 4
        const val EV_BUTTON_RELEASE = 5
        const val EV_MOTION = 6

        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 15000

        /** ~15 fps. GetImage over loopback is cheap but not free. */
        const val FRAME_INTERVAL_MS = 66L

        fun pad4(n: Int): Int = (4 - (n and 3)) and 3

        fun hex(v: Int): String = "0x%x".format(v)
    }
}
