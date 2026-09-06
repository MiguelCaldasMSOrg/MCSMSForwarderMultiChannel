package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal enum class RemoteSmsRuleType(
    val token: String,
    val displayName: String,
) {
    LITERAL_SENDER("MCSMSSL", "literal sender"),
    REGEX_SENDER("MCSMSSR", "sender RegEx"),
    MESSAGE_REGEX("MCSMSMR", "message RegEx");

    companion object {
        fun fromToken(token: String): RemoteSmsRuleType? = entries.firstOrNull { it.token == token }
    }
}

internal data class RemoteSmsRuleCommand(
    val type: RemoteSmsRuleType,
    val value: String,
)

internal sealed interface RemoteSmsRuleParseResult {
    data object NotCommand : RemoteSmsRuleParseResult
    data object Rejected : RemoteSmsRuleParseResult
    data class Accepted(val command: RemoteSmsRuleCommand) : RemoteSmsRuleParseResult
}

internal enum class RemoteSmsRuleApplyState {
    ADDED,
    ALREADY_PRESENT,
}

internal data class RemoteSmsRuleApplyResult(
    val type: RemoteSmsRuleType,
    val state: RemoteSmsRuleApplyState,
) {
    val acknowledgement: String
        get() = when (state) {
            RemoteSmsRuleApplyState.ADDED ->
                "MC SMS Forwarder: ${type.displayName} added."
            RemoteSmsRuleApplyState.ALREADY_PRESENT ->
                "MC SMS Forwarder: ${type.displayName} already present."
        }

    val logEntry: String
        get() = when (state) {
            RemoteSmsRuleApplyState.ADDED ->
                "REMOTE RULE ADDED [${type.displayName}]"
            RemoteSmsRuleApplyState.ALREADY_PRESENT ->
                "REMOTE RULE ALREADY PRESENT [${type.displayName}]"
        }
}

internal data class RemoteSmsRuleMerge(
    val senders: List<SenderRule>,
    val messageRegexes: List<String>,
    val result: RemoteSmsRuleApplyResult,
)

internal fun mergeRemoteSmsRule(
    existingSenders: List<SenderRule>,
    existingMessageRegexes: List<String>,
    command: RemoteSmsRuleCommand,
    senderEquivalent: (SenderRule, SenderRule) -> Boolean,
): RemoteSmsRuleMerge {
    return when (command.type) {
        RemoteSmsRuleType.LITERAL_SENDER,
        RemoteSmsRuleType.REGEX_SENDER,
        -> {
            val candidate = SenderRule(
                value = command.value,
                isRegex = command.type == RemoteSmsRuleType.REGEX_SENDER,
            )
            val alreadyPresent = existingSenders.any { senderEquivalent(it, candidate) }
            if (!alreadyPresent) {
                check(existingSenders.size < RemoteSmsRuleCommands.MAX_LIST_ENTRIES) {
                    "The sender list is full."
                }
            }
            RemoteSmsRuleMerge(
                senders = if (alreadyPresent) existingSenders else existingSenders + candidate,
                messageRegexes = existingMessageRegexes,
                result = RemoteSmsRuleApplyResult(
                    type = command.type,
                    state = if (alreadyPresent) {
                        RemoteSmsRuleApplyState.ALREADY_PRESENT
                    } else {
                        RemoteSmsRuleApplyState.ADDED
                    },
                ),
            )
        }
        RemoteSmsRuleType.MESSAGE_REGEX -> {
            val alreadyPresent = command.value in existingMessageRegexes
            if (!alreadyPresent) {
                check(existingMessageRegexes.size < RemoteSmsRuleCommands.MAX_LIST_ENTRIES) {
                    "The message rule list is full."
                }
            }
            RemoteSmsRuleMerge(
                senders = existingSenders,
                messageRegexes = if (alreadyPresent) {
                    existingMessageRegexes
                } else {
                    existingMessageRegexes + command.value
                },
                result = RemoteSmsRuleApplyResult(
                    type = command.type,
                    state = if (alreadyPresent) {
                        RemoteSmsRuleApplyState.ALREADY_PRESENT
                    } else {
                        RemoteSmsRuleApplyState.ADDED
                    }
                ),
            )
        }
    }
}

internal object RemoteSmsRuleCommands {
    const val REJECTION_ACKNOWLEDGEMENT = "MC SMS Forwarder: remote rule command rejected."
    const val REJECTION_LOG = "REMOTE RULE REJECTED"

    internal const val MAX_VALUE_LENGTH = 4_096
    internal const val MAX_LIST_ENTRIES = 1_000
    private val PAYLOAD_PATTERN = Regex("[A-Za-z0-9_-]+")
    private val MAC_PATTERN = Regex("[0-9a-f]{64}")

