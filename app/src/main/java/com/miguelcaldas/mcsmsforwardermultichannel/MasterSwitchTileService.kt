package com.miguelcaldas.mcsmsforwardermultichannel

import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.miguelcaldas.mcsmsforwardermultichannel.util.LogUtils
import com.miguelcaldas.mcsmsforwardermultichannel.util.MasterSwitchStore

class MasterSwitchTileService: TileService() {

    override fun onStartListening() {
        super.onStartListening()
        renderTile()
    }

    override fun onClick() {
        super.onClick()
        val prefs = applicationContext.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)
        val newValue = !MasterSwitchStore.load(prefs)
        MasterSwitchStore.save(prefs, newValue)
        LogUtils.addToLog(applicationContext, "TILE → forwarding ${if (newValue) "ENABLED" else "DISABLED"}")
        renderTile()
    }

    private fun renderTile() {
        val tile = qsTile ?: return
        val enabled = MasterSwitchStore.load(
            applicationContext.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE),
        )
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.contentDescription = getString(if (enabled) R.string.tile_state_on else R.string.tile_state_off)
        tile.subtitle = getString(if (enabled) R.string.tile_state_on else R.string.tile_state_off)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_sms_forwarder)
        tile.updateTile()
    }
}
