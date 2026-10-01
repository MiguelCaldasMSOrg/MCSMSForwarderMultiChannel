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
    fun boundedExecutorReportsSaturationAndNeverRunsExpiredQueuedWork() {
        val executor = boundedDaemonExecutor("bounded-test", 1, 1)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expired = CountDownLatch(1)
        val queuedRuns = AtomicInteger()
        try {
            executor.execute {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertTrue(executor.executeWithDeadline(50, {
                queuedRuns.incrementAndGet()
            }, { result ->
                assertTrue(result.exceptionOrNull() is TimeoutException)
                expired.countDown()
            }))
            var rejected = false
            assertFalse(executor.executeWithDeadline(50, { "not admitted" }, { result ->
                rejected = result.exceptionOrNull() is RejectedExecutionException
            }))
            assertTrue(rejected)
            assertTrue(expired.await(1, TimeUnit.SECONDS))
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
            assertEquals(0, queuedRuns.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

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
    fun blockingTimeoutCallbackDoesNotDelayAnotherDeadline() {
        val executor = cachedDaemonExecutor("blocked-callback-test")
        val releaseWorkers = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val firstCallback = CountDownLatch(1)
        val secondCallback = CountDownLatch(1)

        try {
            assertTrue(executor.executeWithDeadline(timeoutMs = 50, block = {
                releaseWorkers.await()
                "late"
            }, onResult = { result ->
                assertTrue(result.exceptionOrNull() is TimeoutException)
                firstCallback.countDown()
                releaseCallback.await()
            }))
            assertTrue(firstCallback.await(1, TimeUnit.SECONDS))
            assertTrue(executor.executeWithDeadline(timeoutMs = 50, block = {
                releaseWorkers.await()
                "late"
            }, onResult = { result ->
                assertTrue(result.exceptionOrNull() is TimeoutException)
                secondCallback.countDown()
            }))
            assertTrue(secondCallback.await(1, TimeUnit.SECONDS))
        } finally {
            releaseCallback.countDown()
            releaseWorkers.countDown()
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
