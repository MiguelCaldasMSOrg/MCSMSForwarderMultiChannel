package com.miguelcaldas.mcsmsforwardermultichannel.ui.status

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.PowerManager
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.MasterSwitchStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

/** What a [HealthItem] should do when the user taps its "fix" action. */
enum class HealthAction {
    GRANT_RECEIVE_SMS,
    GRANT_SEND_SMS,
    BATTERY_SETTINGS,
    OPEN_CHANNELS,
    OPEN_FILTERS,
}

/** A single readiness check surfaced on the Status screen. */
data class HealthItem(val label: String, val fixLabel: String, val action: HealthAction)

internal fun permissionHealthItems(
    receiveSmsGranted: Boolean,
    smsEnabled: Boolean,
    sendSmsGranted: Boolean,
): List<HealthItem> = buildList {
    if (!receiveSmsGranted) {
        add(HealthItem("Grant SMS receiving", "Grant", HealthAction.GRANT_RECEIVE_SMS))
    }
    if (smsEnabled && !sendSmsGranted) {
        add(HealthItem("Grant SMS sending", "Grant", HealthAction.GRANT_SEND_SMS))
    }
}

/** Forward counter snapshot, pre-formatted for display. */
data class StatsUi(val count: String, val first: String, val last: String)

class StatusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    private val _masterEnabled = MutableStateFlow(MasterSwitchStore.load(prefs))
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    private val _stats = MutableStateFlow(StatsUi("0", "—", "—"))
    val stats: StateFlow<StatsUi> = _stats.asStateFlow()

    // Only the blocking (not-yet-satisfied) checks. Empty means "all systems go".
    private val _blockers = MutableStateFlow<List<HealthItem>>(emptyList())
    val blockers: StateFlow<List<HealthItem>> = _blockers.asStateFlow()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in STAT_KEYS) {
            refreshStats()
        }
        if (key == MasterSwitchStore.KEY_ENABLED) {
            _masterEnabled.value = MasterSwitchStore.load(prefs)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun setMasterEnabled(enabled: Boolean) {
        MasterSwitchStore.save(prefs, enabled)
        _masterEnabled.value = enabled
    }

    /** Re-read the master switch, stats and readiness checks. Call when the screen resumes. */
    fun refresh() {
        _masterEnabled.value = MasterSwitchStore.load(prefs)
        refreshStats()
        refreshBlockers()
    }

    fun resetStats() {
        ForwardStatsStore.reset(getApplication())
        refreshStats()
    }

    private fun refreshStats() {
        val stats = ForwardStatsStore.load(getApplication())
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val now = System.currentTimeMillis()
        _stats.value = StatsUi(
            count = stats.count.toString(),
            first = if (stats.hasAny) formatStamp(fmt, stats.firstMillis, now) else "—",
            last = if (stats.hasAny) formatStamp(fmt, stats.lastMillis, now) else "—",
        )
    }

    private fun formatStamp(fmt: DateFormat, millis: Long, now: Long): String {
        val relative = DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        return "${fmt.format(Date(millis))}  ·  $relative"
    }

    private fun refreshBlockers() {
        val context = getApplication<Application>()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val waConfig = WhatsAppConfig.load(context)
        val tgConfig = TelegramConfig.load(context)
        val smsConfig = SmsConfig.load(prefs)
        val items = mutableListOf<HealthItem>()

        fun require(satisfied: Boolean, label: String, fixLabel: String, action: HealthAction) {
            if (!satisfied) {
                items.add(HealthItem(label, fixLabel, action))
            }
        }

        items.addAll(
            permissionHealthItems(
                receiveSmsGranted = hasPermission(Manifest.permission.RECEIVE_SMS),
                smsEnabled = smsConfig.enabled,
                sendSmsGranted = hasPermission(Manifest.permission.SEND_SMS),
            ),
        )
        require(powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true, "Exempt from battery optimization", "Settings", HealthAction.BATTERY_SETTINGS)
        require(waConfig.enabled || tgConfig.enabled || smsConfig.enabled, "Enable at least one channel", "Channels", HealthAction.OPEN_CHANNELS)

        if (waConfig.enabled) {
            require(waConfig.phoneNumberId.isNotEmpty(), "Add WhatsApp Phone Number ID", "Open", HealthAction.OPEN_CHANNELS)
            require(waConfig.accessToken.isNotEmpty(), "Add WhatsApp access token", "Open", HealthAction.OPEN_CHANNELS)
            require(waConfig.recipient.isNotEmpty(), "Add WhatsApp recipient", "Open", HealthAction.OPEN_CHANNELS)
        }
        if (tgConfig.enabled) {
            require(tgConfig.botToken.isNotEmpty(), "Add Telegram bot token", "Open", HealthAction.OPEN_CHANNELS)
            require(tgConfig.chatId.isNotEmpty(), "Add Telegram chat ID", "Open", HealthAction.OPEN_CHANNELS)
        }
        if (smsConfig.enabled) {
            require(smsConfig.destination.isNotEmpty(), "Add SMS destination", "Open", HealthAction.OPEN_CHANNELS)
        }
        require(
            SenderListStore.load(prefs).any(SenderMatcher::isValidRegex),
            "Add a valid sender rule",
            "Filters",
            HealthAction.OPEN_FILTERS,
        )
        require(
            RegexListStore.load(prefs).any { runCatching { Regex(it) }.isSuccess },
            "Add a valid message rule",
            "Filters",
            HealthAction.OPEN_FILTERS,
        )

        _blockers.value = items
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(getApplication(), permission) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    private companion object {
        val STAT_KEYS = setOf("fwd_count", "fwd_first_ts", "fwd_last_ts")
    }
}
