package com.miguelcaldas.mcsmsforwardermultichannel.devicetests

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseUiTest {
    private val applicationId = "com.miguelcaldas.mcsmsforwardermultichannel"
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    @After
    fun resetTestApp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "UI tests require a disposable emulator." }
        check(device.executeShellCommand("pm clear $applicationId").trim() == "Success")
    }

    private fun text(value: String): UiObject2 {
        device.waitForIdle()
        return requireNotNull(device.wait(Until.findObject(By.text(value)), 10_000)) { "UI text not found: $value" }
    }

    private fun reveal(value: String): UiObject2 {
        repeat(12) {
            val found = device.wait(Until.findObject(By.text(value)), 750)
            if (found != null) {
                return text(value)
            }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
            device.waitForIdle()
        }
        return text(value)
    }

    private fun launch() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = requireNotNull(context.packageManager.getLaunchIntentForPackage(applicationId))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        text("Status")
    }

    private fun openFilters() {
        text("Channels").click()
        reveal("Senders, rules & template").click()
        text("Filters")
    }

    private fun senderField(): UiObject2 {
        if (!device.hasObject(By.clazz("android.widget.EditText"))) {
            reveal("Add sender").click()
        }
        return requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000))
    }

    @Test
    fun channelSavePreservesRotatedSecretDraftAndUntouchedToken() {
        launch()
        text("Channels").click()
        text("Telegram").click()
        text("Bot token")
        val token = "synthetic-rotation-token"
        val draft = device.findObjects(By.clazz("android.widget.EditText"))
        assertEquals(2, draft.size)
        draft[0].text = token
        draft[1].text = "synthetic-chat"
        try {
            device.setOrientationLeft()
            device.waitForIdle()
            device.setOrientationNatural()
            device.waitForIdle()
            reveal("Save").click()
            text("Channels")
            text("Telegram").click()
            text("Bot token")
            val saved = device.findObjects(By.clazz("android.widget.EditText"))
            assertEquals(2, saved.size)
            assertEquals("\u2022".repeat(token.length), saved[0].text)
            assertEquals("synthetic-chat", saved[1].text)
            saved[1].text = "edited-chat"
            reveal("Save").click()
            text("Channels")
            device.executeShellCommand("am force-stop $applicationId")
            launch()
            text("Channels").click()
            text("Telegram").click()
            text("Bot token")
            val restored = device.findObjects(By.clazz("android.widget.EditText"))
            assertEquals(2, restored.size)
            assertEquals("\u2022".repeat(token.length), restored[0].text)
            assertEquals("edited-chat", restored[1].text)
        } finally {
            device.unfreezeRotation()
        }
    }

    @Test
    fun phoneRuleDryRunWorksInOptimizedApk() {
        launch()
        openFilters()
        senderField().text = "+351912345678"
        reveal("Test")
        val sampleFields = device.findObjects(By.clazz("android.widget.EditText")).takeLast(2)
        assertEquals(2, sampleFields.size)
        sampleFields[0].text = "+351 912 345 678"
        sampleFields[1].text = "compatibility test message"
        reveal("Test").click()
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
        assertTrue(device.wait(Until.hasObject(By.textContains("Sender allowed: yes")), 10_000))
        assertTrue(device.hasObject(By.textContains("Operational channels: none")))
    }

    @Test
    fun navigationAndImportRoutesRemainAvailable() {
        launch()
        text("Channels").click()
        reveal("Senders, rules & template")
        requireNotNull(device.wait(Until.findObject(By.desc("More options")), 10_000)).click()
        text("Choose file or cloud drive")
        text("Scan configuration QR code")
        text("Paste encrypted import code").click()
        assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000))
        device.pressBack()
    }

    @Test
    fun filterDraftDiscardAndSaveSurviveProcessRestart() {
        launch()
        openFilters()
        senderField().text = "compatibility sender"
        text("Unsaved changes")
        device.pressBack()
        if (!device.wait(Until.hasObject(By.text("Discard unsaved changes?")), 1_000)) {
            device.pressBack()
        }
        text("Discard unsaved changes?")
        text("Discard").click()
        text("Channels")
        reveal("Senders, rules & template").click()
        text("Filters")
        assertTrue(senderField().text.isNullOrEmpty())
        senderField().text = "compatibility sender"
        device.pressBack()
        if (device.wait(Until.hasObject(By.text("Discard unsaved changes?")), 500)) {
            text("Keep editing").click()
        }
        reveal("Save").click()
        assertTrue(device.wait(Until.gone(By.text("Unsaved changes")), 10_000))
        device.executeShellCommand("am force-stop $applicationId")
        launch()
        openFilters()
        text("compatibility sender")
    }
}