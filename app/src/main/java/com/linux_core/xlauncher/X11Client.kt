package com.linux_core.xlauncher

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small but *real* X11 protocol client.
 *
 * It implements just enough of the core protocol, the XTEST extension and the
 * DAMAGE extension to drive the remote desktop that `nh desktop start` runs
 * inside the proot guest (Xvfb on display :0, TCP 127.0.0.1:6000):
 *
 *   - connection setup (handshake) + setup-reply parsing
 *   - `GetImage` (ZPixmap) on the root window -> framebuffer polling
 *   - XTEST `FakeInput` -> pointer / button / key injection
 *   - DAMAGE -> partial `GetImage` of just the regions that changed, so an
 *     idle desktop costs almost no bandwidth (see [damageFrameLoop])
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
 *
 *  * Every X11 packet on the wire is 32-byte aligned. Replies and errors carry
 *    a fixed 32-byte header; events are exactly 32 bytes. That is what makes
 *    the single-reader dispatcher in [readPacket] possible without a
 *    per-request reply queue.
 */
class X11Client {

    interface Listener {
        fun onConnected(width: Int, height: Int)

        /**
         * A complete new framebuffer. Sent as the seed frame right after
         * connecting and whenever the damaged area is large enough that one
         * full grab is cheaper than a partial one.
         */
        fun onFramebuffer(width: Int, height: Int, pixels: IntArray)

        /**
         * Only the rectangle [x]..[x]+[w], [y]..[y]+[h] changed. [pixels] is
         * `w * h` ARGB ints, in the same layout [onFramebuffer] uses. Only
         * ever delivered after a full frame has initialised the texture, and
         * only when the server supports DAMAGE.
         */
        fun onFramebufferRegion(x: Int, y: Int, w: Int, h: Int, pixels: IntArray)

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

    /**
     * Where this client may allocate its own XIDs. Without it no extension
     * resource can be created — DAMAGE needs one for its `Damage` object.
     */
    private var resourceIdBase = 0

    /** DAMAGE request major opcode, or 0 when the extension is unavailable. */
    private var damageOpcode = 0

    /** First event code owned by DAMAGE; `DamageNotify` is `base + 0`. */
    private var damageEventBase = 0

    /** XID of our `Damage` object. */
    private var damageXid = 0

    /** True once DamageCreate succeeded — the partial-image path is live. */
    @Volatile
    var damageActive = false
        private set

    /**
     * Diagnostics. If the screen never updates while [damageActive] is true,
     * these two numbers tell the whole story: [damageEventCount] staying at 0
     * means the server accepted DamageCreate but never reports anything (the
     * client is blind and, by design, silent), while a growing count with no
     * fetches would point at the accumulate/box logic instead.
     */
    private var damageEventCount = 0L
    private var regionFetchCount = 0L
    private var fullFetchCount = 0L

    /**
     * Accumulated damage, as a bounding box grown over every `DamageNotify`
     * seen since the last fetch. A union is deliberate: one `GetImage` for the
     * bounding box is fewer round trips than one per rectangle, and X11
     * rectangles for a repaint usually cluster into a few windows anyway.
     * Only ever touched from the client thread, so no lock is needed.
     */
    private var dmgMinX = Int.MAX_VALUE
    private var dmgMinY = Int.MAX_VALUE
    private var dmgMaxX = -1
    private var dmgMaxY = -1

    /**
     * How long to wait between `GetImage` requests, and how long a single read
     * may block. Both are decided in [connect] from whether the target is
     * loopback or across a network: a full-screen `GetImage` is several MB, so
     * loopback pacing on a Wi-Fi link saturates the socket, reads stall past
     * the timeout and the session dies mid-reply.
     */
    private var frameIntervalMs = LOCAL_FRAME_INTERVAL_MS
    private var readTimeoutMs = LOCAL_READ_TIMEOUT_MS

    /**
     * Pacing for partial frames. A damaged region is typically a few KB to a
     * few hundred KB, so it can be fetched far more often than a full screen
     * without saturating the link — this is what makes a remote desktop feel
     * responsive instead of 3 fps.
     */
    private var damageFrameIntervalMs = LOCAL_DAMAGE_FRAME_INTERVAL_MS

    /** Double buffered framebuffers, swapped on every published full frame. */
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

