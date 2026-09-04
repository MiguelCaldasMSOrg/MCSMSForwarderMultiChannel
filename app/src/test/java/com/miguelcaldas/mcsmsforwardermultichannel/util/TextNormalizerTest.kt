package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextNormalizerTest {
    @Test
    fun lowercasesAndStripsCombiningDiacriticsFromMessageBody() {
        assertEquals(
            "chave movel digital: codigo 123",
            TextNormalizer.normalizeForMatching("Chave Móvel Digital: Código 123"),
        )
    }

    @Test
    fun regexSourceMustAlreadyMatchNormalizedForm() {
        val normalized = TextNormalizer.normalizeForMatching("Código Móvel")

        assertTrue(Regex("codigo movel").containsMatchIn(normalized))
        assertFalse(Regex("Código Móvel").containsMatchIn(normalized))
    }
}
