package com.miguelcaldas.mcsmsforwardermultichannel.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FilterRuleMutationTest {
    @Test
    fun preservesConcurrentAdditionsWhileApplyingDraftEditsAndDeletions() {
        val merged = mergeDraftWithConcurrentAdditions(
            baseline = listOf("edited-original", "deleted"),
            draft = listOf("edited"),
            current = listOf("edited-original", "deleted", "remote"),
            equivalent = { left, right -> left == right },
        )

        assertEquals(listOf("edited", "remote"), merged)
    }

    @Test
    fun doesNotDuplicateConcurrentEntriesAlreadyPresentInDraft() {
        val merged = mergeDraftWithConcurrentAdditions(
            baseline = listOf("existing"),
            draft = listOf("existing", "remote"),
            current = listOf("existing", "remote", "remote"),
            equivalent = { left, right -> left == right },
        )

        assertEquals(listOf("existing", "remote"), merged)
    }

    @Test
    fun keepsLiteralAndRegexSenderModesDistinct() {
        val literal = SenderRule("bank")
        val regex = SenderRule("bank", isRegex = true)

        val merged = mergeDraftWithConcurrentAdditions(
            baseline = listOf(literal),
            draft = listOf(literal),
            current = listOf(literal, regex),
            equivalent = { left, right -> left == right },
        )

        assertEquals(listOf(literal, regex), merged)
    }

    @Test
    fun serializesRuleMutationTransactions() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondCompleted = CountDownLatch(1)
        val first = thread {
            FilterRuleMutationCoordinator.withLock {
                firstEntered.countDown()
                assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        val second = thread {
            FilterRuleMutationCoordinator.withLock {
                secondCompleted.countDown()
            }
        }

        assertFalse(secondCompleted.await(100, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()
        assertTrue(secondCompleted.await(2, TimeUnit.SECONDS))
        first.join()
        second.join()
    }
}
