package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RemoteSmsRuleCommandTest {
    private val key = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"

    @Test
    fun parsesEachAuthenticatedCommandType() {
        val values = mapOf(
            RemoteSmsRuleType.LITERAL_SENDER to "Bank Alerts",
            RemoteSmsRuleType.REGEX_SENDER to "^chave.*digital$",
            RemoteSmsRuleType.MESSAGE_REGEX to """otp\s+\d{6}""",
        )

        values.forEach { (type, value) ->
            val body = command(type, value)
            assertEquals(
                RemoteSmsRuleParseResult.Accepted(
                    RemoteSmsRuleCommand(
                        type = type,
                        value = if (type == RemoteSmsRuleType.LITERAL_SENDER) value.trim() else value,
                    ),
                ),
                RemoteSmsRuleCommands.parse(body, key),
            )
        }
    }

    @Test
    fun acceptsOneTrailingLineEnding() {
        val body = command(RemoteSmsRuleType.MESSAGE_REGEX, "^alert$") + "\r\n"

        assertTrue(RemoteSmsRuleCommands.parse(body, key) is RemoteSmsRuleParseResult.Accepted)
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse("$body\r\n", key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(body.dropLast(1), key),
        )
    }

    @Test
    fun rejectsWrongMacMalformedBodyInvalidEncodingAndInvalidRegex() {
        val valid = command(RemoteSmsRuleType.LITERAL_SENDER, "sender")
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(valid.dropLast(1) + "0", key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse("${RemoteSmsRuleType.LITERAL_SENDER.token}:value", key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse("${RemoteSmsRuleType.LITERAL_SENDER.token}:bad=:${"A".repeat(43)}", key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(command(RemoteSmsRuleType.MESSAGE_REGEX, "["), key),
        )
    }

    @Test
    fun rejectsNonCanonicalPayloadMacAndKeyForms() {
        val type = RemoteSmsRuleType.LITERAL_SENDER
        val paddedPayload = Base64.getUrlEncoder()
            .encodeToString("send".toByteArray(Charsets.UTF_8))
        val paddedContent = "${type.token}:$paddedPayload"
        val paddedCommand = "$paddedContent:${RemoteSmsRuleCommands.hmacBase64Url(key, paddedContent)}"
        val valid = command(type, "sender")

        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(paddedCommand, key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse("$valid=", key),
        )
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(valid, "$key="),
        )

        val invalidUtf8Payload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(byteArrayOf(0xc0.toByte()))
        val invalidUtf8Content = "${type.token}:$invalidUtf8Payload"
        val invalidUtf8Command =
            "$invalidUtf8Content:${RemoteSmsRuleCommands.hmacBase64Url(key, invalidUtf8Content)}"
        assertEquals(
            RemoteSmsRuleParseResult.Rejected,
            RemoteSmsRuleCommands.parse(invalidUtf8Command, key),
        )
    }

    @Test
    fun rejectsBlankMultilineAndOversizedValues() {
        listOf(
            "",
            " ",
            "line one\nline two",
            "x".repeat(RemoteSmsRuleCommands.MAX_VALUE_LENGTH + 1),
        ).forEach { value ->
            assertEquals(
                RemoteSmsRuleParseResult.Rejected,
                RemoteSmsRuleCommands.parse(
                    command(RemoteSmsRuleType.LITERAL_SENDER, value),
                    key,
                ),
            )
        }
    }

    @Test
    fun ignoresNormalSmsAndRecognizesReservedTokens() {
        assertEquals(
            RemoteSmsRuleParseResult.NotCommand,
            RemoteSmsRuleCommands.parse("normal message", key),
        )
        RemoteSmsRuleType.entries.forEach { type ->
            assertTrue(RemoteSmsRuleCommands.isReserved(type.token))
            assertTrue(RemoteSmsRuleCommands.isReserved("${type.token}:sender:mac"))
            assertTrue(RemoteSmsRuleCommands.isReserved("${type.token} malformed"))
            assertEquals(
                RemoteSmsRuleParseResult.Rejected,
                RemoteSmsRuleCommands.parse("${type.token} malformed", key),
            )
        }
        assertTrue(!RemoteSmsRuleCommands.isReserved("MCSMS:sender:mac"))
    }

    @Test
    fun validatesAndNormalizesHmacKeys() {
        val caseChangedKey = key.uppercase()

        assertTrue(isValidRemoteSmsHmacKey(key))
        assertTrue(isValidRemoteSmsHmacKey(caseChangedKey))
        assertNotEquals(key, caseChangedKey)
        assertEquals(key, normalizeRemoteSmsHmacKey("  $key  "))
        assertTrue(!isValidRemoteSmsHmacKey("abcd"))
        assertEquals(43, RemoteSmsRulesConfig.HMAC_KEY_MASK.length)
    }

    @Test
    fun acknowledgementNeverContainsRuleValue() {
        RemoteSmsRuleType.entries.forEach { type ->
            RemoteSmsRuleApplyState.entries.forEach { state ->
                val result = RemoteSmsRuleApplyResult(type = type, state = state)
                assertTrue("secret-pattern" !in result.acknowledgement)
                assertTrue("secret-pattern" !in result.logEntry)
            }
        }
        assertEquals(
            "MC SMS Forwarder: message RegEx added.",
            RemoteSmsRuleApplyResult(
                type = RemoteSmsRuleType.MESSAGE_REGEX,
                state = RemoteSmsRuleApplyState.ADDED,
            ).acknowledgement,
        )
        assertEquals(
            "MC SMS Forwarder: remote rule command rejected.",
            RemoteSmsRuleCommands.REJECTION_ACKNOWLEDGEMENT,
        )
    }

    @Test
    fun mergeAddsAndDeduplicatesEveryRuleType() {
        val literal = mergeRemoteSmsRule(
            existingSenders = emptyList(),
            existingMessageRegexes = emptyList(),
            command = RemoteSmsRuleCommand(RemoteSmsRuleType.LITERAL_SENDER, "Bank Alerts"),
            senderEquivalent = { left, right -> left == right },
        )
        val duplicateLiteral = mergeRemoteSmsRule(
            existingSenders = literal.senders,
            existingMessageRegexes = literal.messageRegexes,
            command = RemoteSmsRuleCommand(RemoteSmsRuleType.LITERAL_SENDER, "Bank Alerts"),
            senderEquivalent = { left, right -> left == right },
        )
        val messageRegex = mergeRemoteSmsRule(
            existingSenders = literal.senders,
            existingMessageRegexes = emptyList(),
            command = RemoteSmsRuleCommand(RemoteSmsRuleType.MESSAGE_REGEX, "^otp$"),
            senderEquivalent = { left, right -> left == right },
        )

        assertEquals(RemoteSmsRuleApplyState.ADDED, literal.result.state)
        assertEquals(listOf(SenderRule("Bank Alerts")), literal.senders)
        assertEquals(RemoteSmsRuleApplyState.ALREADY_PRESENT, duplicateLiteral.result.state)
        assertEquals(RemoteSmsRuleApplyState.ADDED, messageRegex.result.state)
        assertEquals(listOf("^otp$"), messageRegex.messageRegexes)
    }

    @Test
    fun mergeRejectsAFullTargetList() {
        val full = List(RemoteSmsRuleCommands.MAX_LIST_ENTRIES) { "rule-$it" }

        assertThrows(IllegalStateException::class.java) {
            mergeRemoteSmsRule(
                existingSenders = emptyList(),
                existingMessageRegexes = full,
                command = RemoteSmsRuleCommand(RemoteSmsRuleType.MESSAGE_REGEX, "new-rule"),
                senderEquivalent = { left, right -> left == right },
            )
        }
    }

    @Test
    fun parsesPowerShellGeneratedCommand() {
        val command = "MCSMSMR:b3RwXHMrXGR7Nn0:" +
            "gdujvG0esmtgHTRGPzWmCEVjjczXcelH-RcHqYfwQl0"

        assertEquals(
            RemoteSmsRuleParseResult.Accepted(
                RemoteSmsRuleCommand(
                    type = RemoteSmsRuleType.MESSAGE_REGEX,
                    value = """otp\s+\d{6}""",
                ),
            ),
            RemoteSmsRuleCommands.parse(command, key),
        )
    }

    private fun command(type: RemoteSmsRuleType, value: String): String {
        val payload = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        val content = "${type.token}:$payload"
        return "$content:${RemoteSmsRuleCommands.hmacBase64Url(key, content)}"
    }
}
