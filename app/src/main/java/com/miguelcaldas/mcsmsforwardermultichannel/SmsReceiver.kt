package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardTemplate
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.MasterSwitchStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TextNormalizer
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppCloudChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SmsReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }

        val prefs = context.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)
        // Master kill-switch: one prefs key checked before any work. Default ON so existing
        // installs are unaffected.
        if (!MasterSwitchStore.load(prefs)) {
            return
        }

        val waConfig = WhatsAppConfig.load(context)
        val tgConfig = TelegramConfig.load(context)
        val smsConfig = SmsConfig.load(prefs)
        // Bail before any other work if no outbound channel is enabled+configured.
        if (!waConfig.isOperational && !tgConfig.isOperational && !smsConfig.isOperational) {
            return
        }

        val allowedSenders = SenderListStore.load(prefs)
        if (allowedSenders.isEmpty()) {
            return
        }
        val patterns = RegexListStore.load(prefs)
        if (patterns.isEmpty()) {
            return
        }
        val forwardTemplate = prefs.getString(ForwardTemplate.KEY, "").orEmpty()

        // The telephony framework reassembles concatenated SMS using the UDH (reference,
        // total parts, sequence number) and only broadcasts SMS_RECEIVED once every part
        // has arrived. The returned array therefore represents a single logical message
        // with its segments already ordered; concatenating their bodies yields the full text.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) {
            return
        }

        val sender = messages[0].originatingAddress ?: return
        val fullBody = buildString {
            for (sms in messages) {
                append(sms.messageBody ?: "")
            }
        }

        val countryIso = SenderMatcher.deviceCountryIso(context)

        // Loop guard (SMS channel only): never re-forward a message that arrived from our
        // own SMS forward destination. Same-transport echo would otherwise bounce
        // indefinitely if the destination is also an allowed sender. WhatsApp/Telegram
        // run on a different transport and cannot trigger this, so the guard is scoped
        // to the SMS channel's destination.
        if (smsConfig.isOperational && PhoneNumberUtils.areSamePhoneNumber(sender, smsConfig.destination, countryIso)) {
            LogUtils.addToLog(context, "LOOP GUARD \u2192 suppressed from $sender (= SMS forward destination)")
            return
        }

        if (!SenderMatcher.matches(allowedSenders, sender, countryIso)) {
            return
        }

        // Compile each pattern at most once per call; the previous form rebuilt Regex
        // objects inside `any { }` on every iteration. Patterns that fail to compile
        // are silently treated as non-matches — a single malformed entry never blocks
        // the others. Diacritics are stripped and the body is lowercased before matching
        // so patterns can be written without accents or case worries; the original body
        // (accents and case preserved) is still what gets forwarded.
        val normalizedBody = TextNormalizer.normalizeForMatching(fullBody)
        val bodyMatches = patterns.asSequence()
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .any { it.containsMatchIn(normalizedBody) }
        if (!bodyMatches) {
            return
        }

        val outgoingBody = if (forwardTemplate.isEmpty()) fullBody else ForwardTemplate.apply(forwardTemplate, sender, messages[0].timestampMillis, fullBody)

        // Network I/O must outlive onReceive returning, so hand the receiver off to
        // goAsync() and let each channel report completion. The forward stat is
        // incremented at most once per SMS, regardless of how many channels succeeded.
        val pending = goAsync()
        val app = context.applicationContext
        val sendViaWa = waConfig.isOperational
        val sendViaTg = tgConfig.isOperational
        val sendViaSms = smsConfig.isOperational
        val remaining = AtomicInteger((if (sendViaWa) 1 else 0) + (if (sendViaTg) 1 else 0) + (if (sendViaSms) 1 else 0))
        val anySuccess = AtomicBoolean(false)
        val onChannelDone: (Boolean) -> Unit = { success ->
            if (success) {
                anySuccess.set(true)
            }
            if (remaining.decrementAndGet() == 0) {
                if (anySuccess.get()) {
                    ForwardStatsStore.recordForward(app)
                }
                pending.finish()
            }
        }

        if (sendViaWa) {
            LogUtils.addToLog(context, "REAL SEND [WhatsApp] \u2192 To: ${waConfig.recipient} | Msg: $outgoingBody")
            WhatsAppCloudChannel.send(context, waConfig, outgoingBody, onChannelDone)
        }
        if (sendViaTg) {
            LogUtils.addToLog(context, "REAL SEND [Telegram] \u2192 To: chat ${tgConfig.chatId} | Msg: $outgoingBody")
            TelegramChannel.send(context, tgConfig, outgoingBody, onChannelDone)
        }
        if (sendViaSms) {
            LogUtils.addToLog(context, "REAL SEND [SMS] \u2192 To: ${smsConfig.destination} | Msg: $outgoingBody")
            SmsChannel.send(context, smsConfig, outgoingBody, onChannelDone)
        }
    }
}
