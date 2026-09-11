package com.linux_core.xlauncher

/**
 * Where to find the X server run by the guest.
 *
 * `nh desktop start` boots Xvfb on display `:0` with `-listen tcp`, which makes
 * it listen on TCP port 6000 (6000 + display number). The proot guest shares
 * the Android network namespace, so the host app reaches it on loopback and no
 * `adb reverse` is needed.
 */
data class ConnectionConfig(
    val host: String,
    val port: Int
) {
    companion object {
        val DEFAULT = ConnectionConfig("127.0.0.1", 6000)

        /** Display number used by `nh desktop start`; TCP port = 6000 + this. */
        const val DEFAULT_DISPLAY = 0
    }
}
