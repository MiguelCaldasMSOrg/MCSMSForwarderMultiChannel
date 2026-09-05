package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherBadgeTest {
    @Test
    fun unseenCountIncrementsOncePerRecordedForward() {
        assertEquals(1L, nextUnseenForwardCount(0L))
        assertEquals(2L, nextUnseenForwardCount(1L))
    }

    @Test
    fun unseenCountSaturatesWithoutOverflowing() {
        assertEquals(Long.MAX_VALUE, nextUnseenForwardCount(Long.MAX_VALUE))
    }

    @Test
    fun launcherCountFitsTheVendorApisIntegerRange() {
        assertEquals(0, launcherBadgeCount(-1L))
        assertEquals(42, launcherBadgeCount(42L))
        assertEquals(Int.MAX_VALUE, launcherBadgeCount(Long.MAX_VALUE))
    }

}
