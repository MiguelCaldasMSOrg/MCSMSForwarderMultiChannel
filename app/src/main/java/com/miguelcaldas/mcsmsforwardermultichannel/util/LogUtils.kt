package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

internal data class StoredLogEntry(val timestamp: Long, val message: String) {
    val byteSize: Int = timestamp.toString().length + 2 + message.toByteArray(Charsets.UTF_8).size
}

internal fun retainLogEntries(entries: List<StoredLogEntry>, nowMillis: Long, maximumBytes: Int = 4 * 1024 * 1024): List<StoredLogEntry> {
    val retained = ArrayList<StoredLogEntry>()
    var bytes = 0
    for (entry in entries.sortedBy { it.timestamp }.asReversed()) {
        if (entry.timestamp < nowMillis - TimeUnit.DAYS.toMillis(35)) {
            continue
        }
        if (retained.size == 2000 || entry.byteSize > maximumBytes - bytes) {
            break
        }
        retained.add(entry)
        bytes += entry.byteSize
    }
    return retained.asReversed()
}

object LogUtils {
    const val FILTER_REJECTED_PREFIX = "FILTER REJECTED"

    internal const val PREFS = "mc_sms_fwd_log"
    internal const val LOGS_KEY = "logs_v2"
    private const val LEGACY_PREFS = "mc_sms_fwd_wa"
    private const val MIGRATED = "legacy_migrated"
    private const val FIELD_SEP = "\u001F"
    private const val LINE_SEP = "\n"
    private const val MAX_PENDING_BYTES = 1024 * 1024
    private const val MAX_ENTRY_BYTES = 512 * 1024
    private val writeExecutor = boundedDaemonExecutor("mc-log-writer", 1, 1)
    private val storeLock = Any()
    private val pending = ArrayDeque<LogWork>()
    private var pendingBytes = 0
    private var draining = false
    private var omitted = 0L

    private sealed interface LogWork {
        data class Append(val entry: StoredLogEntry): LogWork
        data class Clear(val callback: (Boolean) -> Unit): LogWork
    }

    // The on-disk format is line-oriented (`timestamp\x1Fmessage`, entries joined by
    // '\n'), so a message that itself contains a newline or the field separator would
    // break parsing and silently truncate the entry. Forwarded and filter-rejected SMS bodies
    // routinely contain line breaks, so collapse any CR/LF/0x1F run to a single space before
    // storing — the log viewer renders one line per entry.
    private val CONTROL_RUN = Regex("[\\r\\n\\u001F]+")

    fun addToLog(context: Context, logEntry: String) {
        val sanitized = CONTROL_RUN.replace(logEntry, " ")
        val message = if (sanitized.toByteArray(Charsets.UTF_8).size <= MAX_ENTRY_BYTES) sanitized else "LOG ENTRY OMITTED -> entry exceeded 512 KiB"
        enqueue(context.applicationContext, LogWork.Append(StoredLogEntry(System.currentTimeMillis(), message)))
    }

    private fun enqueue(context: Context, work: LogWork) {
        synchronized(pending) {
            val bytes = if (work is LogWork.Append) work.entry.byteSize else 0
            while (pending.size >= 128 || pendingBytes + bytes > MAX_PENDING_BYTES) {
                val oldest = pending.firstOrNull { it is LogWork.Append } as? LogWork.Append
                if (oldest == null) {
                    if (work is LogWork.Clear) {
                        work.callback(false)
                    } else {
                        omitted++
                    }
                    return
                }
                pending.remove(oldest)
                pendingBytes -= oldest.entry.byteSize
                omitted++
            }
            pending.addLast(work)
            pendingBytes += bytes
            if (!draining) {
                draining = true
                scheduleDrain(context)
            }
        }
    }

