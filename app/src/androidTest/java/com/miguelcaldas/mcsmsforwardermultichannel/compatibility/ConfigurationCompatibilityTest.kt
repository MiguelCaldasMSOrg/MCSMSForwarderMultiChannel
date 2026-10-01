package com.miguelcaldas.mcsmsforwardermultichannel.compatibility

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.miguelcaldas.mcsmsforwardermultichannel.ui.filters.FiltersViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisioningBundle
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisioningException
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisionedConfiguration
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisionedTelegram
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRulesConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SecureStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderRule
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigurationCompatibilityTest: DeviceTestBase() {
    @Test
    @SuppressLint("UseKtx")
    fun legacyLogMigrationAndClearKeepTheExistingContents() {
        assertTrue(prefs.edit().putString("logs_v2", "${System.currentTimeMillis()}\u001Fsynthetic legacy message").commit())
        assertTrue(LogUtils.getLogs(context).single().contains("synthetic legacy message"))
        assertFalse(prefs.contains("logs_v2"))
        val cleared = CountDownLatch(1)
        LogUtils.clearLogs(context) { success ->
            if (success) {
                cleared.countDown()
            }
        }
        assertTrue(cleared.await(5, TimeUnit.SECONDS))
        assertTrue(LogUtils.getLogs(context).isEmpty())
    }

    @Test
    fun failedFinalCommitRestoresPublicStateAndExactCiphertext() {
        val initial = ProvisionedConfiguration(false, null, ProvisionedTelegram(false, "synthetic-original-token", "original-chat"), null, null, null)
        ProvisioningBundle.save(context, initial)
        val before = prefs.all
        val secure = context.getSharedPreferences("mc_sms_fwd_secure", Context.MODE_PRIVATE)
        val ciphertext = secure.getString(SecureStore.KEY_TG_BOT_TOKEN, null)
        val commits = AtomicInteger()
        val failingPreferences = object: SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, arguments ->
                    val result = method.invoke(editor, *(arguments ?: emptyArray()))
                    when {
                        method.name == "commit" && commits.incrementAndGet() == 2 -> false
                        result is SharedPreferences.Editor -> proxy
                        else -> result
                    }
                } as SharedPreferences.Editor
            }
        }
        val failingContext = object: ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = if (name == "mc_sms_fwd_wa") failingPreferences else super.getSharedPreferences(name, mode)
        }
        assertThrows(ProvisioningException::class.java) {
            ProvisioningBundle.save(failingContext, initial.copy(telegram = ProvisionedTelegram(true, "synthetic-replacement-token", "replacement-chat")))
        }
        assertEquals(before, prefs.all)
        assertEquals(ciphertext, secure.getString(SecureStore.KEY_TG_BOT_TOKEN, null))
        assertEquals("synthetic-original-token", TelegramConfig.load(context).botToken)
    }

    @Test
    fun publicChannelEditPreservesUntouchedEncryptedToken() {
        SecureStore.writeAll(context, mapOf(SecureStore.KEY_TG_BOT_TOKEN to "synthetic-existing-token"))
        val secure = context.getSharedPreferences("mc_sms_fwd_secure", Context.MODE_PRIVATE)
        val ciphertext = secure.getString(SecureStore.KEY_TG_BOT_TOKEN, null)
        ProvisioningBundle.save(context, ProvisionedConfiguration(null, null, ProvisionedTelegram(false, null, "synthetic-chat"), null, null, null))
        assertEquals(ciphertext, secure.getString(SecureStore.KEY_TG_BOT_TOKEN, null))
        assertEquals("synthetic-existing-token", TelegramConfig.load(context).botToken)
        assertEquals("synthetic-chat", TelegramConfig.load(context).chatId)
    }

    @Test
    fun provisioningPersistsEncryptedSecretsAndIsIdempotent() {
        val parsed = ProvisioningBundle.decrypt(fixture("powershell-provisioning-v1.json"), "test-passphrase-1234".toCharArray())
        val configuration = parsed.copy(masterEnabled = false, whatsApp = parsed.whatsApp?.copy(enabled = false), telegram = parsed.telegram?.copy(enabled = false), sms = parsed.sms?.copy(enabled = false), remoteSmsRules = parsed.remoteSmsRules?.copy(enabled = false))
        ProvisioningBundle.save(context, configuration)
        val firstPreferences = prefs.all
        assertEquals("fake-wa-token-for-tests", WhatsAppConfig.load(context).accessToken)
        assertEquals("123456:fake-telegram-token", TelegramConfig.load(context).botToken)
        assertFalse(prefs.contains(SecureStore.KEY_WA_ACCESS_TOKEN))
        assertFalse(prefs.contains(SecureStore.KEY_TG_BOT_TOKEN))
        val securePrefs = context.getSharedPreferences("mc_sms_fwd_secure", Context.MODE_PRIVATE)
        assertFalse(securePrefs.all.values.contains("fake-wa-token-for-tests"))
        assertFalse(securePrefs.all.values.contains("123456:fake-telegram-token"))
        ProvisioningBundle.save(context, configuration)
        assertEquals(firstPreferences, prefs.all)
        assertEquals("fake-wa-token-for-tests", SecureStore.read(context, SecureStore.KEY_WA_ACCESS_TOKEN))
    }

    @Test
    fun authenticationFailuresDoNotChangeSavedSettings() {
        val bundle = fixture("powershell-provisioning-v1.json")
        val before = prefs.all
        assertThrows(ProvisioningException::class.java) {
            ProvisioningBundle.decrypt(bundle, "incorrect-passphrase-1234".toCharArray())
        }
        val tampered = JSONObject(bundle)
        tampered.getJSONObject("cipher").put("tag", "AAAAAAAAAAAAAAAAAAAAAA==")
        assertThrows(ProvisioningException::class.java) {
            ProvisioningBundle.decrypt(tampered.toString(), "test-passphrase-1234".toCharArray())
        }
        assertEquals(before, prefs.all)
        assertFalse(SecureStore.has(context, SecureStore.KEY_WA_ACCESS_TOKEN))
    }

    @Test
    @SuppressLint("UseKtx")
    fun provisioningMergesRulesAndKeepsRemoteKeyEncrypted() {
        val editor = prefs.edit()
        SenderListStore.write(editor, listOf(SenderRule("existing sender")))
        assertTrue(editor.commit())
        val parsed = ProvisioningBundle.decrypt(fixture("powershell-provisioning-expanded-v1.json"), "sender-rules-passphrase-123".toCharArray())
        val configuration = parsed.copy(masterEnabled = false, whatsApp = parsed.whatsApp?.copy(enabled = false), telegram = parsed.telegram?.copy(enabled = false), sms = parsed.sms?.copy(enabled = false))
        ProvisioningBundle.save(context, configuration)
        val firstSenders = SenderListStore.load(prefs)
        val firstRules = RegexListStore.load(prefs)
        assertEquals(SenderRule("existing sender"), firstSenders.first())
        assertTrue(firstSenders.containsAll(requireNotNull(configuration.filters?.allowedSenders)))
        ProvisioningBundle.save(context, configuration)
        assertEquals(firstSenders, SenderListStore.load(prefs))
        assertEquals(firstRules, RegexListStore.load(prefs))
        val remote = ProvisioningBundle.decrypt(fixture("powershell-provisioning-remote-sms-v1.json"), "remote-sms-passphrase-123".toCharArray())
        ProvisioningBundle.save(context, remote.copy(masterEnabled = false, remoteSmsRules = remote.remoteSmsRules?.copy(enabled = false)))
        assertTrue(RemoteSmsRulesConfig.load(context).hasKey)
        assertFalse(prefs.contains(SecureStore.KEY_REMOTE_SMS_HMAC))
    }

    @Test
    @SuppressLint("UseKtx")
    fun dryRunPreservesTheSmsLoopGuardWithoutRecordingAForward() {
        val editor = prefs.edit().putBoolean(SmsConfig.KEY_ENABLED, true).putString(SmsConfig.KEY_DESTINATION, "+351912345678").putString(RegexListStore.KEY, "test")
        SenderListStore.write(editor, listOf(SenderRule("+351912345678")))
        assertTrue(editor.commit())
        val before = ForwardStatsStore.load(context)
        val viewModel = FiltersViewModel(context.applicationContext as Application)
        val outcome = viewModel.runTest("+351 912 345 678", "test message")
        assertTrue(outcome.text.contains("Sender allowed: yes"))
        assertTrue(outcome.text.contains("SMS loop guard: suppressed"))
        assertEquals(before, ForwardStatsStore.load(context))
    }
}