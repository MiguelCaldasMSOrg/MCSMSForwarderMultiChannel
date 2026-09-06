package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.annotation.SuppressLint
import android.content.SharedPreferences

internal class PreferenceSnapshot private constructor(
    private val values: Map<String, Any?>,
) {
    @SuppressLint("UseKtx")
    fun restore(prefs: SharedPreferences) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                else -> error("Unsupported preference value")
            }
        }
        check(editor.commit()) { "Could not restore preferences" }
    }

    companion object {
        fun capture(
            prefs: SharedPreferences,
            keys: Set<String>,
        ): PreferenceSnapshot {
            val storedValues = prefs.all
            return PreferenceSnapshot(
                keys.associateWith { key ->
                    if (prefs.contains(key)) storedValues[key] else null
                },
            )
        }
    }
}
