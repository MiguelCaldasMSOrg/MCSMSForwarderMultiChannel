package com.miguelcaldas.mcsmsforwardermultichannel.ui.channels

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisioningBundle
import com.miguelcaldas.mcsmsforwardermultichannel.util.ProvisioningException
import com.miguelcaldas.mcsmsforwardermultichannel.util.SecureStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppCloudChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException

/** The three outbound channels, used as nav arguments and to pick the right detail form. */
enum class ChannelType(val title: String, val iconRes: Int) {
    WhatsApp("WhatsApp", R.drawable.ic_chat_24),
    Telegram("Telegram", R.drawable.ic_send_24),
    Sms("SMS", R.drawable.ic_sms_24),
}

/** Visual tone for a channel's one-line status. */
enum class ChannelTone {
    READY,
    DISABLED,
    INCOMPLETE,
}

/** Pre-computed row state for the channels list. */
data class ChannelSummary(val type: ChannelType, val enabled: Boolean, val status: String, val tone: ChannelTone)

sealed interface ProvisioningImportState {
    data object Idle : ProvisioningImportState
    data object Importing : ProvisioningImportState
    data class Complete(val message: String) : ProvisioningImportState
}

class ChannelsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: SharedPreferences = application.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    private val _channels = MutableStateFlow<List<ChannelSummary>>(emptyList())
    val channels: StateFlow<List<ChannelSummary>> = _channels.asStateFlow()

    private val _provisioningImportState = MutableStateFlow<ProvisioningImportState>(ProvisioningImportState.Idle)
    val provisioningImportState: StateFlow<ProvisioningImportState> = _provisioningImportState.asStateFlow()

    /** Recompute every channel summary. Call on resume and after any edit. */
    fun refresh() {
        val context = getApplication<Application>()
        val wa = WhatsAppConfig.load(context)
        val tg = TelegramConfig.load(context)
        val sms = SmsConfig.load(prefs)

        _channels.value = listOf(
            summary(ChannelType.WhatsApp, wa.enabled, whatsAppStatus(wa)),
            summary(ChannelType.Telegram, tg.enabled, telegramStatus(tg)),
            summary(ChannelType.Sms, sms.enabled, smsStatus(sms)),
        )
    }

    private fun summary(type: ChannelType, enabled: Boolean, statusPair: Pair<String, ChannelTone>): ChannelSummary {
        return ChannelSummary(type, enabled, statusPair.first, statusPair.second)
    }

    private fun whatsAppStatus(config: WhatsAppConfig): Pair<String, ChannelTone> {
        if (!config.enabled) {
            return "Disabled" to ChannelTone.DISABLED
        }
        if (config.isOperational) {
            return "Ready" to ChannelTone.READY
        }
        return when {
            config.phoneNumberId.isBlank() -> "Missing Phone Number ID"
            config.accessToken.isBlank() -> "Missing access token"
            config.recipient.isBlank() -> "Missing recipient"
            else -> "Setup incomplete"
        } to ChannelTone.INCOMPLETE
    }

    private fun telegramStatus(config: TelegramConfig): Pair<String, ChannelTone> {
        if (!config.enabled) {
            return "Disabled" to ChannelTone.DISABLED
        }
        if (config.isOperational) {
            return "Ready" to ChannelTone.READY
        }
        return when {
            config.botToken.isBlank() -> "Missing bot token"
            config.chatId.isBlank() -> "Missing chat ID"
            else -> "Setup incomplete"
        } to ChannelTone.INCOMPLETE
    }

    private fun smsStatus(config: SmsConfig): Pair<String, ChannelTone> {
        if (!config.enabled) {
            return "Disabled" to ChannelTone.DISABLED
        }
        if (config.isOperational) {
            return "Ready" to ChannelTone.READY
        }
        return "Missing destination" to ChannelTone.INCOMPLETE
    }

    fun setEnabled(type: ChannelType, enabled: Boolean) {
        val key = when (type) {
            ChannelType.WhatsApp -> WhatsAppConfig.KEY_ENABLED
            ChannelType.Telegram -> TelegramConfig.KEY_ENABLED
            ChannelType.Sms -> SmsConfig.KEY_ENABLED
        }
        prefs.edit {
            putBoolean(key, enabled)
        }
        refresh()
    }

    fun saveWhatsApp(enabled: Boolean, phoneNumberId: String, recipient: String, newToken: String?) {
        prefs.edit {
            putBoolean(WhatsAppConfig.KEY_ENABLED, enabled)
            putString(WhatsAppConfig.KEY_PHONE_NUMBER_ID, phoneNumberId.trim())
            putString(WhatsAppConfig.KEY_RECIPIENT, recipient.trim())
        }
        // null means "the mask was left untouched" -> keep the stored token. A non-null
        // value writes it; SecureStore.write removes the secret when the value is blank,
        // so an emptied field clears the token.
        if (newToken != null) {
            SecureStore.write(getApplication(), SecureStore.KEY_WA_ACCESS_TOKEN, newToken.trim())
        }
        refresh()
    }

    fun saveTelegram(enabled: Boolean, chatId: String, newToken: String?) {
        prefs.edit {
            putBoolean(TelegramConfig.KEY_ENABLED, enabled)
            putString(TelegramConfig.KEY_CHAT_ID, chatId.trim())
        }
        if (newToken != null) {
            SecureStore.write(getApplication(), SecureStore.KEY_TG_BOT_TOKEN, newToken.trim())
        }
        refresh()
    }

    fun importProvisioningBundle(uri: Uri, passphrase: String) {
        importProvisioning(passphrase) {
            readProvisioningBundle(uri)
        }
    }

    fun importProvisioningCode(importCode: String, passphrase: String) {
        importProvisioning(passphrase) {
            ProvisioningBundle.decodeImportCode(importCode)
        }
    }

    fun reportProvisioningMessage(message: String) {
        _provisioningImportState.value = ProvisioningImportState.Complete(message)
    }

    private fun importProvisioning(passphrase: String, readBundle: () -> String) {
        if (_provisioningImportState.value == ProvisioningImportState.Importing) {
            return
        }
        _provisioningImportState.value = ProvisioningImportState.Importing
        val passphraseCharacters = passphrase.toCharArray()

        viewModelScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    val bundleJson = readBundle()
                    val configuration = ProvisioningBundle.decrypt(bundleJson, passphraseCharacters)
                    ProvisioningBundle.save(getApplication(), configuration)
                } catch (error: ProvisioningException) {
                    error.message ?: "Could not import the configuration."
                } catch (_: IOException) {
                    "Could not read the selected configuration file."
                } catch (_: SecurityException) {
                    "Android did not grant access to the selected configuration file."
                } catch (_: GeneralSecurityException) {
                    "Android could not securely store the imported credentials."
                } catch (_: IllegalStateException) {
                    "Android could not save the imported configuration."
                } finally {
                    passphraseCharacters.fill('\u0000')
                }
            }
            refresh()
            _provisioningImportState.value = ProvisioningImportState.Complete(message)
        }
    }

    fun clearProvisioningImportResult() {
        if (_provisioningImportState.value is ProvisioningImportState.Complete) {
            _provisioningImportState.value = ProvisioningImportState.Idle
        }
    }

    @Throws(IOException::class, ProvisioningException::class)
    private fun readProvisioningBundle(uri: Uri): String {
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: throw IOException("The selected document could not be opened")
        return input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var totalBytes = 0
            while (true) {
                val bytesRead = it.read(buffer)
                if (bytesRead < 0) {
                    break
                }
                totalBytes += bytesRead
                if (totalBytes > ProvisioningBundle.MAX_BUNDLE_BYTES) {
                    throw ProvisioningException("The configuration file is too large.")
                }
                output.write(buffer, 0, bytesRead)
            }
            val bytes = output.toByteArray()
            try {
                String(bytes, Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        }
    }

    /** Returns the loop-guard advisory shown after saving SMS settings, or null. */
    fun saveSms(enabled: Boolean, destination: String): String? {
        prefs.edit {
            putBoolean(SmsConfig.KEY_ENABLED, enabled)
            putString(SmsConfig.KEY_DESTINATION, destination.trim())
        }
        refresh()
        return smsDestinationLoopWarning()
    }

    private fun smsDestinationLoopWarning(): String? {
        val config = SmsConfig.load(prefs)
        if (!config.enabled || config.destination.isEmpty()) {
            return null
        }
        val allowed = SenderListStore.load(prefs)
        if (allowed.isEmpty()) {
            return null
        }
        val iso = SenderMatcher.deviceCountryIso(getApplication())
        return if (SenderMatcher.matches(allowed, config.destination, iso)) {
            "SMS destination is also an allowed sender. The loop guard will suppress its replies."
        } else {
            null
        }
    }

    /** Fires a manual WhatsApp test send. Returns the message to show the user. */
    fun sendWhatsAppTest(phoneNumberId: String, recipient: String, accessToken: String): String {
        val context = getApplication<Application>()
        val config = WhatsAppConfig(
            enabled = true,
            phoneNumberId = phoneNumberId.trim(),
            accessToken = accessToken.trim(),
            recipient = recipient.trim(),
        )
        if (!config.hasCredentials) {
            return "Set Phone Number ID, access token, and recipient first."
        }
        val body = "MC SMS\u2192WhatsApp Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(context, "REAL SEND [WhatsApp] \u2192 To: ${config.recipient} | Msg: $body (manual test)")
        val started = WhatsAppCloudChannel.send(context, config, body)
        return if (started) "Sending test message\u2026 see Activity log." else "Could not start test send; see Activity log."
    }

    fun sendTelegramTest(chatId: String, botToken: String): String {
        val context = getApplication<Application>()
        val config = TelegramConfig(enabled = true, botToken = botToken.trim(), chatId = chatId.trim())
        if (!config.hasCredentials) {
            return "Set bot token and chat ID first."
        }
        val body = "MC SMS\u2192Telegram Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(context, "REAL SEND [Telegram] \u2192 To: chat ${config.chatId} | Msg: $body (manual test)")
        val started = TelegramChannel.send(context, config, body)
        return if (started) "Sending Telegram test\u2026 see Activity log." else "Could not start Telegram test; see Activity log."
    }

    fun sendSmsTest(destination: String): String {
        val context = getApplication<Application>()
        val config = SmsConfig(enabled = true, destination = destination.trim())
        if (!config.hasCredentials) {
            return "Set the SMS destination number first."
        }
        val body = "MC SMS\u2192SMS Test \u2014 manual test send at ${System.currentTimeMillis()}"
        LogUtils.addToLog(context, "REAL SEND [SMS] \u2192 To: ${config.destination} | Msg: $body (manual test)")
        val dispatched = SmsChannel.send(context, config, body)
        return if (dispatched) "SMS test handed to the modem\u2026 see Activity log." else "SMS test dispatch failed; see Activity log."
    }
}
