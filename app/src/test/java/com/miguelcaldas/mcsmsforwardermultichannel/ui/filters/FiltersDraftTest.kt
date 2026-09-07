package com.miguelcaldas.mcsmsforwardermultichannel.ui.filters

import com.miguelcaldas.mcsmsforwardermultichannel.util.SenderRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersDraftTest {
    private val senders = listOf(SenderRule("bank"))
    private val rules = listOf("^otp$")

    @Test
    fun unchangedDraftIsClean() {
        assertFalse(changed())
    }

    @Test
    fun everyPersistedDraftAreaCanMakeTheScreenDirty() {
        assertTrue(changed(senders = senders + SenderRule("alerts")))
        assertTrue(changed(rules = rules + "^code$"))
        assertTrue(changed(template = "[%t] %m"))
        assertTrue(changed(remoteSmsEnabled = true))
        assertTrue(changed(remoteSmsKeyChanged = true))
    }

    @Test
    fun revertingValuesClearsTheDirtyState() {
        assertFalse(
            changed(
                senders = senders.toList(),
                rules = rules.toList(),
                template = "",
                remoteSmsEnabled = false,
                remoteSmsKeyChanged = false,
            ),
        )
    }

    private fun changed(
        senders: List<SenderRule> = this.senders,
        rules: List<String> = this.rules,
        template: String = "",
        remoteSmsEnabled: Boolean = false,
        remoteSmsKeyChanged: Boolean = false,
    ): Boolean =
        hasUnsavedFilterChanges(
            senders = senders,
            senderBaseline = this.senders,
            rules = rules,
            ruleBaseline = this.rules,
            template = template,
            templateBaseline = "",
            remoteSmsEnabled = remoteSmsEnabled,
            remoteSmsEnabledBaseline = false,
            remoteSmsKeyChanged = remoteSmsKeyChanged,
        )
}
