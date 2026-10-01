package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val deadlineScheduler = ScheduledThreadPoolExecutor(1, namedDaemonThreadFactory("deadline-timer")).apply {
    removeOnCancelPolicy = true
}
private val deadlineSlots = Semaphore(128)
private val deadlineCallbacks = boundedDaemonExecutor("deadline-callback", 4, 128)

/**
 * Creates a single-thread [ExecutorService] backed by one daemon thread named [name].
 */
internal fun singleThreadDaemonExecutor(name: String): ExecutorService =
    Executors.newSingleThreadExecutor(namedDaemonThreadFactory(name))

/**
 * Runs independent requests concurrently with finite threads and pending work.
 */
internal fun cachedDaemonExecutor(name: String): ExecutorService =
    boundedDaemonExecutor(name, 8, 32)

internal fun boundedDaemonExecutor(name: String, workers: Int, queueCapacity: Int): ExecutorService = ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, ArrayBlockingQueue(queueCapacity), namedDaemonThreadFactory(name), ThreadPoolExecutor.AbortPolicy()).apply {
    allowCoreThreadTimeOut(true)
}

internal fun scheduleDeadline(timeoutMs: Long, action: () -> Unit): java.util.concurrent.ScheduledFuture<*> = deadlineScheduler.schedule(action, timeoutMs, TimeUnit.MILLISECONDS)

/**
 * Bounds the work future by [timeoutMs] even if the blocking transport has not returned.
 * Reserved callback capacity isolates delivery from the timer; the receiver independently
 * guards its overall pending-result lifetime if callback delivery is delayed.
 */
internal fun <T : Any> ExecutorService.executeWithDeadline(
    timeoutMs: Long,
    block: () -> T,
    onResult: (Result<T>) -> Unit,
): Boolean {
    if (!deadlineSlots.tryAcquire()) {
        onResult(Result.failure(RejectedExecutionException("Send completion capacity exhausted")))
        return false
    }
    val future = try {
        CompletableFuture.supplyAsync({ block() }, this)
    } catch (e: RejectedExecutionException) {
        deadlineSlots.release()
        onResult(Result.failure(e))
        return false
    }
    val timeout = deadlineScheduler.schedule({ future.completeExceptionally(TimeoutException()) }, timeoutMs, TimeUnit.MILLISECONDS)
    future.whenComplete { _, _ ->
        timeout.cancel(false)
    }
    future.whenCompleteAsync({ value, error ->
        val result = if (error == null) {
            Result.success(value)
        } else {
            Result.failure(error.unwrapCompletionException())
        }
        try {
            onResult(result)
        } finally {
            deadlineSlots.release()
        }
    }, deadlineCallbacks)
    return true
}

private fun Throwable.unwrapCompletionException(): Throwable =
    if (this is CompletionException && cause != null) cause!! else this

private fun namedDaemonThreadFactory(name: String): ThreadFactory =
    ThreadFactory { runnable ->
        Thread(runnable, name).apply {
            isDaemon = true
        }
    }
