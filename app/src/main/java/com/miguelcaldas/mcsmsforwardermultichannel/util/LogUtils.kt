package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object LogUtils {
    const val FILTER_REJECTED_PREFIX = "FILTER REJECTED"

    private const val PREFS = "mc_sms_fwd_wa"
    private const val LOGS_KEY = "logs_v2"
    private const val FIELD_SEP = "\u001F"
    private const val LINE_SEP = "\n"
    private val RETENTION_MS = TimeUnit.DAYS.toMillis(35)
    private const val MAX_ENTRIES = 2000

    // Single-thread executor: addToLog reads + serializes the whole log file, so
    // concurrent writers from SmsReceiver, the channels, and BootReceiver would
    // race. Serializing them off the main thread keeps broadcast onReceive callbacks
    // snappy and avoids interleaved prune/write.
    private val writeExecutor = singleThreadDaemonExecutor("mc-log-writer")

    private data class Entry(val timestamp: Long, val message: String)

    // The on-disk format is line-oriented (`timestamp\x1Fmessage`, entries joined by
    // '\n'), so a message that itself contains a newline or the field separator would
    // break parsing and silently truncate the entry. Forwarded and filter-rejected SMS bodies
    // routinely contain line breaks, so collapse any CR/LF/0x1F run to a single space before
    // storing — the log viewer renders one line per entry.
    private val CONTROL_RUN = Regex("[\\r\\n\\u001F]+")

    fun addToLog(context: Context, logEntry: String) {
        val appContext = context.applicationContext
        val timestamp = System.currentTimeMillis()
        val sanitized = CONTROL_RUN.replace(logEntry, " ")
        writeExecutor.execute {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val entries = loadEntries(prefs).toMutableList()
            entries.add(Entry(timestamp, sanitized))
            val pruned = prune(entries)
            prefs.edit {
                putString(LOGS_KEY, serialize(pruned))
            }
        }
    }

    /** Most recent first, formatted for display. */
    fun getLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val entries = loadEntries(prefs)
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return entries.sortedByDescending { it.timestamp }.map { "${fmt.format(Date(it.timestamp))} → ${it.message}" }
    }

    @SuppressLint("UseKtx") // KTX edit(commit = true) discards the commit result needed by the UI.
    fun clearLogs(context: Context, onCleared: (Boolean) -> Unit = {}) {
        val appContext = context.applicationContext
        writeExecutor.execute {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var cleared = false
            try {
                cleared = prefs.edit().remove(LOGS_KEY).commit()
            } finally {
                onCleared(cleared)
            }
        }
    }

    private fun loadEntries(prefs: SharedPreferences): List<Entry> =
        parse(prefs.getString(LOGS_KEY, null) ?: "")

    private fun prune(entries: MutableList<Entry>): List<Entry> {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        entries.removeAll { it.timestamp < cutoff }
        if (entries.size > MAX_ENTRIES) {
            entries.sortBy { it.timestamp }
            repeat(entries.size - MAX_ENTRIES) { entries.removeAt(0) }
        }
        return entries
    }

    private fun serialize(entries: List<Entry>): String =
        entries.joinToString(LINE_SEP) { "${it.timestamp}$FIELD_SEP${it.message}" }

    private fun parse(raw: String): List<Entry> {
        if (raw.isEmpty()) {
            return emptyList()
        }
        return raw.split(LINE_SEP).mapNotNull { line ->
            val idx = line.indexOf(FIELD_SEP)
            if (idx <= 0) {
                return@mapNotNull null
            }
            val ts = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
            Entry(ts, line.substring(idx + 1))
        }
    }
}