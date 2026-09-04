package com.miguelcaldas.mcsmsforwardermultichannel.util

import com.miguelcaldas.mcsmsforwardermultichannel.BuildConfig
import com.miguelcaldas.mcsmsforwardermultichannel.GeneratedBuildMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildMetadataTest {
    @Test
    fun formatsBuildTimestampUnambiguouslyInUtc() {
        assertEquals("2026-09-04 16:32:53 UTC", BuildMetadata.formatUtc(1_788_539_573_000L))
    }

    @Test
    fun generatedMetadataIsValid() {
        assertTrue(GeneratedBuildMetadata.BUILD_TIME_EPOCH_MILLIS > 0L)
        assertTrue(GeneratedBuildMetadata.SOURCE_REVISION.matches(Regex("[0-9A-Za-z._-]{1,32}")))
        assertEquals("MC SMS Forwarder v${BuildConfig.VERSION_NAME}", BuildMetadata.title)
        assertTrue(BuildMetadata.details.endsWith("\u00b7 ${GeneratedBuildMetadata.SOURCE_REVISION}"))
    }
}
