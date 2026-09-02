package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyTest {

    @Test
    fun overlappingWorkStartsConcurrently() {
        val executor = cachedDaemonExecutor("concurrency-test")
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(2)

        try {
            repeat(2) {
                assertTrue(
                    executor.executeWithDeadline(
                        timeoutMs = 1_000,
                        block = {
                            started.countDown()
                            release.await()
                            "done"
                        },
                        onResult = { result ->
                            assertEquals("done", result.getOrThrow())
                            completed.countDown()
                        },
                    )
                )
            }

            assertTrue(started.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(completed.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun timeoutCompletesExactlyOnceAndIgnoresLateResult() {
        val executor = cachedDaemonExecutor("timeout-test")
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val callbackCount = AtomicInteger(0)

        try {
            assertTrue(
                executor.executeWithDeadline(
                    timeoutMs = 50,
                    block = {
                        release.await()
                        "late"
                    },
                    onResult = { result ->
                        callbackCount.incrementAndGet()
                        assertTrue(result.exceptionOrNull() is TimeoutException)
                        completed.countDown()
                    },
                )
            )

            assertTrue(completed.await(1, TimeUnit.SECONDS))
            release.countDown()
            Thread.sleep(100)
            assertEquals(1, callbackCount.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun workerExceptionIsUnwrapped() {
        val executor = cachedDaemonExecutor("exception-test")
        val completed = CountDownLatch(1)

        try {
            assertTrue(
                executor.executeWithDeadline(
                    timeoutMs = 1_000,
                    block = { throw IllegalStateException("expected") },
                    onResult = { result ->
                        val error = result.exceptionOrNull()
                        assertTrue(error is IllegalStateException)
                        assertEquals("expected", error?.message)
                        completed.countDown()
                    },
                )
            )

            assertTrue(completed.await(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun rejectedExecutionReportsFailureOnce() {
        val executor = cachedDaemonExecutor("rejection-test")
        val callbackCount = AtomicInteger(0)
        executor.shutdown()

        val started = executor.executeWithDeadline(
            timeoutMs = 1_000,
            block = { "never" },
            onResult = { result ->
                callbackCount.incrementAndGet()
                assertTrue(result.exceptionOrNull() is RejectedExecutionException)
            },
        )

        assertFalse(started)
        assertEquals(1, callbackCount.get())
    }
}
