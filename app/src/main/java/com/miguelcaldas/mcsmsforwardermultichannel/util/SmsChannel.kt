package com.miguelcaldas.mcsmsforwardermultichannel.util

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Re-sends forwarded SMS bodies through the device's own modem via SmsManager.
 *
 * Unlike the WhatsApp / Telegram channels (which complete synchronously over
 * HTTP), SmsManager dispatches asynchronously: the modem reports per-part
 * delivery results through a broadcast that arrives AFTER
 * sendMultipartTextMessage returns. The caller's onComplete therefore reflects
 * whether the message was successfully *handed to the modem*, mirroring how the
 * HTTP channels report 2xx — the eventual modem result (no service, radio off,
 * …) is surfaced in the Activity log by the internal result receiver.
 *
 * Forward stats are owned by SmsReceiver (a single increment per matched SMS,
 * regardless of channel), so this channel only logs; it never records stats.
 */
object SmsChannel {

    private const val ACTION = "com.miguelcaldas.mcsmsforwardermultichannel.SMS_SENT_RESULT"
    private const val EXTRA_DESTINATION = "destination"
    private const val EXTRA_PART_INDEX = "partIndex"
    private const val EXTRA_PART_COUNT = "partCount"

    private val sequence = AtomicInteger(0)

    @Volatile
    private var registered = false

    fun send(context: Context, config: SmsConfig, body: String, onComplete: (Boolean) -> Unit = {}): Boolean {
        val app = context.applicationContext
        if (!config.hasCredentials) {
            LogUtils.addToLog(app, "SEND FAILED [SMS] → missing config")
            onComplete(false)
            return false
        }
        var dispatched = false
        try {
            val smsManager = app.getSystemService(SmsManager::class.java) ?: throw IllegalStateException("SmsManager unavailable")
            val parts = smsManager.divideMessage(body)
            val sentIntents = buildSentIntents(app, parts.size, config.destination)
            smsManager.sendMultipartTextMessage(config.destination, null, parts, sentIntents, null)
            dispatched = true
        } catch (e: Exception) {
            // SecurityException (missing SEND_SMS), IllegalArgumentException (empty body /
            // bad destination), or any modem-dispatch failure surfaced synchronously.
            LogUtils.addToLog(app, "SEND FAILED [SMS] → ${config.destination} (dispatch) ${e.message.orEmpty()}".trimEnd())
        } finally {
            onComplete(dispatched)
        }
        return dispatched
    }

    private fun buildSentIntents(app: Context, partCount: Int, destination: String): ArrayList<PendingIntent> {
        ensureRegistered(app)
        val list = ArrayList<PendingIntent>(partCount)
        for (i in 0 until partCount) {
            val intent = Intent(ACTION).setPackage(app.packageName).putExtra(EXTRA_DESTINATION, destination).putExtra(EXTRA_PART_INDEX, i).putExtra(EXTRA_PART_COUNT, partCount)
            // Unique requestCode per PendingIntent so the system keeps them
            // distinct rather than collapsing to one shared instance.
            val requestCode = sequence.incrementAndGet()
            list.add(PendingIntent.getBroadcast(app, requestCode, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
        }
        return list
    }

    @Synchronized
    private fun ensureRegistered(app: Context) {
        if (registered) {
            return
        }
        app.registerReceiver(ResultReceiver, IntentFilter(ACTION), Context.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private object ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION) {
                return
            }
            val destination = intent.getStringExtra(EXTRA_DESTINATION) ?: "(unknown)"
            val idx = intent.getIntExtra(EXTRA_PART_INDEX, -1) + 1
            val total = intent.getIntExtra(EXTRA_PART_COUNT, -1)
            val tag = if (total > 1) " part $idx/$total" else ""
            val msg = when (resultCode) {
                Activity.RESULT_OK -> "SEND OK [SMS]$tag → $destination"
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "SEND FAILED [SMS]$tag → $destination (generic failure)"
                SmsManager.RESULT_ERROR_NO_SERVICE -> "SEND FAILED [SMS]$tag → $destination (no service)"
                SmsManager.RESULT_ERROR_NULL_PDU -> "SEND FAILED [SMS]$tag → $destination (null PDU)"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "SEND FAILED [SMS]$tag → $destination (radio off)"
                else -> "SEND FAILED [SMS]$tag → $destination (code $resultCode)"
            }
            LogUtils.addToLog(context, msg)
        }
    }
}
