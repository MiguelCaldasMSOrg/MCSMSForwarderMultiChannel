package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.os.Build
import android.telephony.PhoneNumberUtils
import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

internal object PhoneNumberCompat {
    fun areSame(first: String, second: String, countryIso: String): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            PhoneNumberUtils.areSamePhoneNumber(first, second, countryIso)
        } else {
            areSameOnAndroid11(first, second, countryIso)
        }
    }

    internal fun areSameOnAndroid11(first: String, second: String, countryIso: String): Boolean {
        val parser = PhoneNumberUtil.getInstance()
        val region = countryIso.uppercase(Locale.ROOT)
        return try {
            val firstNumber = parser.parseAndKeepRawInput(first, region)
            val secondNumber = parser.parseAndKeepRawInput(second, region)
            when (parser.isNumberMatch(firstNumber, secondNumber)) {
                PhoneNumberUtil.MatchType.EXACT_MATCH, PhoneNumberUtil.MatchType.NSN_MATCH -> true
                PhoneNumberUtil.MatchType.SHORT_NSN_MATCH -> firstNumber.countryCode == secondNumber.countryCode && firstNumber.nationalNumber == secondNumber.nationalNumber
                else -> false
            }
        } catch (_: NumberParseException) {
            false
        }
    }
}