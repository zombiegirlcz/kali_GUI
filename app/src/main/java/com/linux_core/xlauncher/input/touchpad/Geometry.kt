package com.linux_core.xlauncher.input.touchpad

import com.linux_core.xlauncher.input.Viewport

/**
 * Maps a view coordinate to remote framebuffer pixels, or null when the
 * viewport is not laid out yet or the point falls outside the rendered image.
 * Moved verbatim from `LauncherActivity.toFramebuffer`.
 */
fun Viewport.toFramebuffer(viewX: Float, viewY: Float): Pair<Int, Int>? {
    if (viewportWidth <= 0 || viewportHeight <= 0) return null
    val fx = (viewX - viewportX) / viewportWidth.toFloat()
    val fy = (viewY - viewportY) / viewportHeight.toFloat()
    if (fx < 0f || fx > 1f || fy < 0f || fy > 1f) return null
    val x = (fx * framebufferWidth).toInt().coerceIn(0, framebufferWidth - 1)
    val y = (fy * framebufferHeight).toInt().coerceIn(0, framebufferHeight - 1)
    return x to y
}
