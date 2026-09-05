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
        assertEquals(Manifest.permission.RECEIVE_SMS, HealthAction.GRANT_RECEIVE_SMS.runtimePermission())
        assertEquals(Manifest.permission.SEND_SMS, HealthAction.GRANT_SEND_SMS.runtimePermission())
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, HealthAction.GRANT_NOTIFICATIONS.runtimePermission())
        assertNull(HealthAction.BATTERY_SETTINGS.runtimePermission())
        assertNull(HealthAction.OPEN_CHANNELS.runtimePermission())
        assertNull(HealthAction.OPEN_FILTERS.runtimePermission())
    }

    @Test
    fun sendSmsPermissionIsRequiredOnlyForEnabledSmsChannel() {
        val disabled = permissionHealthItems(
            receiveSmsGranted = true,
            notificationsGranted = true,
            smsEnabled = false,
            sendSmsGranted = false,
        )
        val enabled = permissionHealthItems(
            receiveSmsGranted = true,
            notificationsGranted = true,
            smsEnabled = true,
            sendSmsGranted = false,
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
}
