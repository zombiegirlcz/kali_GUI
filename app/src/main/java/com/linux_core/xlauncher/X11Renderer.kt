package com.linux_core.xlauncher

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hardware accelerated renderer that draws the remote X11 framebuffer on a
 * full-screen quad.
 *
 * The framebuffer arrives as ARGB ints (see [X11Client]). On a little-endian
 * device those ints are laid out in memory as B,G,R,A which is exactly what
 * `GL_RGBA` / `GL_UNSIGNED_BYTE` expects, so the upload is a plain memcpy.
 *
 * The image keeps its aspect ratio: [onSurfaceChanged] letterboxes the
 * viewport instead of stretching the desktop.
 */
class X11Renderer(private val glView: GLSurfaceView) : GLSurfaceView.Renderer {

    /** Framebuffer handed over by the X11 client. Written from another thread. */
    @Volatile
    private var pendingPixels: IntArray? = null

    @Volatile
    private var pendingWidth = 0

    @Volatile
    private var pendingHeight = 0

    private var textureId = 0
    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var samplerHandle = 0

    private var vertexBuffer: FloatBuffer? = null

    /** Reused direct buffer; allocating 3.7 MB per frame would thrash the GC. */
    private var uploadBuffer: java.nio.IntBuffer? = null

    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /**
     * Pointer overlay. The cursor is NOT part of the X11 framebuffer (GetImage
     * never returns it), so we draw our own arrow sprite at the position the
     * input layer reports.
     */
    private var cursorTexture = 0
    private var cursorBuffer: FloatBuffer? = null

    @Volatile
    private var pendingPointerX = 0

    @Volatile
    private var pendingPointerY = 0

    @Volatile
    private var pointerVisible = false

    /** Size of the remote framebuffer, used for touch coordinate mapping. */
    @Volatile
    var framebufferWidth = 0
        private set

    @Volatile
    var framebufferHeight = 0
        private set

    /** Viewport actually used (letterboxed); touch input maps through this. */
    @Volatile
    var viewportX = 0
        private set

    @Volatile
    var viewportY = 0
        private set

    @Volatile
    var viewportWidth = 0
        private set

    @Volatile
    var viewportHeight = 0
        private set

    /**
     * Region patches waiting to be uploaded. Drained on the GL thread in
     * [onDrawFrame]. The client allocates a fresh array per patch (see
     * [X11Client.publishRegion]), so a patch can never be overwritten while it
     * is still queued.
     *
     * A queue rather than a single slot: several `DamageNotify` regions can
     * arrive between two GL frames, and dropping all but the last would leave
     * stale pixels on screen.
     */
    private val pendingPatches = java.util.ArrayDeque<RegionPatch>()

    private class RegionPatch(val x: Int, val y: Int, val w: Int, val h: Int, val pixels: IntArray)

    /**
     * The dimensions the texture currently holds. Only meaningful once a full
     * frame has been uploaded; a patch may not change them (glTexSubImage2D
     * writes into the existing texture).
     */
    private var textureWidth = 0
    private var textureHeight = 0

    /** Called from the network thread whenever a new full frame arrived. */
    fun updateFramebuffer(width: Int, height: Int, pixels: IntArray) {
        pendingWidth = width
        pendingHeight = height
        pendingPixels = pixels
        framebufferWidth = width
        framebufferHeight = height
        // A full frame replaces the whole texture, so anything queued for the
        // previous one is obsolete and must not be applied on top.
        synchronized(pendingPatches) { pendingPatches.clear() }
        glView.requestRender()
    }

    /**
     * Called from the network thread for a partial update — only the rectangle
     * [x]..[x]+[w], [y]..[y]+[h] changed. Ignored until a full frame has
     * established the texture (there is nothing to patch into yet).
     */
    fun updateFramebufferRegion(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return
        synchronized(pendingPatches) {
            // Bounded: if the GL thread is far behind (surface paused, device
            // busy) the whole screen is more than likely damaged anyway, so
            // waiting for one full frame beats an unbounded queue.
            if (pendingPatches.size >= MAX_PENDING_PATCHES) return
            pendingPatches.add(RegionPatch(x, y, w, h, pixels))
        }
        glView.requestRender()
    }

    /** Called whenever the pointer moved (or just became visible). */
    fun updatePointer(x: Int, y: Int) {
        pendingPointerX = x
        pendingPointerY = y
        pointerVisible = true
        glView.requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Pointer arrow texture, generated once from a small ARGB bitmap.
        GLES20.glGenTextures(1, ids, 0)
        cursorTexture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        uploadArrowTexture()

        // Full-screen triangle strip: x, y, u, v. The V coordinate is inverted
        // because X11 images start at the top row while GL textures start at
        // the bottom row.
        val quad = floatArrayOf(
                -1f, -1f, 0f, 1f,
                 1f, -1f, 1f, 1f,
                -1f,  1f, 0f, 0f,
                 1f,  1f, 1f, 0f,
        )
        vertexBuffer = ByteBuffer.allocateDirect(quad.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(quad); position(0) }

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        Log.i(TAG, "GL surface created (program=$program texture=$textureId)")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        applyViewport()
    }

