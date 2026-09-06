package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import android.app.Application
import android.content.Context
import android.telephony.PhoneNumberUtils
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardTemplate
import com.miguelcaldas.mcsmsforwardermultichannel.util.InboundFilterDecision
import com.miguelcaldas.mcsmsforwardermultichannel.util.RegexListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.RemoteSmsRulesConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.SecureStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderListStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderMatcher
import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderRule
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TelegramConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.TextNormalizer
import com.miguelcaldas.mcsmsforwardermultichannel.util.WhatsAppConfig
import com.miguelcaldas.mcsmsforwardermultichannel.util.decideInboundFilter
import com.miguelcaldas.mcsmsforwardermultichannel.util.isValidRemoteSmsHmacKey
import com.miguelcaldas.mcsmsforwardermultichannel.util.normalizeRemoteSmsHmacKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FiltersViewModel(application: Application) : AndroidViewModel(application) {

    enum class Tone { NEUTRAL, POSITIVE }

    data class TestOutcome(val text: String, val tone: Tone)

    private val prefs = application.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    private val _senders = MutableStateFlow(SenderListStore.load(prefs))
    val senders: StateFlow<List<SenderRule>> = _senders.asStateFlow()

    private val _rules = MutableStateFlow(RegexListStore.load(prefs))
    val rules: StateFlow<List<String>> = _rules.asStateFlow()

    private val _template = MutableStateFlow(prefs.getString(ForwardTemplate.KEY, "").orEmpty())
    val template: StateFlow<String> = _template.asStateFlow()

    private val initialRemoteConfig = RemoteSmsRulesConfig.load(application)
    private val _remoteSmsEnabled = MutableStateFlow(initialRemoteConfig.enabled)
    val remoteSmsEnabled: StateFlow<Boolean> = _remoteSmsEnabled.asStateFlow()

    private val _remoteSmsKey = MutableStateFlow(
        if (initialRemoteConfig.hasKey) RemoteSmsRulesConfig.HMAC_KEY_MASK else "",
    )
    val remoteSmsKey: StateFlow<String> = _remoteSmsKey.asStateFlow()

    private val _remoteSmsKeySaved = MutableStateFlow(initialRemoteConfig.hasKey)
    val remoteSmsKeySaved: StateFlow<Boolean> = _remoteSmsKeySaved.asStateFlow()

    private var remoteSmsKeyChanged = false

    // Edits mutate in-memory draft state only; nothing is persisted until save() is called,
    // mirroring the explicit Save button on the channel detail screens. Senders and rules are
    // edited in place as a list of free-text rows: sender rows also carry a literal/RegEx mode.
    // Order is not significant, so rows are addressed by index and blank rows are simply dropped
    // on save() (and ignored by the live pipeline). Invalid-pattern checks are shown by the UI but
    // do not prevent editing or saving.
    fun updateSender(index: Int, value: String) {
        // Newlines would corrupt the newline-delimited store, so collapse them away.
        val sanitized = value.replace('\n', ' ').replace('\r', ' ')
        _senders.value = _senders.value.toMutableList().also {
            if (index in it.indices) {
                it[index] = it[index].copy(value = sanitized)
            }
        }
    }

    fun setSenderRegex(index: Int, isRegex: Boolean) {
        _senders.value = _senders.value.toMutableList().also {
            if (index in it.indices) {
                it[index] = it[index].copy(isRegex = isRegex)
            }
        }
    }

    fun addSender() {
        _senders.value = _senders.value + SenderRule("")
    }

    fun removeSenderAt(index: Int) {
        _senders.value = _senders.value.filterIndexed { i, _ -> i != index }
    }

    fun updateRule(index: Int, value: String) {
        // Rules are newline-delimited in storage too, so a single row can't contain a newline.
        val sanitized = value.replace('\n', ' ').replace('\r', ' ')
        _rules.value = _rules.value.toMutableList().also {
            if (index in it.indices) {
                it[index] = sanitized
            }
        }
    }

    fun addRule() {
        _rules.value = _rules.value + ""
    }

    fun removeRuleAt(index: Int) {
        _rules.value = _rules.value.filterIndexed { i, _ -> i != index }
    }

    fun setTemplate(value: String) {
        _template.value = value
    }

    fun setRemoteSmsEnabled(enabled: Boolean) {
        _remoteSmsEnabled.value = enabled
    }

    fun setRemoteSmsKey(value: String) {
        _remoteSmsKey.value = value.replace('\r', ' ').replace('\n', ' ')
        remoteSmsKeyChanged = _remoteSmsKey.value != RemoteSmsRulesConfig.HMAC_KEY_MASK
    }

    fun removeRemoteSmsKey() {
        _remoteSmsEnabled.value = false
        _remoteSmsKey.value = ""
        _remoteSmsKeySaved.value = false
        remoteSmsKeyChanged = true
    }

    fun refresh() {
        _senders.value = SenderListStore.load(prefs)
        _rules.value = RegexListStore.load(prefs)
        _template.value = prefs.getString(ForwardTemplate.KEY, "").orEmpty()
        val remoteConfig = RemoteSmsRulesConfig.load(getApplication())
        _remoteSmsEnabled.value = remoteConfig.enabled
        _remoteSmsKey.value = if (remoteConfig.hasKey) RemoteSmsRulesConfig.HMAC_KEY_MASK else ""
        _remoteSmsKeySaved.value = remoteConfig.hasKey
        remoteSmsKeyChanged = false
    }

    // The message has no default — it starts blank unless a previous test was run, in which
    // case the last-tested message is restored.
    fun lastTestMessage(): String {
        return prefs.getString(KEY_LAST_TEST_MESSAGE, "").orEmpty()
    }

    // The sender defaults to the first phone-like entry in the (draft) senders list, unless a
    // previous test already used a sender, in which case that saved value wins.
    fun defaultTestSender(): String {
        val saved = prefs.getString(KEY_LAST_TEST_SENDER, null)
        if (!saved.isNullOrBlank()) {
            return saved
        }
        val senders = _senders.value.filter { !it.isRegex && it.value.isNotBlank() }
        return senders.firstOrNull { looksLikePhone(it.value) }?.value ?: senders.firstOrNull()?.value.orEmpty()
    }

    private fun looksLikePhone(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.startsWith("+") || trimmed.firstOrNull()?.isDigit() == true
    }

    // Dry-run mirror of SmsReceiver's pipeline, evaluated against the *currently displayed*
    // (possibly unsaved) draft filters. Keep this in lockstep with the live receiver: the
    // sender must be allowed, the message must match at least one rule, and at least one
    // channel must be operational (toggle on AND credentials complete). Nothing is sent.
    fun runTest(senderRaw: String, messageRaw: String): TestOutcome {
        val context = getApplication<Application>()
        val sender = senderRaw.trim()
        val message = messageRaw

        if (sender.isEmpty() || message.isEmpty()) {
            return TestOutcome("Enter a sender and a message to test.", Tone.NEUTRAL)
        }

        // Remember the inputs so the next test pre-fills with what was last used.
        prefs.edit {
            putString(KEY_LAST_TEST_SENDER, sender)
            putString(KEY_LAST_TEST_MESSAGE, message)
        }

        val allowedSenders = _senders.value.filter { it.value.isNotBlank() }
        val rules = _rules.value.filter { it.isNotBlank() }
        val template = _template.value

        val iso = SenderMatcher.deviceCountryIso(context)
        val senderAllowed = allowedSenders.isNotEmpty() && SenderMatcher.matches(allowedSenders, sender, iso)

        val normalized = TextNormalizer.normalizeForMatching(message)
        // Same as the receiver: compile each rule at most once, silently skip invalid ones, match any.
        val ruleMatches = rules.isNotEmpty() && rules.asSequence()
            .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            .any { it.containsMatchIn(normalized) }

        val waConfig = WhatsAppConfig.load(context)
        val tgConfig = TelegramConfig.load(context)
        val smsConfig = SmsConfig.load(prefs)
        val suppressedByLoopGuard = smsConfig.isOperational &&
            PhoneNumberUtils.areSamePhoneNumber(sender, smsConfig.destination, iso)
        val operationalChannels = buildList {
            if (waConfig.isOperational) {
                add("WhatsApp ${waConfig.recipient}")
            }
            if (tgConfig.isOperational) {
                add("Telegram chat ${tgConfig.chatId}")
            }
            if (smsConfig.isOperational) {
                add("SMS ${smsConfig.destination}")
            }
        }

        val outgoingBody = if (template.isEmpty()) message else ForwardTemplate.apply(template, sender, System.currentTimeMillis(), message)
        val filterDecision = decideInboundFilter(senderAllowed, ruleMatches)
        val wouldSend = !suppressedByLoopGuard &&
            filterDecision == InboundFilterDecision.FORWARD &&
            operationalChannels.isNotEmpty()

        val builder = StringBuilder()
        builder.append("Sender allowed: ").append(if (senderAllowed) "yes" else "no").append(" (against ").append(allowedSenders.size).append(" entries)\n")
        builder.append("Message matches a rule: ").append(if (ruleMatches) "yes" else "no").append(" (against ").append(rules.size).append(" rules)\n")
        if (smsConfig.isOperational) {
            builder.append("SMS loop guard: ").append(if (suppressedByLoopGuard) "suppressed" else "clear").append('\n')
        }
        builder.append("Operational channels: ").append(if (operationalChannels.isEmpty()) "none" else operationalChannels.joinToString(", ")).append('\n')
        builder.append('\n')
        when {
            suppressedByLoopGuard -> builder.append("Would not forward: SMS loop guard.")
            operationalChannels.isEmpty() -> builder.append("Would not forward: no operational channels.")
            wouldSend -> {
                builder.append("Would forward to ").append(operationalChannels.joinToString(", ")).append(":\n")
                builder.append('"').append(outgoingBody).append('"')
            }
            filterDecision == InboundFilterDecision.SENDER_REJECTED ->
                builder.append("Would not forward. Would log FILTER REJECTED: sender did not match.")
            filterDecision == InboundFilterDecision.MESSAGE_RULE_REJECTED ->
                builder.append("Would not forward. Would log FILTER REJECTED: message rule did not match.")
            else -> builder.append("Would not forward or log: neither filter component matched.")
        }

        return TestOutcome(builder.toString(), if (wouldSend) Tone.POSITIVE else Tone.NEUTRAL)
    }

    fun save(): String {
        val normalizedKey = if (remoteSmsKeyChanged) {
            normalizeRemoteSmsHmacKey(_remoteSmsKey.value)
        } else {
            null
        }
        if (normalizedKey != null && normalizedKey.isNotEmpty() && !isValidRemoteSmsHmacKey(normalizedKey)) {
            return "The remote SMS HMAC key must contain exactly 64 hexadecimal characters."
        }
        val effectiveHasKey = when {
            normalizedKey == null -> _remoteSmsKeySaved.value
            normalizedKey.isEmpty() -> false
            else -> true
        }
        if (_remoteSmsEnabled.value && !effectiveHasKey) {
            return "Add a valid HMAC key before enabling remote SMS commands."
        }

        if (normalizedKey != null) {
            SecureStore.write(
                getApplication(),
                SecureStore.KEY_REMOTE_SMS_HMAC,
                normalizedKey,
            )
        }
        prefs.edit {
            SenderListStore.write(this, _senders.value)
            RegexListStore.write(this, _rules.value)
            putString(ForwardTemplate.KEY, _template.value)
            putBoolean(RemoteSmsRulesConfig.KEY_ENABLED, _remoteSmsEnabled.value)
        }
        refresh()
        return saveWarning() ?: "Filters saved"
    }

    // Non-blocking, save-time advisory shown after a successful save. Blank rows are dropped on
    // save (so they're never reported), but a leftover invalid regex would be silently skipped by
    // the live pipeline, which is easy to miss — surface it here so the user can fix it.
    fun saveWarning(): String? {
        val rules = _rules.value.filter { it.isNotBlank() }
        val invalidSenderRules = _senders.value.count { !SenderMatcher.isValidRegex(it) }
        val invalidMessageRules = rules.count { runCatching { Regex(it) }.isFailure }
        if (invalidSenderRules > 0 || invalidMessageRules > 0) {
            return "Saved, but $invalidSenderRules sender and $invalidMessageRules message pattern(s) are invalid and will be ignored."
        }
        return null
    }

    private companion object {
        const val KEY_LAST_TEST_SENDER = "lastTestSender"
        const val KEY_LAST_TEST_MESSAGE = "lastTestMessage"
    }
}
