package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.SharedPreferences
import androidx.core.content.edit

object MasterSwitchStore {
    const val KEY_ENABLED = "master_enabled"

    fun load(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun save(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(KEY_ENABLED, enabled)
        }
    }
}
