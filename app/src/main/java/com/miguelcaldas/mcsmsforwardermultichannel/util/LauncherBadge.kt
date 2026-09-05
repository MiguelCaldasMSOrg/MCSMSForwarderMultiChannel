package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import me.leolin.shortcutbadger.ShortcutBadger

internal fun launcherBadgeCount(count: Long): Int =
    count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

object LauncherBadge {
    private var unsupportedLogged = false

    @Synchronized
    fun setCount(context: Context, count: Long) {
        val app = context.applicationContext
        val badgeCount = launcherBadgeCount(count)
        val launcherPackage = defaultLauncherPackage(app)
        val applied = try {
            ShortcutBadger.applyCount(app, badgeCount)
        } catch (error: RuntimeException) {
            if (badgeCount > 0) {
                logUnsupportedOnce(app, "failed with ${error.javaClass.simpleName}")
            } else {
                unsupportedLogged = false
            }
            return
        }

        if (applied) {
            unsupportedLogged = false
        } else if (badgeCount > 0) {
            logUnsupportedOnce(app, "is not supported by $launcherPackage")
        } else {
            unsupportedLogged = false
        }
    }

    private fun logUnsupportedOnce(context: Context, detail: String) {
        if (!unsupportedLogged) {
            unsupportedLogged = true
            LogUtils.addToLog(context, "BADGE UNAVAILABLE \u2192 notification-free counter $detail")
        }
    }

    private fun defaultLauncherPackage(context: Context): String {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            context.packageManager
                .resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?: "the current launcher"
        } catch (_: RuntimeException) {
            "the current launcher"
        }
    }
}
