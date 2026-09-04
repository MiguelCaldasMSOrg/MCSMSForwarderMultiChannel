package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.SharedPreferences
import androidx.core.content.edit

object SenderListStore {
    internal const val KEY = "allowedSenders"

    fun load(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY, "")?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun save(prefs: SharedPreferences, senders: List<String>) {
        val normalized = senders.map { it.trim() }.filter { it.isNotEmpty() }
        prefs.edit {
            putString(KEY, normalized.joinToString("\n"))
        }
    }
}
