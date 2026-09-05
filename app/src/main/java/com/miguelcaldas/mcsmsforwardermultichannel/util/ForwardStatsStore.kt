package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import androidx.core.content.edit

object ForwardStatsStore {
    private const val PREFS = "mc_sms_fwd_wa"
    private const val BADGE_PREFS = "mc_sms_fwd_badge"
    private const val KEY_COUNT = "fwd_count"
    private const val KEY_FIRST = "fwd_first_ts"
    private const val KEY_LAST = "fwd_last_ts"
    private const val KEY_UNSEEN = "fwd_unseen_count"

    data class Stats(val count: Long, val firstMillis: Long, val lastMillis: Long) {
        val hasAny: Boolean get() = count > 0L
    }

    fun recordForward(context: Context, timestamp: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val badgePrefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val count = prefs.getLong(KEY_COUNT, 0L)
            val first = prefs.getLong(KEY_FIRST, 0L)
            val unseen = nextUnseenForwardCount(badgePrefs.getLong(KEY_UNSEEN, 0L))
            prefs.edit {
                putLong(KEY_COUNT, count + 1)
                if (first == 0L) {
                    putLong(KEY_FIRST, timestamp)
                }
                putLong(KEY_LAST, timestamp)
            }
            badgePrefs.edit {
                putLong(KEY_UNSEEN, unseen)
            }
            LauncherBadge.setCount(app, unseen)
        }
    }

    fun load(context: Context): Stats {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Stats(
            count = prefs.getLong(KEY_COUNT, 0L),
            firstMillis = prefs.getLong(KEY_FIRST, 0L),
            lastMillis = prefs.getLong(KEY_LAST, 0L),
        )
    }

    fun reset(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val badgePrefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            prefs.edit {
                remove(KEY_COUNT)
                remove(KEY_FIRST)
                remove(KEY_LAST)
            }
            badgePrefs.edit {
                remove(KEY_UNSEEN)
            }
            LauncherBadge.setCount(app, 0L)
        }
    }

    fun acknowledgeLauncherOpen(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            prefs.edit {
                remove(KEY_UNSEEN)
            }
            LauncherBadge.setCount(app, 0L)
        }
    }

    fun restoreLauncherBadge(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            LauncherBadge.setCount(app, prefs.getLong(KEY_UNSEEN, 0L))
        }
    }
}

internal fun nextUnseenForwardCount(current: Long): Long =
    if (current >= Long.MAX_VALUE) Long.MAX_VALUE else current.coerceAtLeast(0L) + 1L
