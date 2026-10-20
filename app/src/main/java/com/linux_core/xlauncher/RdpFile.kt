package com.linux_core.xlauncher

/**
 * Minimal reader for Microsoft `.rdp` connection files.
 *
 * The format is one `name:type:value` property per line (e.g.
 * `full address:s:192.168.1.50:6000`). Only the X server address is used
 * here: `full address` gives the host and, optionally, the TCP port. Every
 * other property is ignored, so unknown or RDP-only keys never break a load.
 */
object RdpFile {

    /** Parses [text] and returns the X server target, or null if it has no usable `full address`. */
    fun connectionConfig(text: String): ConnectionConfig? {
        val address = properties(text)["full address"]?.trim().orEmpty()
        if (address.isEmpty()) return null

        val (host, port) = splitAddress(address)
        if (host.isEmpty()) return null
        return ConnectionConfig(host, port ?: ConnectionConfig.DEFAULT.port)
    }

    /** Lowercase-free map of `name -> value`; the value keeps any colons it contains. */
    internal fun properties(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val first = line.indexOf(':')
            if (first <= 0) continue
            val second = line.indexOf(':', first + 1)
            if (second < 0) continue
            val name = line.substring(0, first).trim()
            out[name] = line.substring(second + 1)
        }
        return out
    }

    /** `host`, `host:port`, `[v6]` or `[v6]:port` → host and optional port. */
    internal fun splitAddress(address: String): Pair<String, Int?> {
        if (address.startsWith("[")) {
            val close = address.indexOf(']')
            if (close < 0) return address to null
            val host = address.substring(1, close)
            val rest = address.substring(close + 1)
            val port = rest.removePrefix(":").toIntOrNull()
            return host to port
        }
        // A single colon separates the port; more colons means a bare IPv6 address.
        if (address.count { it == ':' } == 1) {
            val idx = address.indexOf(':')
            return address.substring(0, idx) to address.substring(idx + 1).toIntOrNull()
        }
        return address to null
    }
}