    fun isReserved(body: String): Boolean {
        val token = canonicalize(body).substringBefore(':')
        return RemoteSmsRuleType.fromToken(token) != null
    }

    fun parse(body: String, hmacKey: String): RemoteSmsRuleParseResult {
        val canonicalBody = canonicalize(body)
        val token = canonicalBody.substringBefore(':')
        val type = RemoteSmsRuleType.fromToken(token) ?: return RemoteSmsRuleParseResult.NotCommand
        if (!isValidRemoteSmsHmacKey(hmacKey)) {
            return RemoteSmsRuleParseResult.Rejected
        }

        if (canonicalBody.contains('\r') || canonicalBody.contains('\n')) {
            return RemoteSmsRuleParseResult.Rejected
        }
        val fields = canonicalBody.split(':')
        if (fields.size != 3) {
            return RemoteSmsRuleParseResult.Rejected
        }
        val payload = fields[1]
        val suppliedMac = fields[2]
        if (!PAYLOAD_PATTERN.matches(payload) || !MAC_PATTERN.matches(suppliedMac)) {
            return RemoteSmsRuleParseResult.Rejected
        }

        val expectedMac = hmacHex(
            keyHex = normalizeRemoteSmsHmacKey(hmacKey),
            text = "${type.token}:$payload",
        )
        if (
            !MessageDigest.isEqual(
                expectedMac.toByteArray(Charsets.US_ASCII),
                suppliedMac.toByteArray(Charsets.US_ASCII),
            )
        ) {
            return RemoteSmsRuleParseResult.Rejected
        }

        val value = decodePayload(payload) ?: return RemoteSmsRuleParseResult.Rejected
        val storedValue = when (type) {
            RemoteSmsRuleType.LITERAL_SENDER -> value.trim()
            RemoteSmsRuleType.REGEX_SENDER,
            RemoteSmsRuleType.MESSAGE_REGEX,
            -> value
        }
        if (
            storedValue.isBlank() ||
            storedValue.length > MAX_VALUE_LENGTH ||
            storedValue.contains('\r') ||
            storedValue.contains('\n')
        ) {
            return RemoteSmsRuleParseResult.Rejected
        }
        if (
            type != RemoteSmsRuleType.LITERAL_SENDER &&
            runCatching { Regex(storedValue) }.isFailure
        ) {
            return RemoteSmsRuleParseResult.Rejected
        }

        return RemoteSmsRuleParseResult.Accepted(
            RemoteSmsRuleCommand(type = type, value = storedValue),
        )
    }

    @SuppressLint("UseKtx")
    @Synchronized
    fun apply(
        context: Context,
        prefs: SharedPreferences,
        command: RemoteSmsRuleCommand,
    ): RemoteSmsRuleApplyResult {
        val existingSenders = SenderListStore.load(prefs)
        val existingRegexes = RegexListStore.load(prefs)
        val countryIso = SenderMatcher.deviceCountryIso(context)
        val merged = mergeRemoteSmsRule(
            existingSenders = existingSenders,
            existingMessageRegexes = existingRegexes,
            command = command,
            senderEquivalent = { existing, candidate ->
                SenderMatcher.equivalent(existing, candidate, countryIso)
            },
        )
        if (merged.result.state == RemoteSmsRuleApplyState.ADDED) {
            val editor = prefs.edit()
            when (command.type) {
                RemoteSmsRuleType.LITERAL_SENDER,
                RemoteSmsRuleType.REGEX_SENDER,
                -> SenderListStore.write(editor, merged.senders)
                RemoteSmsRuleType.MESSAGE_REGEX ->
                    RegexListStore.write(editor, merged.messageRegexes)
            }
            check(editor.commit()) {
                "Could not save the remote rule."
            }
        }
        return merged.result
    }

    internal fun hmacHex(keyHex: String, text: String): String {
        val normalizedKey = normalizeRemoteSmsHmacKey(keyHex)
        require(isValidRemoteSmsHmacKey(normalizedKey))
        val key = decodeHex(normalizedKey)
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            mac.doFinal(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }
        } finally {
            key.fill(0)
        }
    }

    private fun canonicalize(body: String): String =
        when {
            body.endsWith("\r\n") -> body.dropLast(2)
            body.endsWith('\n') -> body.dropLast(1)
            else -> body
        }

    private fun decodePayload(payload: String): String? {
        val bytes = try {
            Base64.getUrlDecoder().decode(payload)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            if (Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != payload) {
                return null
            }
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun decodeHex(value: String): ByteArray =
        ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}
