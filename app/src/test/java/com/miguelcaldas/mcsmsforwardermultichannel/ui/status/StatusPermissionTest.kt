package com.miguelcaldas.mcsmsforwardermultichannel.ui.status

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPermissionTest {
    @Test
    fun eachGrantActionMapsToExactlyOnePermission() {
        assertEquals(Manifest.permission.RECEIVE_SMS, HealthAction.GRANT_RECEIVE_SMS.runtimePermission(33))
        assertEquals(Manifest.permission.SEND_SMS, HealthAction.GRANT_SEND_SMS.runtimePermission(33))
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, HealthAction.GRANT_NOTIFICATIONS.runtimePermission(33))
        assertNull(HealthAction.OPEN_NOTIFICATION_SETTINGS.runtimePermission(33))
        assertNull(HealthAction.BATTERY_SETTINGS.runtimePermission(33))
        assertNull(HealthAction.OPEN_CHANNELS.runtimePermission(33))
        assertNull(HealthAction.OPEN_FILTERS.runtimePermission(33))
    }

    @Test
    fun olderAndroidUsesNotificationSettingsWithoutRequestingAnUnavailablePermission() {
        for (sdkInt in 30..32) {
            assertNull(HealthAction.GRANT_NOTIFICATIONS.runtimePermission(sdkInt))
            assertNull(HealthAction.OPEN_NOTIFICATION_SETTINGS.runtimePermission(sdkInt))
            assertEquals(Manifest.permission.RECEIVE_SMS, HealthAction.GRANT_RECEIVE_SMS.runtimePermission(sdkInt))
            assertEquals(Manifest.permission.SEND_SMS, HealthAction.GRANT_SEND_SMS.runtimePermission(sdkInt))
            val items = permissionHealthItems(receiveSmsGranted = true, notificationsGranted = false, smsEnabled = false, sendSmsGranted = false, sdkInt = sdkInt)
            assertEquals(listOf(HealthItem("Allow icon badge count", "Settings", HealthAction.OPEN_NOTIFICATION_SETTINGS)), items)
            assertTrue(permissionHealthItems(receiveSmsGranted = true, notificationsGranted = true, smsEnabled = false, sendSmsGranted = false, sdkInt = sdkInt).isEmpty())
        }
    }

    @Test
    fun sendSmsPermissionIsRequiredOnlyForEnabledSmsChannel() {
        val disabled = permissionHealthItems(
            receiveSmsGranted = true,
            notificationsGranted = true,
            smsEnabled = false,
            sendSmsGranted = false,
            sdkInt = 33,
        )
        val enabled = permissionHealthItems(
            receiveSmsGranted = true,
            notificationsGranted = true,
            smsEnabled = true,
            sendSmsGranted = false,
            sdkInt = 33,
        )

        assertFalse(disabled.any { it.action == HealthAction.GRANT_SEND_SMS })
        assertTrue(enabled.any { it.action == HealthAction.GRANT_SEND_SMS })
    }

    @Test
    fun permissionCardsRemainIndependent() {
        val items = permissionHealthItems(
            receiveSmsGranted = false,
            notificationsGranted = false,
            smsEnabled = true,
            sendSmsGranted = false,
            sdkInt = 33,
        )

        assertEquals(
            listOf(
                HealthAction.GRANT_RECEIVE_SMS,
                HealthAction.GRANT_NOTIFICATIONS,
                HealthAction.GRANT_SEND_SMS,
            ),
            items.map(HealthItem::action),
        )
    }

    @Test
    fun denialMessageNamesSmsSendingPermission() {
        assertEquals(
            "SMS sending permission was not granted.",
            permissionDeniedMessage(Manifest.permission.SEND_SMS),
        )
    }

    @Test
    fun denialMessageExplainsBadgeCompatibility() {
        assertEquals(
            "Notification permission was not granted; some launcher badges may not work.",
            permissionDeniedMessage(Manifest.permission.POST_NOTIFICATIONS),
        )
    }

    @Test
    fun remoteSmsKeyBlocksReadinessOnlyWhileEnabled() {
        assertNull(remoteSmsHealthItem(enabled = false, hasKey = false))
        assertNull(remoteSmsHealthItem(enabled = true, hasKey = true))
        assertEquals(
            HealthItem(
                "Add remote SMS command key",
                "Filters",
                HealthAction.OPEN_FILTERS,
            ),
            remoteSmsHealthItem(enabled = true, hasKey = false),
        )
    }
}
