package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class InboundFilterDecisionTest {
    @Test
    fun forwardsOnlyWhenBothComponentsMatch() {
        assertEquals(
            InboundFilterDecision.FORWARD,
            decideInboundFilter(senderMatches = true, messageRuleMatches = true),
        )
    }

    @Test
    fun identifiesSenderOnlyRejection() {
        assertEquals(
            InboundFilterDecision.SENDER_REJECTED,
            decideInboundFilter(senderMatches = false, messageRuleMatches = true),
        )
    }

    @Test
    fun identifiesMessageRuleOnlyRejection() {
        assertEquals(
            InboundFilterDecision.MESSAGE_RULE_REJECTED,
            decideInboundFilter(senderMatches = true, messageRuleMatches = false),
        )
    }

    @Test
    fun ignoresWhenNeitherComponentMatches() {
        assertEquals(
            InboundFilterDecision.IGNORE,
            decideInboundFilter(senderMatches = false, messageRuleMatches = false),
        )
    }
}
