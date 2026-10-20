package com.linux_core.xlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers the `.rdp` files the app was opened with, so they can be reopened
 * from the launcher later. Only the parsed target is stored, not the file
 * itself: a content:// grant from a file manager is not kept across launches.
 */
class SavedSessions(context: Context) {

    data class Entry(
        /** Source URI as given by the intent; the identity used for de-duplication. */
        val uri: String,
        val name: String,
        val host: String,
        val port: Int,
        val addedAt: Long,
    ) {
        fun config(): ConnectionConfig = ConnectionConfig(host, port)
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Newest first. Corrupt stored data yields an empty list instead of a crash. */
    fun all(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    uri = o.getString("uri"),
                    name = o.getString("name"),
                    host = o.getString("host"),
                    port = o.getInt("port"),
                    addedAt = o.getLong("addedAt"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Stores [entry] at the top, replacing any earlier entry with the same URI. */
    fun add(entry: Entry) {
        val rest = all().filter { it.uri != entry.uri }
        write(listOf(entry) + rest)
    }

    fun remove(uri: String) {
        write(all().filter { it.uri != uri })
    }

    private fun write(entries: List<Entry>) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("uri", e.uri)
                put("name", e.name)
                put("host", e.host)
                put("port", e.port)
                put("addedAt", e.addedAt)
            })
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "xlauncher_sessions"
        private const val KEY_ENTRIES = "entries"
    }
}