            // Resolved address, not the typed host: "localhost", an explicit
            // 127.0.0.1 and a LAN IP must be told apart by what we actually
            // reached, not by how it was spelled.
            val loopback = sock.inetAddress?.isLoopbackAddress ?: true
            frameIntervalMs = if (loopback) LOCAL_FRAME_INTERVAL_MS else REMOTE_FRAME_INTERVAL_MS
            readTimeoutMs = if (loopback) LOCAL_READ_TIMEOUT_MS else REMOTE_READ_TIMEOUT_MS
            damageFrameIntervalMs =
                    if (loopback) LOCAL_DAMAGE_FRAME_INTERVAL_MS else REMOTE_DAMAGE_FRAME_INTERVAL_MS
            sock.soTimeout = readTimeoutMs
            Log.i(TAG, "connected to ${config.host}:${config.port} " +
                    "(loopback=$loopback, interval=${frameIntervalMs}ms, " +
                    "damageInterval=${damageFrameIntervalMs}ms, readTimeout=${readTimeoutMs}ms)")
            socket = sock
            input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            output = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
            running.set(true)

            handshake(listener)

            // Best effort: a server without DAMAGE just keeps the old
            // full-frame polling, so nothing here may be fatal on its own.
            setupDamage()

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
            throw IOException("X server reported an empty screen (${screenWidth}x${screenHeight})")
        }

        // NOTE: the cursor is NOT part of GetImage output (it is a hardware /
        // compositing overlay), so the viewer draws its own pointer sprite.
        // XFixesGetCursorImage would give the real sprite, but Xvfb answers it
        // with an error, so we do not rely on it.
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
        resourceIdBase = u32()      // our XID range; needed by DAMAGE
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
    /* Packet I/O                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * One complete 32-byte X11 packet: reply header, error header or event.
     * [data] always has 32 entries and `data[0]` is the type byte.
     */
    private class XPacket(val type: Int, val data: ByteArray)

    /**
     * Reads one packet if the server sends one within [timeoutMs], or returns
     * null when nothing arrived.
     *
     * The timeout only guards the *first* byte. Once a packet has started
     * arriving, the remainder is read with the socket's normal (generous)
     * timeout: a partially consumed event would silently desynchronise the
     * stream, and every later packet would be misread. Waiting for the first
     * byte is safe, because nothing has been consumed when it times out.
     */
    private fun readPacket(timeoutMs: Int): XPacket? {
        val inp = input ?: return null
        val sock = socket ?: return null
        val data = ByteArray(32)
        val previous = sock.soTimeout
        try {
            sock.soTimeout = timeoutMs
            data[0] = inp.readByte()
        } catch (e: SocketTimeoutException) {
            return null
        } finally {
            sock.soTimeout = previous
        }
        inp.readFully(data, 1, 31)
        return XPacket(data[0].toInt() and 0xFF, data)
    }

    /**
     * Reads packets until a reply (1) or error (0) header arrives, feeding any
     * interleaved events to [dispatchEvent].
     *
     * This replaces the old "skip 31 bytes" drain. Events are not noise here:
     * `DamageNotify` arrives asynchronously and carries the only information
     * about what actually changed on screen, so it must be parsed rather than
     * discarded.
     */
    private fun awaitReply(): XPacket {
        while (true) {
            val pkt = readPacket(readTimeoutMs)
                    ?: throw SocketTimeoutException("X server stopped replying")
            if (pkt.type == X_REPLY || pkt.type == X_ERROR) return pkt
            dispatchEvent(pkt)
        }
    }

    /**
     * Handles one asynchronous event. Returns true when it was a
     * `DamageNotify` that contributed to the dirty region.
     */
    private fun dispatchEvent(pkt: XPacket): Boolean {
        if (!damageActive || pkt.type != damageEventBase) {
            // Not every stray packet is noise worth ignoring silently: an
            // event with a *different* type while DAMAGE is on is a real
            // signal (wrong event base, or another extension's event).
            if (pkt.type != X_REPLY && pkt.type != X_ERROR) {
                Log.i(TAG, "non-damage event type=${pkt.type} (expected $damageEventBase)")
            }
            return false
        }
        // DamageNotify: type(1) level(1) sequence(2) drawable(4) damage(4)
        // timestamp(4) area.x(2) area.y(2) area.w(2) area.h(2) geometry...
        val ax = be16(pkt.data, 16)
        val ay = be16(pkt.data, 18)
        val aw = be16(pkt.data, 20)
        val ah = be16(pkt.data, 22)
        damageEventCount++
        if (damageEventCount <= 5 || damageEventCount % 200 == 0L) {
            Log.i(TAG, "DamageNotify #$damageEventCount area=($ax,$ay,$aw,$ah)")
        }
        accumulate(ax, ay, aw, ah)
        return true
    }

