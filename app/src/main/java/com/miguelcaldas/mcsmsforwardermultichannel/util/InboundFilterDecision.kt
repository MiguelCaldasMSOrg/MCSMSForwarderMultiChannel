package com.miguelcaldas.mcsmsforwardermultichannel.util

enum class InboundFilterDecision {
    FORWARD,
    SENDER_REJECTED,
    MESSAGE_RULE_REJECTED,
    IGNORE,
}

fun decideInboundFilter(senderMatches: Boolean, messageRuleMatches: Boolean): InboundFilterDecision {
    return when {
        senderMatches && messageRuleMatches -> InboundFilterDecision.FORWARD
        messageRuleMatches -> InboundFilterDecision.SENDER_REJECTED
        senderMatches -> InboundFilterDecision.MESSAGE_RULE_REJECTED
        else -> InboundFilterDecision.IGNORE
    }
}