    private fun scheduleDrain(context: Context) {
        try {
            writeExecutor.execute { drain(context) }
        } catch (_: RejectedExecutionException) {
            draining = false
            omitted += pending.count { it is LogWork.Append }
            val callbacks = pending.filterIsInstance<LogWork.Clear>()
            pending.clear()
            pendingBytes = 0
            callbacks.forEach { it.callback(false) }
        }
    }

    @SuppressLint("UseKtx")
    private fun drain(context: Context) {
        val batch = synchronized(pending) {
            val work = pending.toList()
            pending.clear()
            pendingBytes = 0
            val dropped = omitted
            omitted = 0
            work to dropped
        }
        val saved = runCatching {
            synchronized(storeLock) {
                val prefs = store(context)
                val entries = loadEntries(prefs).toMutableList()
                if (batch.second > 0) {
                    entries.add(StoredLogEntry(System.currentTimeMillis(), "LOG BACKPRESSURE -> ${batch.second} pending entries omitted"))
                }
                for (work in batch.first) {
                    when (work) {
                        is LogWork.Append -> entries.add(work.entry)
                        is LogWork.Clear -> entries.clear()
                    }
                }
                prefs.edit().putString(LOGS_KEY, serialize(retainLogEntries(entries, System.currentTimeMillis()))).commit()
            }
        }.getOrDefault(false)
        batch.first.filterIsInstance<LogWork.Clear>().forEach {
            runCatching { it.callback(saved) }
        }
        synchronized(pending) {
            if (!saved) {
                omitted += batch.first.count { it is LogWork.Append }
            }
            if (pending.isEmpty()) {
                draining = false
            } else {
                scheduleDrain(context)
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun store(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MIGRATED, false)) {
            val before = PreferenceSnapshot.capture(prefs, setOf(LOGS_KEY, MIGRATED))
            val entries = parse(legacy.getString(LOGS_KEY, "").orEmpty()) + loadEntries(prefs)
            if (!prefs.edit().putString(LOGS_KEY, serialize(retainLogEntries(entries, System.currentTimeMillis()))).putBoolean(MIGRATED, true).commit()) {
                runCatching { before.restore(prefs) }
                error("Could not migrate logs; legacy copy retained")
            }
        }
        if (legacy.contains(LOGS_KEY)) {
            legacy.edit().remove(LOGS_KEY).commit()
        }
        return prefs
    }

    /** Most recent first; callers perform migration, pruning and formatting off the UI thread. */
    @SuppressLint("UseKtx")
    fun getLogs(context: Context): List<String> = synchronized(storeLock) {
        val prefs = store(context)
        val entries = retainLogEntries(loadEntries(prefs), System.currentTimeMillis())
        val serialized = serialize(entries)
        if (serialized != prefs.getString(LOGS_KEY, "")) {
            prefs.edit().putString(LOGS_KEY, serialized).commit()
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        entries.asReversed().map { "${fmt.format(Date(it.timestamp))} → ${it.message}" }
    }

    fun clearLogs(context: Context, onCleared: (Boolean) -> Unit = {}) {
        enqueue(context.applicationContext, LogWork.Clear(onCleared))
    }

    private fun loadEntries(prefs: SharedPreferences): List<StoredLogEntry> = parse(prefs.getString(LOGS_KEY, "").orEmpty())

    private fun serialize(entries: List<StoredLogEntry>): String =
        entries.joinToString(LINE_SEP) { "${it.timestamp}$FIELD_SEP${it.message}" }

    private fun parse(raw: String): List<StoredLogEntry> {
        if (raw.isEmpty()) {
            return emptyList()
        }
        val bounded = if (raw.length > 4 * 1024 * 1024) raw.takeLast(4 * 1024 * 1024).substringAfter(LINE_SEP, "") else raw
        return bounded.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(FIELD_SEP)
            if (idx <= 0) {
                return@mapNotNull null
            }
            val ts = line.substring(0, idx).toLongOrNull() ?: return@mapNotNull null
            StoredLogEntry(ts, line.substring(idx + 1))
        }.toList()
    }
}