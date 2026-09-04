package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.SharedPreferences
import androidx.core.content.edit

data class SenderRule(val value: String, val isRegex: Boolean = false)

object SenderListStore {
    internal const val KEY = "allowedSenders"
    internal const val KEY_REGEX_FLAGS = "allowedSenderRegexFlags"

    fun load(prefs: SharedPreferences): List<SenderRule> =
        decode(
            rawValues = prefs.getString(KEY, "").orEmpty(),
            rawRegexFlags = prefs.getString(KEY_REGEX_FLAGS, "").orEmpty(),
        )

    internal fun decode(rawValues: String, rawRegexFlags: String): List<SenderRule> {
        if (rawValues.isEmpty() || rawRegexFlags.isEmpty()) {
            return emptyList()
        }
        val values = rawValues.split('\n')
        val regexFlags = rawRegexFlags.split('\n')
        return values.zip(regexFlags).mapNotNull { (value, regexFlag) ->
            normalize(SenderRule(value = value, isRegex = regexFlag == "1"))
                .takeIf { it.value.isNotBlank() }
        }
    }

    fun save(prefs: SharedPreferences, senders: List<SenderRule>) {
        prefs.edit {
            write(this, senders)
        }
    }

    internal fun write(editor: SharedPreferences.Editor, senders: List<SenderRule>) {
        val normalized = senders.map(::normalize).filter { it.value.isNotBlank() }
        editor.putString(KEY, normalized.joinToString("\n") { it.value })
        editor.putString(KEY_REGEX_FLAGS, normalized.joinToString("\n") { if (it.isRegex) "1" else "0" })
    }

    private fun normalize(rule: SenderRule): SenderRule =
        if (rule.isRegex) rule else rule.copy(value = rule.value.trim())
}
