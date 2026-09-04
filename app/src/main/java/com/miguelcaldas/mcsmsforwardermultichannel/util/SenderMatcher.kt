package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

object SenderMatcher {

    // Single source of truth for sender allow-list matching. Used by SmsReceiver
    // (live pipeline) and FiltersViewModel.runTest (dry-run) so the two cannot drift.
    // Incoming alphanumeric content is normalized; rule text is deliberately not transformed.
    fun matches(allowedSenders: List<SenderRule>, sender: String, countryIso: String): Boolean {
        val normalizedSender = TextNormalizer.normalizeForMatching(sender)
        return allowedSenders.any { rule ->
            if (rule.isRegex) {
                matchesRegex(rule.value, normalizedSender)
            } else {
                rule.value == normalizedSender ||
                    PhoneNumberUtils.areSamePhoneNumber(rule.value, sender, countryIso)
            }
        }
    }

    fun isValidRegex(rule: SenderRule): Boolean =
        !rule.isRegex || runCatching { Regex(rule.value) }.isSuccess

    fun equivalent(left: SenderRule, right: SenderRule, countryIso: String): Boolean {
        if (left.isRegex != right.isRegex) {
            return false
        }
        if (left.value == right.value) {
            return true
        }
        return !left.isRegex && PhoneNumberUtils.areSamePhoneNumber(left.value, right.value, countryIso)
    }

    internal fun matchesRegex(pattern: String, normalizedSender: String): Boolean =
        runCatching { Regex(pattern) }.getOrNull()?.matches(normalizedSender) == true

    fun deviceCountryIso(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = tm?.networkCountryIso?.takeIf { it.isNotEmpty() } ?: tm?.simCountryIso?.takeIf { it.isNotEmpty() } ?: Locale.getDefault().country
        return iso.lowercase(Locale.ROOT)
    }
}
