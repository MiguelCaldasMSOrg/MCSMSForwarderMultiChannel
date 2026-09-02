package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Creates a single-thread [ExecutorService] backed by one daemon thread named [name].
 *
 * Used by the log writer to serialize work off the main thread without keeping
 * the JVM alive.
 */
internal fun singleThreadDaemonExecutor(name: String): ExecutorService =
    Executors.newSingleThreadExecutor(namedDaemonThreadFactory(name))

/**
 * Starts independent work immediately so one slow request cannot queue or reject
 * an unrelated forward.
 */
internal fun cachedDaemonExecutor(name: String): ExecutorService =
    Executors.newCachedThreadPool(namedDaemonThreadFactory(name))

/**
 * Completes [onResult] within [timeoutMs] even if the blocking transport has not
 * returned, keeping BroadcastReceiver pending results inside their time budget.
 */
internal fun <T : Any> ExecutorService.executeWithDeadline(
    timeoutMs: Long,
    block: () -> T,
    onResult: (Result<T>) -> Unit,
): Boolean {
    val future = try {
        CompletableFuture.supplyAsync({ block() }, this)
    } catch (e: RejectedExecutionException) {
        onResult(Result.failure(e))
        return false
    }
    future
        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .whenComplete { value, error ->
            val result = if (error == null) {
                Result.success(value)
            } else {
                Result.failure(error.unwrapCompletionException())
            }
            onResult(result)
        }
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
