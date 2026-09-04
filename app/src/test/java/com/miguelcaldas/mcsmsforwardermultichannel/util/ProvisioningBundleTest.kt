package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ProvisioningBundleTest {
    private val passphrase = "test-passphrase-1234".toCharArray()

    @Test
    fun decryptsBundleGeneratedByPowerShellHelper() {
        val bundle = requireNotNull(javaClass.getResource("/powershell-provisioning-v1.json")).readText()

        val result = ProvisioningBundle.decrypt(bundle, passphrase)

        assertEquals("fake-wa-token-for-tests", result.whatsApp?.accessToken)
        assertEquals("123456:fake-telegram-token", result.telegram?.botToken)
    }

    @Test
    fun decryptsPowerShellBundleWithUnicodePassphrase() {
        val bundle = requireNotNull(
            javaClass.getResource("/powershell-provisioning-unicode-v1.json"),
        ).readText()

        val result = ProvisioningBundle.decrypt(bundle, "válida-passphrase-🔐-123".toCharArray())

        assertEquals("fake-wa-token-for-tests", result.whatsApp?.accessToken)
        assertEquals("123456:fake-telegram-token", result.telegram?.botToken)
    }

    @Test
    fun decryptsExpandedPowerShellBundle() {
        val bundle = requireNotNull(
            javaClass.getResource("/powershell-provisioning-expanded-v1.json"),
        ).readText()

        val result = ProvisioningBundle.decrypt(bundle, "expanded-passphrase-123".toCharArray())

        assertEquals(true, result.masterEnabled)
        assertEquals("+351911111111", result.sms?.destination)
        assertEquals(listOf("BankAlerts", "+351922222222"), result.filters?.allowedSenders)
        assertEquals(listOf("""otp\s+\d+""", "^account alert"), result.filters?.regexes)
        assertEquals("[%t] %s: %m", result.filters?.forwardTemplate)
    }

    @Test
    fun additiveMergeIsIdempotentAndPreservesExistingEntries() {
        val existing = listOf("existing", "duplicate", "duplicate")
        val additions = listOf("duplicate", "new", "new")

        val first = mergeProvisioningEntries(existing, additions) { left, right -> left == right }
        val second = mergeProvisioningEntries(first.values, additions) { left, right -> left == right }

        assertEquals(listOf("existing", "duplicate", "duplicate", "new"), first.values)
        assertEquals(1, first.addedCount)
        assertEquals(first.values, second.values)
        assertEquals(0, second.addedCount)
    }

    @Test
    fun acceptsFiltersOnlyAndDeduplicatesBundleEntries() {
        val payload = JSONObject().put(
            "filters",
            JSONObject()
                .put("allowedSenders", listOf(" BankAlerts ", "bankalerts", "+351922222222"))
                .put("regexes", listOf("^alert", "^alert", "^ALERT"))
                .put("forwardTemplate", ""),
        )

        val result = ProvisioningBundle.decrypt(encrypt(payload), passphrase)

        assertEquals(listOf("BankAlerts", "+351922222222"), result.filters?.allowedSenders)
        assertEquals(listOf("^alert", "^ALERT"), result.filters?.regexes)
        assertEquals("", result.filters?.forwardTemplate)
        assertEquals(null, result.whatsApp)
        assertEquals(null, result.sms)
    }

    @Test
    fun decryptsBothChannelConfigurations() {
        val payload = JSONObject()
            .put(
                "whatsApp",
                JSONObject()
                    .put("enabled", true)
                    .put("phoneNumberId", "123456789")
                    .put("accessToken", "fake-wa-token")
                    .put("recipient", "351900000000"),
            )
            .put(
                "telegram",
                JSONObject()
                    .put("enabled", false)
                    .put("botToken", "123456:fake-telegram-token")
                    .put("chatId", "-1001234567890"),
            )

        val result = ProvisioningBundle.decrypt(encrypt(payload), passphrase)

        assertNotNull(result.whatsApp)
        assertEquals(true, result.whatsApp?.enabled)
        assertEquals("123456789", result.whatsApp?.phoneNumberId)
        assertEquals("fake-wa-token", result.whatsApp?.accessToken)
        assertEquals("351900000000", result.whatsApp?.recipient)
        assertNotNull(result.telegram)
        assertEquals(false, result.telegram?.enabled)
        assertEquals("123456:fake-telegram-token", result.telegram?.botToken)
        assertEquals("-1001234567890", result.telegram?.chatId)
    }

    @Test
    fun rejectsIncorrectPassphrase() {
        val payload = JSONObject().put(
            "telegram",
            JSONObject()
                .put("enabled", true)
                .put("botToken", "123456:fake-telegram-token")
                .put("chatId", "-1001234567890"),
        )

        val error = assertThrows(ProvisioningException::class.java) {
            ProvisioningBundle.decrypt(encrypt(payload), "incorrect-passphrase".toCharArray())
        }

        assertEquals(
            "Could not decrypt the configuration. The passphrase is incorrect or the file was changed.",
            error.message,
        )
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val payload = JSONObject().put(
            "whatsApp",
            JSONObject()
                .put("enabled", true)
                .put("phoneNumberId", "123456789")
                .put("accessToken", "fake-wa-token")
                .put("recipient", "351900000000"),
        )
        val envelope = JSONObject(encrypt(payload))
        val ciphertext = Base64.getDecoder().decode(envelope.getJSONObject("cipher").getString("ciphertext"))
        ciphertext[0] = (ciphertext[0].toInt() xor 1).toByte()
        envelope.getJSONObject("cipher").put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))

        assertThrows(ProvisioningException::class.java) {
            ProvisioningBundle.decrypt(envelope.toString(), passphrase)
        }
    }

    private fun encrypt(payload: JSONObject): String {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 16).toByte() }
        val specification = PBEKeySpec(passphrase, salt, 600_000, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).encoded
        } finally {
            specification.clearPassword()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD("mc-sms-forwarder-config:v1".toByteArray(Charsets.UTF_8))
        val encryptedWithTag = try {
            cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        } finally {
            key.fill(0)
        }
        val ciphertext = encryptedWithTag.copyOfRange(0, encryptedWithTag.size - 16)
        val tag = encryptedWithTag.copyOfRange(encryptedWithTag.size - 16, encryptedWithTag.size)

        return JSONObject()
            .put("format", "mc-sms-forwarder-config")
            .put("version", 1)
            .put(
                "kdf",
                JSONObject()
                    .put("algorithm", "PBKDF2-HMAC-SHA256")
                    .put("iterations", 600_000)
                    .put("salt", Base64.getEncoder().encodeToString(salt)),
            )
            .put(
                "cipher",
                JSONObject()
                    .put("algorithm", "AES-256-GCM")
                    .put("nonce", Base64.getEncoder().encodeToString(nonce))
                    .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
                    .put("tag", Base64.getEncoder().encodeToString(tag)),
            )
            .toString()
    }
}