    /**
     * Letterboxes the remote screen inside the surface, preserving its aspect
     * ratio. Recomputed on every frame because the first framebuffer only
     * arrives after [onSurfaceChanged] has already run once.
     */
    private fun applyViewport() {
        val surfaceW = surfaceWidth
        val surfaceH = surfaceHeight
        val fbW = framebufferWidth
        val fbH = framebufferHeight
        if (surfaceW <= 0 || surfaceH <= 0) return

        if (fbW <= 0 || fbH <= 0) {
            viewportX = 0
            viewportY = 0
            viewportWidth = surfaceW
            viewportHeight = surfaceH
        } else {
            val scale = minOf(surfaceW.toFloat() / fbW, surfaceH.toFloat() / fbH)
            val drawW = (fbW * scale).toInt()
            val drawH = (fbH * scale).toInt()
            viewportX = (surfaceW - drawW) / 2
            viewportY = (surfaceH - drawH) / 2
            viewportWidth = drawW
            viewportHeight = drawH
        }
        GLES20.glViewport(viewportX, viewportY, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        applyViewport()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val pixels = pendingPixels
        if (pixels != null && pendingWidth > 0 && pendingHeight > 0) {
            uploadTexture(pendingWidth, pendingHeight, pixels)
            // Consume it. Without this a later patch-triggered render would
            // re-upload the whole texture from a stale frame, which is exactly
            // the 8 MB-per-frame cost the DAMAGE path exists to avoid.
            pendingPixels = null
            drawQuad()
        }
        applyPendingPatches()
        // A patch updated the existing texture in place, so it still has to be
        // drawn — but only if there is a texture at all.
        if (textureWidth > 0 && textureHeight > 0) drawQuad()
        // Cursor is an overlay: GetImage never returns it, so we draw it here.
        drawCursor()
    }

    /**
     * Uploads every queued region patch into the existing texture with
     * `glTexSubImage2D`, which touches only the damaged pixels instead of
     * re-sending the whole 8 MB framebuffer to the GPU.
     */
    private fun applyPendingPatches() {
        if (textureWidth <= 0 || textureHeight <= 0) {
            synchronized(pendingPatches) { pendingPatches.clear() }
            return
        }
        while (true) {
            val patch = synchronized(pendingPatches) { pendingPatches.poll() } ?: return
            // Clamp to the texture: a stale patch from a since-resized screen
            // must not make glTexSubImage2D read out of bounds.
            val x = patch.x.coerceIn(0, textureWidth - 1)
            val y = patch.y.coerceIn(0, textureHeight - 1)
            val w = patch.w.coerceAtMost(textureWidth - x)
            val h = patch.h.coerceAtMost(textureHeight - y)
            if (w <= 0 || h <= 0) continue

            val buffer = ByteBuffer.allocateDirect(w * h * 4)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer()
            buffer.put(patch.pixels, 0, w * h)
            buffer.position(0)

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D, 0,
                    x, y, w, h,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        }
    }

    private fun uploadTexture(width: Int, height: Int, pixels: IntArray) {
        var buffer = uploadBuffer
        if (buffer == null || buffer.capacity() < pixels.size) {
            buffer = ByteBuffer.allocateDirect(pixels.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asIntBuffer()
            uploadBuffer = buffer
        }
        buffer.clear()
        buffer.put(pixels)
        buffer.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        textureWidth = width
        textureHeight = height
    }

    private fun drawQuad() {
        val vb = vertexBuffer ?: return
        GLES20.glUseProgram(program)

        vb.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vb)

        vb.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vb)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(samplerHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /** Uploads the built-in arrow sprite (ARROW is ARGB, premultiplied). */
    private fun uploadArrowTexture() {
        val buf = ByteBuffer.allocateDirect(ARROW.size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer()
        buf.put(ARROW)
        buf.position(0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTexture)
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                ARROW_W, ARROW_H, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
    }

    /**
     * Draws the pointer arrow at the last reported position. Without this the
     * pointer is invisible: X11 GetImage on the root window never includes the
     * cursor (it is a hardware/compositing overlay).
     */
    private fun drawCursor() {
        if (!pointerVisible || cursorTexture == 0) return

        val fbW = framebufferWidth
        val fbH = framebufferHeight
        if (fbW <= 0 || fbH <= 0) return

        // Arrow hot spot is its top-left corner (0,0) in ARROW.
        val left = pendingPointerX.toFloat()
        val top = pendingPointerY.toFloat()

        val x0 = 2f * left / fbW - 1f
        val x1 = 2f * (left + ARROW_W) / fbW - 1f
        val y0 = 1f - 2f * top / fbH          // top edge -> larger NDC y
        val y1 = 1f - 2f * (top + ARROW_H) / fbH

        val quad = floatArrayOf(
                x0, y1, 0f, 1f,
                x1, y1, 1f, 1f,
                x0, y0, 0f, 0f,
                x1, y0, 1f, 0f,
        )
        var buf = cursorBuffer
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(quad.size * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer()
            cursorBuffer = buf
        }
        buf.clear()
        buf.put(quad)
        buf.position(0)

        // Premultiplied ARGB sprite -> standard premultiplied blending.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUseProgram(program)
        buf.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTexture)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /* ------------------------------------------------------------------ */

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vs)
        GLES20.glAttachShader(id, fs)
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            throw RuntimeException("Failed to link GL program: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return id
    }

    private fun compileShader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            throw RuntimeException("Failed to compile shader ($type): $log")
        }
        return id
    }

    private companion object {
        const val TAG = "X11Renderer"

        const val ARROW_W = 16
        const val ARROW_H = 16

        /** Above this the queued patches are stale, not worth uploading. */
        const val MAX_PENDING_PATCHES = 64

        /**
         * Classic white arrow with a black outline and a soft drop shadow,
         * premultiplied ARGB. Generated at build time by hand so the viewer
         * does not depend on the server for a cursor image.
         */
        val ARROW = intArrayOf(
            0xF0000000.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0xF0000000.toInt(), 0xF0000000.toInt(), 0xF0000000.toInt(), 0xF0000000.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0xF0000000.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xF0000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
            0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0xF0000000.toInt(), 0xF0000000.toInt(), 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000, 0x00000000,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
