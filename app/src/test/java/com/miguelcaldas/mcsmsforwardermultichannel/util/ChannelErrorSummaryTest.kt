package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.net.URLEncoder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelErrorSummaryTest {
    private val secret = "audit-only-secret:" + "synthetic".repeat(40)

    @Test
    fun whatsAppRedactsLongTokenBeforeTruncatingParsedMessage() {
        val error = JSONObject().put("code", 190).put("type", "OAuthException").put("message", "Invalid token: $secret")
        val raw = JSONObject().put("error", error).toString()

        assertEquals("code=190 type=OAuthException msg=Invalid token: [redacted]", WhatsAppCloudChannel.summarizeError(raw, secret))
    }

    @Test
    fun telegramRedactsEncodedTokenBeforeTruncatingParsedMessage() {
        val encoded = URLEncoder.encode(secret, "UTF-8")
        val raw = JSONObject().put("error_code", 400).put("description", "Invalid token: $encoded").toString()

        assertEquals("code=400 desc=Invalid token: [redacted]", TelegramChannel.summarizeError(raw, secret))
    }

    @Test
    fun tokensCrossingSummaryBoundaryLeaveNoPrefix() {
        val prefix = "context ".repeat(25)
        val whatsAppRaw = JSONObject().put("error", JSONObject().put("message", prefix + secret)).toString()
        val telegramRaw = JSONObject().put("description", prefix + secret).toString()

        val summaries = listOf(WhatsAppCloudChannel.summarizeError(whatsAppRaw, secret), TelegramChannel.summarizeError(telegramRaw, secret))
        summaries.forEach { summary ->
            assertFalse(summary.contains("audit-only-secret"))
            assertTrue(summary.contains("[redacted]"))
            assertTrue(summary.length <= 240)
        }
    }

    @Test
    fun malformedResponsesAreRedactedBeforeFallbackTruncation() {
        val raw = "Invalid token: $secret"
        val encodedRaw = "Invalid token: " + URLEncoder.encode(secret, "UTF-8")

        assertEquals("Invalid token: [redacted]", WhatsAppCloudChannel.summarizeError(raw, secret))
        assertEquals("Invalid token: [redacted]", TelegramChannel.summarizeError(raw, secret))
        assertEquals("Invalid token: [redacted]", TelegramChannel.summarizeError(encodedRaw, secret))
    }

    @Test
    fun missingWhatsAppErrorObjectStillRedactsBeforeTruncation() {
        val raw = JSONObject().put("echo", secret).toString()

        assertEquals(JSONObject().put("echo", "[redacted]").toString(), WhatsAppCloudChannel.summarizeError(raw, secret))
    }

    @Test
    fun escapedTokensAreRedactedAfterJsonDecoding() {
        val escapedSecret = "audit-only-\"quoted\\token"
        val whatsAppRaw = JSONObject().put("error", JSONObject().put("message", escapedSecret)).toString()
        val telegramRaw = JSONObject().put("description", escapedSecret).toString()

        assertEquals("msg=[redacted]", WhatsAppCloudChannel.summarizeError(whatsAppRaw, escapedSecret))
        assertEquals("desc=[redacted]", TelegramChannel.summarizeError(telegramRaw, escapedSecret))
    }

    @Test
    fun diagnosticLengthLimitsArePreserved() {
        val description = "detail ".repeat(100)
        val whatsAppRaw = JSONObject().put("error", JSONObject().put("message", description)).toString()
        val telegramRaw = JSONObject().put("description", description).toString()

        assertEquals(240, WhatsAppCloudChannel.summarizeError(whatsAppRaw, secret).length)
        assertEquals(240, TelegramChannel.summarizeError(telegramRaw, secret).length)
        assertEquals(180, WhatsAppCloudChannel.summarizeError(description, secret).length)
        assertEquals(180, TelegramChannel.summarizeError(description, secret).length)
    }

    @Test
    fun emptyResponsesProduceNoDiagnostic() {
        assertEquals("", WhatsAppCloudChannel.summarizeError("", secret))
        assertEquals("", TelegramChannel.summarizeError(" ", secret))
    }
}