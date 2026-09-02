package com.miguelcaldas.mcsmsforwardermultichannel.ui.navigation

import androidx.annotation.DrawableRes
import com.miguelcaldas.mcsmsforwardermultichannel.R

/** Top-level bottom-navigation destinations. */
enum class TopLevelDestination(val route: String, val label: String, @param:DrawableRes val icon: Int) {
    Status("status", "Status", R.drawable.ic_home_24),
    Channels("channels", "Channels", R.drawable.ic_tune_24),
    Activity("activity", "Activity", R.drawable.ic_history_24),
}
