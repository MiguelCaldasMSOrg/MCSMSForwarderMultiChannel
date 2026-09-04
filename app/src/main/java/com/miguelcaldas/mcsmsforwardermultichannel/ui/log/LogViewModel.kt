package com.miguelcaldas.mcsmsforwardermultichannel.ui.log

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LogFilter { All, SendOk, SendFailed, FilterRejected, Boot }
enum class LogClearState { Idle, Clearing, Failed }

internal enum class LogEntryType { SendOk, SendFailed, FilterRejected, Boot, Other }

internal fun classifyLogEntry(entry: String): LogEntryType {
    val message = entry.substringAfter(" \u2192 ", entry)
    return when {
        message.startsWith("${LogUtils.FILTER_REJECTED_PREFIX} \u2192") -> LogEntryType.FilterRejected
        message.startsWith("SEND OK [") -> LogEntryType.SendOk
        message.startsWith("SEND FAILED [") -> LogEntryType.SendFailed
        message.startsWith("BOOT \u2192") || message.startsWith("TILE \u2192") -> LogEntryType.Boot
        else -> LogEntryType.Other
    }
}

internal fun matchesLogFilter(entry: String, filter: LogFilter): Boolean = when (filter) {
    LogFilter.All -> true
    LogFilter.SendOk -> classifyLogEntry(entry) == LogEntryType.SendOk
    LogFilter.SendFailed -> classifyLogEntry(entry) == LogEntryType.SendFailed
    LogFilter.FilterRejected -> classifyLogEntry(entry) == LogEntryType.FilterRejected
    LogFilter.Boot -> classifyLogEntry(entry) == LogEntryType.Boot
}

/**
 * Holds the activity-log screen state. Reads entries through [LogUtils] (backed by
 * SharedPreferences) and refreshes live while a new entry is written, so the list
 * survives configuration changes without re-reading on every recomposition.
 */
class LogViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _filter = MutableStateFlow(LogFilter.All)
    val filter: StateFlow<LogFilter> = _filter.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _clearState = MutableStateFlow(LogClearState.Idle)
    val clearState: StateFlow<LogClearState> = _clearState.asStateFlow()

    // Refresh the list live when a new entry is written elsewhere (SmsReceiver, channels, …).
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (_clearState.value != LogClearState.Clearing && (key == null || key == LOGS_KEY)) {
            refresh()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        refresh()
    }

    fun setFilter(filter: LogFilter) {
        _filter.value = filter
        refresh()
    }

    fun clear() {
        if (_clearState.value == LogClearState.Clearing) {
            return
        }
        _clearState.value = LogClearState.Clearing
        LogUtils.clearLogs(getApplication()) { cleared ->
            if (cleared) {
                _logs.value = emptyList()
            }
            _clearState.value = if (cleared) LogClearState.Idle else LogClearState.Failed
            refresh()
        }
    }

    fun clearFailureShown() {
        if (_clearState.value == LogClearState.Failed) {
            _clearState.value = LogClearState.Idle
        }
    }

    /** Current filtered entries, most recent first. Used by the share action. */
    fun visibleLogs(): List<String> = _logs.value

    private fun refresh() {
        viewModelScope.launch {
            _logs.value = LogUtils.getLogs(getApplication()).filter { matchesLogFilter(it, _filter.value) }
        }
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(changeListener)
    }

    private companion object {
        const val PREFS = "mc_sms_fwd_wa"
        const val LOGS_KEY = "logs_v2"
    }
}
