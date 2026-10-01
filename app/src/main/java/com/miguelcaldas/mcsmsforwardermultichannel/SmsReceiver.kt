package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.miguelcaldas.mcsmsforwardermultichannel.util.FilterRuleMutationCoordinator
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardingConfiguration
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardingEvaluator
import com.miguelcaldas.mcsmsforwardermultichannel.util.InboundFilterDecision
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.MasterSwitchStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.PhoneNumberCompat
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRuleCommands
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRuleParseResult
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRulesConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppCloudChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.boundedDaemonExecutor
import com.miguelcaldas.mcsmsforwardermultichannel.util.scheduleDeadline
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SmsReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9)
        val expiration = scheduleDeadline(9_000) {
            if (finished.compareAndSet(false, true)) {
                try {
                    LogUtils.addToLog(app, "SMS PROCESSING EXPIRED -> delivery may be unknown; no automatic retry")
                } finally {
                    pending.finish()
                }
            }
        }
        val complete: () -> Unit = {
            if (finished.compareAndSet(false, true)) {
                expiration.cancel(false)
                pending.finish()
            }
        }
        val active: () -> Boolean = { !finished.get() && System.nanoTime() < deadline }
        try {
            processingExecutor.execute {
                var waitingForChannels = false
                try {
                    if (active()) {
                        waitingForChannels = process(app, intent, complete, active, deadline)
                    }
                } catch (_: Exception) {
                    LogUtils.addToLog(app, "SMS PROCESSING FAILED -> configuration or processing unavailable")
                } finally {
                    if (!waitingForChannels) {
                        complete()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            try {
                LogUtils.addToLog(app, "SMS PROCESSING REJECTED -> processing capacity exhausted; no automatic retry")
            } finally {
                complete()
            }
        }
    }

    private fun process(context: Context, intent: Intent, complete: () -> Unit, active: () -> Boolean, deadline: Long): Boolean {
        // The telephony framework reassembles concatenated SMS using the UDH (reference,
        // total parts, sequence number) and only broadcasts SMS_RECEIVED once every part
        // has arrived. The returned array therefore represents a single logical message
        // with its segments already ordered; concatenating their bodies yields the full text.
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return false
        if (messages.isEmpty()) {
            return false
        }

        val fullBody = buildString {
            for (sms in messages) {
                append(sms.messageBody ?: "")
            }
        }

        val prefs = context.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)
        if (RemoteSmsRuleCommands.isReserved(fullBody)) {
            return handleRemoteSmsRuleCommand(context, prefs, fullBody, complete, active, deadline)
        }

        val sender = messages[0].originatingAddress ?: return false

        // Master kill-switch for ordinary forwarding; reserved remote commands were handled above.
        // Fresh installs default ON.
        val configuration = FilterRuleMutationCoordinator.withLock {
            if (!MasterSwitchStore.load(prefs) || !active()) null else ForwardingConfiguration.load(context)
        } ?: return false
        if (!configuration.hasOperationalChannel || !active()) {
            return false
        }

        // Loop guard (SMS channel only): never re-forward a message that arrived from our
        // own SMS forward destination. Same-transport echo would otherwise bounce
        // indefinitely if the destination is also an allowed sender. WhatsApp/Telegram
        // run on a different transport and cannot trigger this, so the guard is scoped
        // to the SMS channel's destination.
        if (configuration.sms.isOperational && PhoneNumberCompat.areSame(sender, configuration.sms.destination, configuration.countryIso)) {
            LogUtils.addToLog(context, "LOOP GUARD \u2192 suppressed from $sender (= SMS forward destination)")
            return false
        }

        val evaluation = ForwardingEvaluator.evaluate(configuration, sender, fullBody, messages[0].timestampMillis)
        if (!active()) {
            return false
        }
        when (evaluation.decision) {
            InboundFilterDecision.FORWARD -> Unit
            InboundFilterDecision.SENDER_REJECTED -> {
                LogUtils.addToLog(
                    context,
                    "${LogUtils.FILTER_REJECTED_PREFIX} \u2192 Sender did not match | " +
                        "Raw from: $sender | Raw message: $fullBody",
                )
                return false
            }
            InboundFilterDecision.MESSAGE_RULE_REJECTED -> {
                LogUtils.addToLog(
                    context,
                    "${LogUtils.FILTER_REJECTED_PREFIX} \u2192 Message rule did not match | " +
                        "Raw from: $sender | Raw message: $fullBody",
                )
                return false
            }
            InboundFilterDecision.IGNORE -> return false
        }
        return send(context, configuration, evaluation.outgoingBody, true, complete, active, deadline)
    }

    private fun send(context: Context, configuration: ForwardingConfiguration, outgoingBody: String, recordForward: Boolean, complete: () -> Unit, active: () -> Boolean, deadline: Long): Boolean {
        if (!active()) {
            return false
        }
        val app = context.applicationContext
        val waConfig = configuration.whatsApp
        val tgConfig = configuration.telegram
        val smsConfig = configuration.sms
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
                try {
                    if (recordForward && anySuccess.get()) {
                        ForwardStatsStore.recordForward(app)
                    }
                } finally {
                    complete()
                }
            }
        }

        if (sendViaWa) {
            if (recordForward) {
                LogUtils.addToLog(context, "REAL SEND [WhatsApp] \u2192 To: ${waConfig.recipient} | Msg: $outgoingBody")
            }
            if (active()) {
                WhatsAppCloudChannel.send(context, waConfig, outgoingBody, onChannelDone, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
            } else {
                onChannelDone(false)
            }
        }
        if (sendViaTg) {
            if (recordForward) {
                LogUtils.addToLog(context, "REAL SEND [Telegram] \u2192 To: chat ${tgConfig.chatId} | Msg: $outgoingBody")
            }
            if (active()) {
                TelegramChannel.send(context, tgConfig, outgoingBody, onChannelDone, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
            } else {
                onChannelDone(false)
            }
        }
        if (sendViaSms) {
            if (recordForward) {
                LogUtils.addToLog(context, "REAL SEND [SMS] \u2192 To: ${smsConfig.destination} | Msg: $outgoingBody")
            }
            if (active()) {
                SmsChannel.send(context, smsConfig, outgoingBody, onChannelDone)
            } else {
                onChannelDone(false)
            }
        }
        return true
    }

    private fun handleRemoteSmsRuleCommand(
        context: Context,
        prefs: android.content.SharedPreferences,
        body: String,
        complete: () -> Unit,
        active: () -> Boolean,
        deadline: Long,
    ): Boolean {
        val response = FilterRuleMutationCoordinator.withLock {
            val config = RemoteSmsRulesConfig.load(context)
            if (!config.isOperational || !active()) {
                return@withLock null
            }

            val acknowledgement = when (val parsed = RemoteSmsRuleCommands.parse(body, config.hmacKey)) {
                RemoteSmsRuleParseResult.NotCommand -> null
                RemoteSmsRuleParseResult.Rejected -> {
                    LogUtils.addToLog(context, RemoteSmsRuleCommands.REJECTION_LOG)
                    RemoteSmsRuleCommands.REJECTION_ACKNOWLEDGEMENT
                }
                is RemoteSmsRuleParseResult.Accepted -> {
                    val result = try {
                        RemoteSmsRuleCommands.apply(context, prefs, parsed.command)
                    } catch (_: IllegalStateException) {
                        LogUtils.addToLog(context, RemoteSmsRuleCommands.REJECTION_LOG)
                        null
                    }
                    if (result == null) {
                        RemoteSmsRuleCommands.REJECTION_ACKNOWLEDGEMENT
                    } else {
                        LogUtils.addToLog(context, result.logEntry)
                        result.acknowledgement
                    }
                }
            } ?: return@withLock null
            acknowledgement to ForwardingConfiguration.load(context)
        } ?: return false
        if (!response.second.hasOperationalChannel) {
            LogUtils.addToLog(context, RemoteSmsRuleCommands.ACK_SKIPPED_NO_CHANNELS_LOG)
            return false
        }
        return send(context, response.second, response.first, false, complete, active, deadline)
    }

    private companion object {
        val processingExecutor = boundedDaemonExecutor("sms-evaluator", 2, 32)
    }
}