    /* ------------------------------------------------------------------ */
    /* DAMAGE extension                                                   */
    /* ------------------------------------------------------------------ */

    private class ExtensionInfo(val present: Boolean, val majorOpcode: Int, val firstEvent: Int)

    /**
     * `QueryExtension`: does the server know [name], and if so which opcodes
     * and event codes did it reserve for it?
     */
    private fun queryExtension(name: String): ExtensionInfo? {
        val out = output ?: return null
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val padded = nameBytes.size + pad4(nameBytes.size)
        val length = (8 + padded) / 4

        synchronized(writeLock) {
            out.writeByte(OP_QUERY_EXTENSION)
            out.writeByte(0)
            out.writeShort(length)
            out.writeShort(nameBytes.size)
            out.writeShort(0)
            out.write(nameBytes)
            repeat(pad4(nameBytes.size)) { out.writeByte(0) }
            out.flush()
        }

        val reply = awaitReply()
        if (reply.type != X_REPLY) return null
        return ExtensionInfo(
                present = (reply.data[8].toInt() and 0xFF) != 0,
                majorOpcode = reply.data[9].toInt() and 0xFF,
                firstEvent = reply.data[10].toInt() and 0xFF)
    }

    /**
     * `DAMAGE QueryVersion`. Returns false when the extension refuses.
     *
     * Body: 1 byte major opcode, 1 byte minor (0), 2 bytes length (3),
     * 4 bytes client-major, 4 bytes client-minor = 12 bytes.
     */
    private fun damageQueryVersion(opcode: Int): Boolean {
        val out = output ?: return false
        synchronized(writeLock) {
            out.writeByte(opcode)
            out.writeByte(0)          // minor: QueryVersion
            out.writeShort(3)         // 4 header + 2 + 2 words
            out.writeInt(1)           // client major version
            out.writeInt(1)           // client minor version
            out.flush()
        }
        return awaitReply().type == X_REPLY
    }

    /**
     * `DAMAGE Create` at the RawRectangles report level (0): each DamageNotify
     * carries the changed rectangle itself, so no `DamageSubtract` or XFixes
     * region is needed. The higher levels trade fewer events for a round trip
     * through XFixes on every fetch, which is not worth a second extension.
     */
    private fun damageCreate(opcode: Int, damageId: Int, drawable: Int, level: Int) {
        val out = output ?: return
        synchronized(writeLock) {
            out.writeByte(opcode)
            out.writeByte(1)          // minor: Create
            out.writeShort(4)         // 4 header + 4 + 4 + 1 + 3 pad
            out.writeInt(damageId)
            out.writeInt(drawable)
            out.writeByte(level)
            out.writeByte(0)          // pad
            out.writeByte(0)
            out.writeByte(0)
            out.flush()
        }
    }

    /**
     * Turns on partial-frame updates when the server supports DAMAGE.
     *
     * Absence is not an error — a server without the extension (or with it
     * disabled) simply keeps the full-frame polling in [fullFrameLoop], which
     * is exactly the old behaviour. Only a genuinely broken stream throws, and
     * that should end the session so the activity's auto-reconnect can retry.
     */
    private fun setupDamage() {
        val ext = queryExtension("DAMAGE")
        if (ext == null || !ext.present || ext.majorOpcode == 0) {
            Log.i(TAG, "DAMAGE extension unavailable; using full-frame polling")
            return
        }
        if (!damageQueryVersion(ext.majorOpcode)) {
            Log.w(TAG, "DamageQueryVersion refused; using full-frame polling")
            return
        }
        damageOpcode = ext.majorOpcode
        damageEventBase = ext.firstEvent
        damageXid = resourceIdBase or DAMAGE_XID_OFFSET

        // dispatchEvent() ignores DamageNotify unless damageActive is set, and
        // the confirmation round trip below has to see them — so flip the flag
        // first and undo it if the subscription turns out not to exist.
        damageActive = true
        damageCreate(damageOpcode, damageXid, rootWindow, DAMAGE_RAW_RECTANGLES)

        // DamageCreate has no reply of its own, and that is the trap: a
        // rejection (BadIdChoice, BadValue, BadAlloc) would sit unread in the
        // stream while the loop parks forever on a subscription that does not
        // exist. The result is a frozen picture with *zero* traffic — exactly
        // what a silently unsupported DAMAGE looks like, and impossible to
        // tell from an idle desktop once the loop is running.
        //
        // GetInputFocus does have a reply, so a single round trip flushes any
        // pending error. And DamageCreate is required to emit one seed
        // DamageNotify covering the whole drawable, so seeing that event is
        // positive proof the subscription is live — no guessing, no canary
        // draw, and an idle desktop still costs nothing.
        val sawError = syncForErrors()
        if (sawError || damageEventCount == 0L) {
            Log.w(TAG, "DAMAGE not confirmed (error=$sawError, seedEvents=$damageEventCount); " +
                    "falling back to full-frame polling")
            damageActive = false
            return
        }
        Log.i(TAG, "DAMAGE active (opcode=$damageOpcode eventBase=$damageEventBase " +
                "damageId=${hex(damageXid)}) — partial GetImage enabled, " +
                "seed damage ($dmgMinX,$dmgMinY)-($dmgMaxX,$dmgMaxY)")
    }

