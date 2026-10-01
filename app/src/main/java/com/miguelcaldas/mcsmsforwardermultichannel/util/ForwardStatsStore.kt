package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ForwardStatsStore {
    private const val PREFS = "mc_sms_fwd_wa"
    private const val BADGE_PREFS = "mc_sms_fwd_badge"
    private const val KEY_COUNT = "fwd_count"
    private const val KEY_FIRST = "fwd_first_ts"
    private const val KEY_LAST = "fwd_last_ts"
    private const val KEY_UNSEEN = "fwd_unseen_count"
    private val badgeExecutor = boundedDaemonExecutor("badge-refresh", 1, 1)
    private val badgeQueued = AtomicBoolean()
    private val badgeRevision = AtomicLong()

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
            requestBadgeRefresh(app)
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
            requestBadgeRefresh(app)
        }
    }

    fun acknowledgeLauncherOpen(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            prefs.edit {
                remove(KEY_UNSEEN)
            }
            requestBadgeRefresh(app)
        }
    }

    fun restoreLauncherBadge(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            requestBadgeRefresh(app)
        }
    }

    private fun requestBadgeRefresh(context: Context) {
        badgeRevision.incrementAndGet()
        scheduleBadgeRefresh(context)
    }

    private fun scheduleBadgeRefresh(context: Context) {
        if (!badgeQueued.compareAndSet(false, true)) {
            return
        }
        badgeExecutor.execute {
            var appliedRevision = -1L
            try {
                do {
                    appliedRevision = badgeRevision.get()
                    val count = context.getSharedPreferences(BADGE_PREFS, Context.MODE_PRIVATE).getLong(KEY_UNSEEN, 0L)
                    LauncherBadge.setCount(context, count)
                } while (appliedRevision != badgeRevision.get())
            } finally {
                badgeQueued.set(false)
                if (appliedRevision != badgeRevision.get()) {
                    scheduleBadgeRefresh(context)
                }
            }
        }
    }
}

internal fun nextUnseenForwardCount(current: Long): Long =
    if (current >= Long.MAX_VALUE) Long.MAX_VALUE else current.coerceAtLeast(0L) + 1L
