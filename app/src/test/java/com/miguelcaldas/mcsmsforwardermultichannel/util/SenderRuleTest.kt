package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderRuleTest {
    @Test
    fun android11PhoneMatchingPreservesNationalAndInternationalEquivalence() {
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+351 912 345 678", "912345678", "pt"))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("00351 912345678", "+351912345678", "PT"))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+1 202 555 0123", "(202) 555-0123", "us"))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+44 20 7946 0123", "020 7946 0123", "gb"))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+39 02 12345678", "02 12345678", "it"))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("12345", "123 45", "pt"))
    }

    @Test
    fun android11PhoneMatchingRejectsSuffixCountryAndExtensionConflicts() {
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("+1 202 555 0123", "5550123", "us"))
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("+1 202 555 0123", "+44 202 555 0123", "us"))
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("+1 202 555 0123 ext 1", "+1 202 555 0123 ext 2", "us"))
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("bank", "bank", "pt"))
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("", "", "pt"))
        assertFalse(PhoneNumberCompat.areSameOnAndroid11("912345678", "912345678", ""))
    }

    @Test
    fun android11PhoneMatchingAllowsInternationalNumbersWithoutARegion() {
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+351912345678", "+351 912 345 678", ""))
        assertTrue(PhoneNumberCompat.areSameOnAndroid11("+1 202 555 0123 ext 1", "+1 202 555 0123", "us"))
    }

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