    /**
     * Sends `GetInputFocus` (a request with a guaranteed reply) and reads until
     * that reply arrives. Returns true when an X error was seen on the way —
     * i.e. some earlier request without a reply of its own was rejected.
     */
    private fun syncForErrors(): Boolean {
        val out = output ?: return true
        synchronized(writeLock) {
            out.writeByte(OP_GET_INPUT_FOCUS)
            out.writeByte(0)
            out.writeShort(1)
            out.flush()
        }
        var sawError = false
        while (true) {
            val pkt = readPacket(readTimeoutMs) ?: return sawError
            when (pkt.type) {
                X_ERROR -> sawError = true
                X_REPLY -> return sawError
                else -> dispatchEvent(pkt)
            }
        }
    }

    /** Grows the dirty bounding box by one rectangle, clamped to the screen. */
    private fun accumulate(x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val x1 = (x + w - 1).coerceAtMost(screenWidth - 1)
        val y1 = (y + h - 1).coerceAtMost(screenHeight - 1)
        if (x1 < x0 || y1 < y0) return
        if (x0 < dmgMinX) dmgMinX = x0
        if (y0 < dmgMinY) dmgMinY = y0
        if (x1 > dmgMaxX) dmgMaxX = x1
        if (y1 > dmgMaxY) dmgMaxY = y1
    }

    private fun clearDamage() {
        dmgMinX = Int.MAX_VALUE
        dmgMinY = Int.MAX_VALUE
        dmgMaxX = -1
        dmgMaxY = -1
    }

