package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRetentionTest {
    @Test
    fun equalTimestampsKeepStableOrderAcrossRepeatedReads() {
        val entries = listOf(StoredLogEntry(1, "first"), StoredLogEntry(1, "second"), StoredLogEntry(1, "third"))
        assertEquals(entries, retainLogEntries(entries, 1))
        assertEquals(entries, retainLogEntries(retainLogEntries(entries, 1), 1))
    }

    @Test
    fun byteLimitKeepsNewestWholeEntriesWithoutTruncatingContent() {
        val entries = listOf(StoredLogEntry(1, "older raw message"), StoredLogEntry(2, "newer raw message"))
        val retained = retainLogEntries(entries, 2, entries.last().byteSize)
        assertEquals(listOf(entries.last()), retained)
    }

    @Test
    fun countsUtf8BytesRatherThanCharacters() {
        val entry = StoredLogEntry(1, "\u00e9\u00e9")
        assertEquals(7, entry.byteSize)
        assertTrue(retainLogEntries(listOf(entry), 1, 6).isEmpty())
    }

    @Test
    fun appliesAgeAndEntryLimitsTogether() {
        val now = TimeUnit.DAYS.toMillis(40)
        val entries = listOf(StoredLogEntry(0, "expired")) + (0 until 2100).map { StoredLogEntry(now + it, "entry $it") }
        val retained = retainLogEntries(entries, now)
        assertEquals(2000, retained.size)
        assertEquals("entry 100", retained.first().message)
        assertEquals("entry 2099", retained.last().message)
    }
}