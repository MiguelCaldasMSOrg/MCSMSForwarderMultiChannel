package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import java.util.Base64

data class RemoteSmsRulesConfig(val enabled: Boolean, val hmacKey: String) {
    val hasKey: Boolean
        get() = isValidRemoteSmsHmacKey(hmacKey)

    val isOperational: Boolean
        get() = enabled && hasKey

    companion object {
        const val KEY_ENABLED = "remoteSmsRulesEnabled"
        const val HMAC_KEY_BYTES = 32
        const val HMAC_KEY_BASE64URL_LENGTH = 43
        val HMAC_KEY_MASK = "\u2022".repeat(HMAC_KEY_BASE64URL_LENGTH)

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
    value.trim()

internal fun isValidRemoteSmsHmacKey(value: String): Boolean {
    val normalized = normalizeRemoteSmsHmacKey(value)
    return normalized.length == RemoteSmsRulesConfig.HMAC_KEY_BASE64URL_LENGTH &&
        decodeCanonicalUnpaddedBase64Url(normalized)?.size == RemoteSmsRulesConfig.HMAC_KEY_BYTES
}

internal fun encodeUnpaddedBase64Url(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

internal fun decodeCanonicalUnpaddedBase64Url(value: String): ByteArray? {
    if (value.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) {
        return null
    }
    val decoded = try {
        Base64.getUrlDecoder().decode(value)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return decoded.takeIf { encodeUnpaddedBase64Url(it) == value }
}
