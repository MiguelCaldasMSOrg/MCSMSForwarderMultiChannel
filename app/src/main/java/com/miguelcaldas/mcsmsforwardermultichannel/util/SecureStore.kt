package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted-at-rest storage for channel and remote-control secrets. Values are encrypted with an
 * AES key held by Android Keystore before being written to a private SharedPreferences file.
 */
object SecureStore {
    private const val FILE = "mc_sms_fwd_secure"
    private const val KEY_ALIAS = "mc_sms_fwd_secure_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    const val KEY_WA_ACCESS_TOKEN = "waAccessToken"
    const val KEY_TG_BOT_TOKEN = "tgBotToken"
    const val KEY_REMOTE_SMS_HMAC = "remoteSmsHmacKey"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val app = context.applicationContext
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).also {
                    cached = it
                }
            }
        }
    }

    /** True when a non-empty secret is stored under [key]. */
    fun has(context: Context, key: String): Boolean = read(context, key).isNotEmpty()

    /** Returns the stored secret, or an empty string when unset. */
    fun read(context: Context, key: String): String =
        prefs(context).getString(key, null)?.let { decrypt(it) }.orEmpty()

    /** Stores [value] (trimmed), or removes the secret entirely when [value] is blank. */
    fun write(context: Context, key: String, value: String) {
        prefs(context).edit {
            if (value.isBlank()) {
                remove(key)
            } else {
                putString(key, encrypt(value.trim()))
            }
        }
    }

    /**
     * Encrypts every value before committing any of them, then persists the complete update
     * synchronously so provisioning can report storage failures.
     */
    @SuppressLint("UseKtx")
    fun writeAll(context: Context, values: Map<String, String>) {
        val encrypted = values.mapValues { (_, value) ->
            value.trim().takeIf { it.isNotEmpty() }?.let(::encrypt)
        }
        val editor = prefs(context).edit()
        encrypted.forEach { (key, value) ->
            if (value == null) {
                editor.remove(key)
            } else {
                editor.putString(key, value)
            }
        }
        check(editor.commit()) { "Could not persist encrypted secrets" }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
