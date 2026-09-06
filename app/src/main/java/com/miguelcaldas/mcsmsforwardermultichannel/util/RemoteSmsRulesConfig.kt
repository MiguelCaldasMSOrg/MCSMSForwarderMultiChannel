package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import java.util.Locale

data class RemoteSmsRulesConfig(val enabled: Boolean, val hmacKey: String) {
    val hasKey: Boolean
        get() = isValidRemoteSmsHmacKey(hmacKey)

    val isOperational: Boolean
        get() = enabled && hasKey

    companion object {
        const val KEY_ENABLED = "remoteSmsRulesEnabled"
        const val HMAC_KEY_HEX_LENGTH = 64
        const val HMAC_KEY_MASK = "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022" +
            "\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022"

        fun load(context: Context): RemoteSmsRulesConfig {
            val prefs = context.getSharedPreferences(WhatsAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            return RemoteSmsRulesConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                hmacKey = SecureStore.read(context, SecureStore.KEY_REMOTE_SMS_HMAC),
            )
        }
    }
}

internal fun normalizeRemoteSmsHmacKey(value: String): String =
    value.trim().lowercase(Locale.ROOT)

internal fun isValidRemoteSmsHmacKey(value: String): Boolean {
    val normalized = normalizeRemoteSmsHmacKey(value)
    return normalized.length == RemoteSmsRulesConfig.HMAC_KEY_HEX_LENGTH &&
        normalized.all { it in '0'..'9' || it in 'a'..'f' }
}