    /** The dirty bounding box as `[x, y, w, h]`, or null when nothing changed. */
    private fun damageBox(): IntArray? {
        if (dmgMaxX < dmgMinX || dmgMaxY < dmgMinY) return null
        val x0 = dmgMinX.coerceIn(0, screenWidth - 1)
        val y0 = dmgMinY.coerceIn(0, screenHeight - 1)
        val x1 = dmgMaxX.coerceIn(x0, screenWidth - 1)
        val y1 = dmgMaxY.coerceIn(y0, screenHeight - 1)
        return intArrayOf(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    /* ------------------------------------------------------------------ */
    /* Framebuffer polling                                                */
    /* ------------------------------------------------------------------ */

    private fun frameLoop(listener: Listener) {
        // Both loops publish full frames through [publishFrame], which reads
        // frameA/frameB with `!!`. Allocate them here, once, for both paths —
        // the DAMAGE loop's seed frame would otherwise dereference null and
        // kill the session on its very first frame (which the activity then
        // retries forever, since onConnected resets the attempt counter).
        frameA = IntArray(screenWidth * screenHeight)
        frameB = IntArray(screenWidth * screenHeight)
        if (damageActive) {
            damageFrameLoop(listener)
        } else {
            fullFrameLoop(listener)
        }
    }

    /**
     * Damage-driven loop: fetch only what changed.
     *
     * The desktop is idle most of the time, and an idle desktop sends no
     * `DamageNotify` at all, so this parks in a 100 ms poll and transmits
     * nothing. When something does change, the bounding box of all damage seen
     * so far is fetched with a single partial `GetImage` — a few KB instead of
     * the ~8 MB a full frame costs.
     */
    private fun damageFrameLoop(listener: Listener) {
        val w = screenWidth
        val h = screenHeight
        val bytesPerPixel = bitsPerPixel / 8
        val screenArea = w.toLong() * h

        val full = ByteArray(w * h * bytesPerPixel)
        var seeded = false

        while (running.get()) {
            val started = System.currentTimeMillis()

            // Drain what the server has already sent. The first read blocks up
            // to IDLE_POLL_MS so an idle desktop parks here instead of
            // spinning; a pending DamageNotify returns immediately. The budget
            // keeps a continuously repainting server from starving the fetch
            // below — anything left over is dispatched from awaitReply() while
            // we wait for the image reply anyway.
            val drainUntil = started + DRAIN_BUDGET_MS
            while (running.get() && System.currentTimeMillis() < drainUntil) {
                val pkt = readPacket(IDLE_POLL_MS) ?: break
                dispatchEvent(pkt)
            }

            val box = damageBox()
            // The seed frame is mandatory: until one full image has been
            // uploaded there is no texture to patch into. After that, a
            // bounding box covering most of the screen is cheaper (and much
            // simpler) as one full frame than as a huge partial one.
            val big = box != null &&
                    box[2].toLong() * box[3] >= (screenArea * FULL_FRAME_RATIO).toLong()

            var publishedFull = false
            if (!seeded || big) {
                val bytes = getImage(0, 0, w, h, full)
                if (bytes > 0) {
                    publishFrame(full, w, h, listener)
                    seeded = true
                    publishedFull = true
                    fullFetchCount++
                    if (fullFetchCount <= 3) {
                        Log.i(TAG, "full frame #$fullFetchCount ($bytes B, seed=${!seeded})")
                    }
                }
                clearDamage()
            } else if (box != null) {
                val x = box[0]
                val y = box[1]
                val bw = box[2]
                val bh = box[3]
                val patch = ByteArray(bw * bh * bytesPerPixel)
                val bytes = getImage(x, y, bw, bh, patch)
                if (bytes > 0) {
                    publishRegion(x, y, bw, bh, patch, listener)
                    regionFetchCount++
                    if (regionFetchCount <= 5 || regionFetchCount % 200 == 0L) {
                        Log.i(TAG, "region #$regionFetchCount ($x,$y,$bw,$bh) $bytes B")
                    }
                }
                clearDamage()
            }

            // Full frames are expensive, regions are cheap — pace accordingly.
            val interval = if (publishedFull) frameIntervalMs else damageFrameIntervalMs
            val sleep = interval - (System.currentTimeMillis() - started)
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

    /** The original whole-screen polling loop, used when DAMAGE is absent. */
    private fun fullFrameLoop(listener: Listener) {
        val w = screenWidth
        val h = screenHeight
        val bytesPerPixel = bitsPerPixel / 8
        val rowBytes = w * bytesPerPixel

        val raw = ByteArray(rowBytes * h)
        val previous = ByteArray(raw.size)
        var havePrevious = false

        while (running.get()) {
            val started = System.currentTimeMillis()

            val bytes = getImage(0, 0, w, h, raw)
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
            val sleep = frameIntervalMs - elapsed
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
     * Sends a `GetImage` request for the rectangle ([x], [y], [w], [h]) and
     * reads the reply into [raw]. Returns the number of image bytes, or 0 when
     * the server answered with an error.
     */
    private fun getImage(x: Int, y: Int, w: Int, h: Int, raw: ByteArray): Int {
        val out = output!!
        val inp = input!!

        synchronized(writeLock) {
            out.writeByte(OP_GET_IMAGE)
            out.writeByte(ZPIXMAP)
            out.writeShort(5)              // 4 + 4 + 2*4 + 4 = 20 bytes
            out.writeInt(rootWindow)
            out.writeShort(x)
            out.writeShort(y)
            out.writeShort(w)
            out.writeShort(h)
            out.writeInt(0x00FFFFFF)       // plane mask
            out.flush()
        }

        // Events are interleaved with replies on the same stream, and with
        // DAMAGE active they carry the damage we have not seen yet — so
        // awaitReply() dispatches them instead of skipping 31 bytes. (This was
        // the original "unexpected reply type 34" failure: type 34 is
        // MappingNotify, not a malformed reply.)
        val reply = awaitReply()

        // Reply header (32 bytes) - a failed request also produces 32 bytes,
        // so the stream stays aligned either way.
        val length = be32(reply.data, 4)
        if (reply.type == X_ERROR) {
            Log.w(TAG, "GetImage($x,$y,$w,$h) rejected by the server (depth/pixmap mismatch?)")
            return 0
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
        convertToArgb(raw, buffer, w * h)
        listener.onFramebuffer(w, h, buffer)
    }

    /**
     * Same conversion for a partial update. A fresh array is allocated per
     * patch on purpose: the renderer drains patches from a queue, so reusing a
     * buffer would let the client overwrite one the GL thread has not uploaded
     * yet. Patches are small, so the allocation is cheap.
     */
    private fun publishRegion(x: Int, y: Int, w: Int, h: Int, raw: ByteArray, listener: Listener) {
        val buffer = IntArray(w * h)
        convertToArgb(raw, buffer, w * h)
        listener.onFramebufferRegion(x, y, w, h, buffer)
    }

    private fun convertToArgb(raw: ByteArray, out: IntArray, count: Int) {
        when (bitsPerPixel) {
            32 -> {
                var i = 0
                var o = 0
                while (o < count) {
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
                    out[o++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
            16 -> {
                // RGB565 -> ARGB8888
                var i = 0
                var o = 0
                while (o < count) {
                    val lo = raw[i].toInt() and 0xFF
                    val hi = raw[i + 1].toInt() and 0xFF
                    i += 2
                    val v = if (imageLsbFirst) (hi shl 8) or lo else (lo shl 8) or hi
                    val r = ((v shr 11) and 0x1F) * 255 / 31
                    val g = ((v shr 5) and 0x3F) * 255 / 63
                    val b = (v and 0x1F) * 255 / 31
                    out[o++] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
            else -> throw IOException("Unsupported bits-per-pixel $bitsPerPixel")
        }
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

    /**
     * Presses/releases a button without moving the pointer. X uses the current
     * pointer position for button events, so the wheel can be scrolled without
     * jumping the cursor to a stale location.
     */
    fun sendButton(button: Int, pressed: Boolean) {
        inject(if (pressed) EV_BUTTON_PRESS else EV_BUTTON_RELEASE, button, 0, 0)
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
        const val OP_QUERY_EXTENSION = 98

        /** Has a reply, so it can be used to flush and detect pending errors. */
        const val OP_GET_INPUT_FOCUS = 43
        const val ZPIXMAP = 2

        const val XTEST_OPCODE = 132
        const val XTEST_FAKE_INPUT = 2

        const val EV_KEY_PRESS = 2
        const val EV_KEY_RELEASE = 3
        const val EV_BUTTON_PRESS = 4
        const val EV_BUTTON_RELEASE = 5
        const val EV_MOTION = 6

        const val CONNECT_TIMEOUT_MS = 5000

        const val LOCAL_READ_TIMEOUT_MS = 15000

        /**
         * Generous on purpose. A remote full-screen `GetImage` is several MB
         * and a slow link can legitimately take tens of seconds; a read that
         * times out cannot be retried, because it leaves the reply stream
         * desynchronised and forces a whole reconnect.
         */
        const val REMOTE_READ_TIMEOUT_MS = 45000

        /** ~15 fps. GetImage over loopback is cheap but not free. */
        const val LOCAL_FRAME_INTERVAL_MS = 66L

        /**
         * ~3 fps. Still far more than a network link can carry at full frame
         * size, but it stops requests from piling up faster than replies drain.
         */
        const val REMOTE_FRAME_INTERVAL_MS = 300L

        /** ~30 fps for small patches over loopback. */
        const val LOCAL_DAMAGE_FRAME_INTERVAL_MS = 33L

        /** ~10 fps for small patches across a network. */
        const val REMOTE_DAMAGE_FRAME_INTERVAL_MS = 100L

        /** How long one drain pass may spend reading pending events. */
        const val DRAIN_BUDGET_MS = 200L

        /**
         * Idle poll interval. This is the worst-case latency between a repaint
         * on the server and the fetch that picks it up, and also the idle
         * wake-up rate — 10 Hz is a reasonable middle ground.
         */
        const val IDLE_POLL_MS = 100

        /** DAMAGE report level: every event carries the raw rectangle. */
        const val DAMAGE_RAW_RECTANGLES = 0

        /** First XID we may allocate ourselves, inside [resourceIdBase]. */
        const val DAMAGE_XID_OFFSET = 1

        /**
         * Above this share of the screen a single full frame beats a partial
         * one: the bounding box would cover nearly everything anyway, and one
         * request is cheaper than the bookkeeping for many small ones.
         */
        const val FULL_FRAME_RATIO = 0.6

        fun pad4(n: Int): Int = (4 - (n and 3)) and 3

        fun hex(v: Int): String = "0x%x".format(v)

        fun be16(b: ByteArray, o: Int): Int =
                ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        fun be32(b: ByteArray, o: Int): Int =
                ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
                        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
    }
}