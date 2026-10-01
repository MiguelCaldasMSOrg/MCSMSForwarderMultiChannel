package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ForwardingEvaluatorTest {
    @Test
    fun repetitiveTemplatesFailBeforeAllocatingUnboundedOutput() {
        assertThrows(IllegalArgumentException::class.java) {
            ForwardTemplate.apply("%m".repeat(2000), "sender", 0, "payload".repeat(1000))
        }
        assertEquals("%m %s", ForwardTemplate.apply("%m", "source", 0, "%m %s"))
    }

    private fun configuration() = ForwardingConfiguration(false, WhatsAppConfig(false, "", "", ""), TelegramConfig(false, "", ""), SmsConfig(false, ""), listOf(SenderRule("bank")), listOf("[", "codigo"), "%s: %m", "pt")

    @Test
    fun matchingPreservesRawBodyAndDoesNotApplyMasterSwitch() {
        val result = ForwardingEvaluator.evaluate(configuration(), "Bank", "Codigo 123", 0)
        assertEquals(InboundFilterDecision.FORWARD, result.decision)
        assertEquals("Bank: Codigo 123", result.outgoingBody)
        assertFalse(result.suppressedByLoopGuard)
    }

    @Test
    fun xorDiagnosticsAndDoubleRejectionArePreserved() {
        assertEquals(InboundFilterDecision.MESSAGE_RULE_REJECTED, ForwardingEvaluator.evaluate(configuration(), "Bank", "other", 0).decision)
        assertEquals(InboundFilterDecision.SENDER_REJECTED, ForwardingEvaluator.evaluate(configuration(), "other", "codigo", 0).decision)
        assertEquals(InboundFilterDecision.IGNORE, ForwardingEvaluator.evaluate(configuration(), "other", "other", 0).decision)
    }

    @Test
    fun loopGuardUsesTheSameCountryAwareComparison() {
        val snapshot = configuration().copy(sms = SmsConfig(true, "+351912345678"), senders = listOf(SenderRule("+351912345678")))
        val result = ForwardingEvaluator.evaluate(snapshot, "912 345 678", "codigo", 0)
        assertTrue(result.suppressedByLoopGuard)
        assertTrue(result.senderMatches)
    }
}