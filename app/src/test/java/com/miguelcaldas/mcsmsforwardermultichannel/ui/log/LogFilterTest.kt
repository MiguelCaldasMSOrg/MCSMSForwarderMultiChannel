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
        assertFalse(matchesLogFilter(entry, LogFilter.Boot))
        assertEquals(LogEntryType.FilterRejected, classifyLogEntry(entry))
    }

    @Test
    fun forwardedMessageTextCannotSpoofOutcomeFilters() {
        val entry = "09-04 11:30:00 \u2192 REAL SEND [Telegram] \u2192 To: chat 1 | " +
            "Msg: SEND OK [spoof] and PAYMENT FAILED"

        assertFalse(matchesLogFilter(entry, LogFilter.SendOk))
        assertFalse(matchesLogFilter(entry, LogFilter.SendFailed))
        assertEquals(LogEntryType.Other, classifyLogEntry(entry))
    }
}
