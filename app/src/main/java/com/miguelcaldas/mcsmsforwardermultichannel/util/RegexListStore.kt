package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.SharedPreferences
import androidx.core.content.edit

object RegexListStore {
    internal const val KEY = "messageFormat"

    // Newline is the only separator: commas, spaces, etc. can appear inside regexes.
    // Entries are not trimmed because leading/trailing whitespace can be a deliberate
    // part of a pattern.
    fun load(prefs: SharedPreferences): List<String> =
        prefs.getString(KEY, "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun save(prefs: SharedPreferences, patterns: List<String>) {
        val normalized = patterns.filter { it.isNotBlank() }
        prefs.edit {
            putString(KEY, normalized.joinToString("\n"))
        }
    }
}
