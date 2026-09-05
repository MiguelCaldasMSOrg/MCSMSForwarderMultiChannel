package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.miguelcaldas.mcsmsforwardermultichannel.util.ForwardStatsStore
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils

// Wakes the package after a reboot so the SMS receiver is "warm" before the first
// SMS arrives, then reapplies any unseen-forward badge that the launcher discarded.
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                LogUtils.addToLog(context, "BOOT → ready to forward")
                ForwardStatsStore.restoreLauncherBadge(context)
            }
        }
    }
}
