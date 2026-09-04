package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderRuleTest {
    @Test
    fun senderStorageRequiresAlignedModeFlags() {
        assertEquals(
            listOf(
                SenderRule("mb way"),
                SenderRule("^chave.*digital$", isRegex = true),
            ),
            SenderListStore.decode(
                rawValues = "mb way\n^chave.*digital$",
                rawRegexFlags = "0\n1",
            ),
        )
    }

    @Test
    fun unflaggedSenderRowsAreNotLoaded() {
        assertEquals(
            emptyList<SenderRule>(),
            SenderListStore.decode(rawValues = "mb way", rawRegexFlags = ""),
        )
    }

    @Test
    fun senderRegexMatchesCompleteNormalizedExternalValue() {
        val normalized = TextNormalizer.normalizeForMatching("Chave Móvel Digital")

        assertTrue(SenderMatcher.matchesRegex("^chave.*digital$", normalized))
        assertFalse(SenderMatcher.matchesRegex("chave", normalized))
        assertFalse(SenderMatcher.matchesRegex("^Chave Móvel Digital$", normalized))
        assertFalse(SenderMatcher.matchesRegex("[", normalized))
    }

    @Test
    fun matcherNormalizesOnlyTheIncomingSender() {
        assertTrue(
            SenderMatcher.matches(
                allowedSenders = listOf(SenderRule("chave movel digital")),
                sender = "Chave Móvel Digital",
                countryIso = "pt",
            ),
        )
        assertTrue(
            SenderMatcher.matches(
                allowedSenders = listOf(SenderRule("^chave.*digital$", isRegex = true)),
                sender = "Chave Móvel Digital",
                countryIso = "pt",
            ),
        )
    }

    @Test
    fun duplicateEquivalenceKeepsLiteralAndRegexModesDistinct() {
        assertFalse(
            SenderMatcher.equivalent(
                SenderRule("^sender$"),
                SenderRule("^sender$", isRegex = true),
                countryIso = "pt",
            ),
        )
        assertTrue(
            SenderMatcher.equivalent(
                SenderRule("^sender$", isRegex = true),
                SenderRule("^sender$", isRegex = true),
                countryIso = "pt",
            ),
        )
    }
}
