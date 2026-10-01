package com.miguelcaldas.mcsmsforwardermultichannel.compatibility

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.miguelcaldas.mcsmsforwardermultichannel.R
import com.miguelcaldas.mcsmsforwardermultichannel.util.PhoneNumberCompat
import com.miguelcaldas.mcsmsforwardermultichannel.util.SmsChannel
import com.miguelcaldas.mcsmsforwardermultichannel.util.cachedDaemonExecutor
import com.miguelcaldas.mcsmsforwardermultichannel.util.executeWithDeadline
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

abstract class DeviceTestBase {
    protected val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    protected val prefs get() = context.getSharedPreferences("mc_sms_fwd_wa", Context.MODE_PRIVATE)

    @Before
    @After
    @SuppressLint("UseKtx")
    fun resetTestState() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Device tests require a disposable emulator, never a physical phone." }
        for (name in listOf("mc_sms_fwd_wa", "mc_sms_fwd_secure", "mc_sms_fwd_badge", "mc_sms_fwd_log")) {
            check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
        }
        check(prefs.edit().putBoolean("master_enabled", false).putBoolean("waEnabled", false).commit())
    }

    protected fun fixture(name: String): String = InstrumentationRegistry.getInstrumentation().context.assets.open(name).bufferedReader().use {
        it.readText()
    }
}

@RunWith(AndroidJUnit4::class)
class PlatformCompatibilityTest: DeviceTestBase() {
    @Test
    fun phoneFallbackMatchesThePlatformPolicy() {
        val cases = listOf(
            Triple("+351 912 345 678", "912345678", "pt"),
            Triple("00351 912345678", "+351912345678", "pt"),
            Triple("+1 202 555 0123", "(202) 555-0123", "us"),
            Triple("+1 202 555 0123", "5550123", "us"),
            Triple("+44 20 7946 0123", "020 7946 0123", "gb"),
            Triple("+39 02 12345678", "02 12345678", "it"),
            Triple("12345", "123 45", "pt"),
            Triple("+1 202 555 0123 ext 1", "+1 202 555 0123", "us"),
            Triple("+1 202 555 0123 ext 1", "+1 202 555 0123 ext 2", "us"),
        )
        for ((first, second, country) in cases) {
            val legacy = PhoneNumberCompat.areSameOnAndroid11(first, second, country)
            if (Build.VERSION.SDK_INT >= 31) {
                assertEquals(PhoneNumberUtils.areSamePhoneNumber(first, second, country), legacy)
            }
            assertEquals(legacy, PhoneNumberCompat.areSame(first, second, country))
        }
        assertTrue(PhoneNumberCompat.areSame("+351912345678", "912345678", "pt"))
        assertFalse(PhoneNumberCompat.areSame("+12025550123", "5550123", "us"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun smsManagerRetainsTheDefaultSubscription() {
        val manager = SmsChannel.defaultSmsManager(context)
        assertNotNull(manager)
        assertEquals(SmsManager.getDefault().subscriptionId, manager!!.subscriptionId)
    }

    @Test
    fun privateReceiverAcceptsAnOwnPackagePendingIntent() {
        val action = "${context.packageName}.COMPATIBILITY_CALLBACK"
        val received = CountDownLatch(1)
        val receiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action) {
                    received.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val pending = PendingIntent.getBroadcast(context, 90730, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        try {
            pending.send()
            assertTrue(received.await(5, TimeUnit.SECONDS))
        } finally {
            pending.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun deadlineCompletesOnceOnTheAndroidRuntime() {
        val executor = cachedDaemonExecutor("device-deadline-test")
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callback = CountDownLatch(1)
        val count = AtomicInteger()
        try {
            assertTrue(executor.executeWithDeadline(timeoutMs = 50, block = {
                release.await()
                finished.countDown()
                "late"
            }, onResult = { result ->
                assertTrue(result.exceptionOrNull() is TimeoutException)
                count.incrementAndGet()
                callback.countDown()
            }))
            assertTrue(callback.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, count.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun bothBackupFormatsExcludeSecretsAndBadgeState() {
        for (resource in listOf(R.xml.backup_rules, R.xml.data_extraction_rules)) {
            val excluded = mutableSetOf<String>()
            context.resources.getXml(resource).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref") {
                        excluded.add(parser.getAttributeValue(null, "path"))
                    }
                    parser.next()
                }
            }
            assertEquals(setOf("mc_sms_fwd_secure.xml", "mc_sms_fwd_badge.xml"), excluded)
        }
    }
}