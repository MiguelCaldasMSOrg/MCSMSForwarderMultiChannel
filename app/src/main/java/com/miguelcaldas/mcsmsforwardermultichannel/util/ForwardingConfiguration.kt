package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context

internal data class ForwardingConfiguration(val masterEnabled: Boolean, val whatsApp: WhatsAppConfig, val telegram: TelegramConfig, val sms: SmsConfig, val senders: List<SenderRule>, val rules: List<String>, val template: String, val countryIso: String) {
    val hasOperationalChannel: Boolean get() = whatsApp.isOperational || telegram.isOperational || sms.isOperational

    companion object {
        fun load(context: Context): ForwardingConfiguration = FilterRuleMutationCoordinator.withLock {
            val prefs = context.getSharedPreferences(WhatsAppConfig.PREFS_NAME, Context.MODE_PRIVATE)
            ForwardingConfiguration(MasterSwitchStore.load(prefs), WhatsAppConfig.load(context), TelegramConfig.load(context), SmsConfig.load(prefs), SenderListStore.load(prefs).toList(), RegexListStore.load(prefs).toList(), prefs.getString(ForwardTemplate.KEY, "").orEmpty(), SenderMatcher.deviceCountryIso(context))
        }
    }
}

internal data class ForwardingEvaluation(val senderMatches: Boolean, val messageMatches: Boolean, val suppressedByLoopGuard: Boolean, val outgoingBody: String) {
    val decision: InboundFilterDecision get() = decideInboundFilter(senderMatches, messageMatches)
}

internal object ForwardingEvaluator {
    fun evaluate(configuration: ForwardingConfiguration, sender: String, message: String, timestampMillis: Long): ForwardingEvaluation {
        val suppressed = configuration.sms.isOperational && PhoneNumberCompat.areSame(sender, configuration.sms.destination, configuration.countryIso)
        val normalized = TextNormalizer.normalizeForMatching(message)
        val messageMatches = configuration.rules.any { pattern ->
            pattern.isNotBlank() && runCatching { Regex(pattern).containsMatchIn(normalized) }.getOrDefault(false)
        }
        val senderMatches = SenderMatcher.matches(configuration.senders, sender, configuration.countryIso)
        val outgoing = if (senderMatches && messageMatches && !suppressed) {
            require(message.length <= ForwardTemplate.MAX_OUTPUT_CHARS) { "Forwarded message exceeds 256 Ki characters" }
            if (configuration.template.isEmpty()) message else ForwardTemplate.apply(configuration.template, sender, timestampMillis, message)
        } else {
            message
        }
        return ForwardingEvaluation(senderMatches, messageMatches, suppressed, outgoing)
    }
}