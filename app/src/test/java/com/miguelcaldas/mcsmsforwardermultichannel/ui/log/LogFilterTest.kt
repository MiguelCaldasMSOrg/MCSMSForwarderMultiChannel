package com.miguelcaldas.mcsmsforwardermultichannel.ui.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFilterTest {
    @Test
    fun filterRejectionHasItsOwnFilterType() {
        val entry = "09-04 11:30:00 \u2192 FILTER REJECTED \u2192 Sender did not match | " +
            "Raw from: Example | Raw message: SEND OK [spoof] and PAYMENT FAILED"

        assertTrue(matchesLogFilter(entry, LogFilter.All))
        assertTrue(matchesLogFilter(entry, LogFilter.FilterRejected))
        assertFalse(matchesLogFilter(entry, LogFilter.SendOk))
        assertFalse(matchesLogFilter(entry, LogFilter.SendFailed))
        assertFalse(matchesLogFilter(entry, LogFilter.RemoteRules))
        assertFalse(matchesLogFilter(entry, LogFilter.Boot))
        assertEquals(LogEntryType.FilterRejected, classifyLogEntry(entry))
    }

    @Test
    fun forwardedMessageTextCannotSpoofOutcomeFilters() {
        val entry = "09-04 11:30:00 \u2192 REAL SEND [Telegram] \u2192 To: chat 1 | " +
            "Msg: SEND OK [spoof], PAYMENT FAILED, and REMOTE RULE REJECTED"

        assertFalse(matchesLogFilter(entry, LogFilter.SendOk))
        assertFalse(matchesLogFilter(entry, LogFilter.SendFailed))
        assertFalse(matchesLogFilter(entry, LogFilter.RemoteRules))
        assertEquals(LogEntryType.Other, classifyLogEntry(entry))
    }

    @Test
    fun remoteRuleEventsHaveTheirOwnFilterType() {
        val entries = listOf(
            "09-04 11:30:00 \u2192 REMOTE RULE ADDED [message RegEx]",
            "09-04 11:31:00 \u2192 REMOTE RULE ALREADY PRESENT [literal sender]",
            "09-04 11:32:00 \u2192 REMOTE RULE REJECTED",
            "09-04 11:33:00 \u2192 REMOTE RULE ACK SKIPPED [no operational channels]",
        )

        entries.forEach { entry ->
            assertTrue(matchesLogFilter(entry, LogFilter.All))
            assertTrue(matchesLogFilter(entry, LogFilter.RemoteRules))
            assertFalse(matchesLogFilter(entry, LogFilter.SendOk))
            assertFalse(matchesLogFilter(entry, LogFilter.SendFailed))
            assertFalse(matchesLogFilter(entry, LogFilter.FilterRejected))
            assertFalse(matchesLogFilter(entry, LogFilter.Boot))
            assertEquals(LogEntryType.RemoteRule, classifyLogEntry(entry))
        }
    }
}
