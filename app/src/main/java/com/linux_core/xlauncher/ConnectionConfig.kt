package com.linux_core.xlauncher

/**
 * Connection target for the Linux-X11 X server started by the host app
 * (`nh desktop start` → linux-x11 :1).
 *
 * The launcher connects to the X11 port (6000) where the X server listens.
 * MIT-SHM extension is used for efficient framebuffer sharing when available.
 */
data class ConnectionConfig(
    val host: String,
    val port: Int
) {
    companion object {
        /** Defaults match `nh desktop start` (linux-x11 :1, display 6000). */
        val DEFAULT = ConnectionConfig("127.0.0.1", 6000)

        // Default display number for X server
        const val DEFAULT_DISPLAY = 1
    }
}
