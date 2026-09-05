package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {
    @Test
    fun launcherIntentAcknowledgesBadge() {
        assertTrue(
            shouldAcknowledgeForwardBadge(
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LAUNCHER),
                flags = 0,
                restoredState = false,
            ),
        )
    }

    @Test
    fun restoredActivityDoesNotAcknowledgeBadgeAgain() {
        assertFalse(
            shouldAcknowledgeForwardBadge(
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LAUNCHER),
                flags = 0,
                restoredState = true,
            ),
        )
    }

    @Test
    fun nonLauncherEntryDoesNotAcknowledgeBadge() {
        assertFalse(
            shouldAcknowledgeForwardBadge(
                action = Intent.ACTION_VIEW,
                categories = emptySet(),
                flags = 0,
                restoredState = false,
            ),
        )
    }

    @Test
    fun recentsRelaunchDoesNotAcknowledgeBadge() {
        assertFalse(
            shouldAcknowledgeForwardBadge(
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LAUNCHER),
                flags = Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY,
                restoredState = false,
            ),
        )
    }
}
