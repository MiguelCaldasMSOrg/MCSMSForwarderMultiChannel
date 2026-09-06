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
        prefs.edit {
            write(this, patterns)
        }
    }

    internal fun write(editor: SharedPreferences.Editor, patterns: List<String>) {
        editor.putString(KEY, patterns.filter { it.isNotBlank() }.joinToString("\n"))
    }
}
