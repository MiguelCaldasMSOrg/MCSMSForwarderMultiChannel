package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.miguelcaldas.mcsmsforwardermultichannel.ui.AppRoot
import com.miguelcaldas.mcsmsforwardermultichannel.ui.theme.MCSmsForwarderTheme
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore

internal fun shouldAcknowledgeForwardBadge(
    action: String?,
    categories: Set<String>?,
    flags: Int,
    restoredState: Boolean,
): Boolean =
    !restoredState &&
        flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0 &&
        action == Intent.ACTION_MAIN &&
        categories?.contains(Intent.CATEGORY_LAUNCHER) == true

class MainActivity: ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        acknowledgeForwardBadge(intent, restoredState = savedInstanceState != null)
        setContent {
            MCSmsForwarderTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acknowledgeForwardBadge(intent, restoredState = false)
    }

    private fun acknowledgeForwardBadge(intent: Intent?, restoredState: Boolean) {
        if (
            shouldAcknowledgeForwardBadge(
                action = intent?.action,
                categories = intent?.categories,
                flags = intent?.flags ?: 0,
                restoredState = restoredState,
            )
        ) {
            ForwardStatsStore.acknowledgeLauncherOpen(this)
        }
    }
}
