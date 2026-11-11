package com.cuscus.wifiaudiostreaming

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** A source's timed audio work must not queue behind unrelated shared IO work. */
internal class ReceiverPlaybackWorker(
    threadName: String,
    private val onFailure: (Throwable) -> Unit
) {
    private val lock = Any()
    private val executor = ScheduledThreadPoolExecutor(1) { command ->
        Thread(command, threadName).apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val failures = CoroutineExceptionHandler { _, failure -> onFailure(failure) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + failures)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + failures)
    private val closed = AtomicBoolean()
    private val cleanupUnconfirmed = AtomicBoolean()
    private var worker: Job? = null
    private var releaseJob: Job? = null
    private var stopped = false

    fun start(play: suspend CoroutineScope.() -> Unit): Boolean = synchronized(lock) {
        if (stopped || worker != null) return false
        val next = scope.launch(start = CoroutineStart.LAZY) {
            try { play() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                cleanupUnconfirmed.set(true)
                throw failure
            }
        }
        worker = next
        // A lazy coroutine can be cancelled before its body/finally ever runs.
        next.invokeOnCompletion { closeDispatcher() }
        next.start()
        true
    }

    fun stop(release: () -> Unit): Boolean = synchronized(lock) {
        if (stopped) return false
        stopped = true
        val cleanup = cleanupScope.launch(start = CoroutineStart.LAZY) {
            try { release() }
            catch (failure: Throwable) {
                cleanupUnconfirmed.set(true)
                // Cleanup is never deliberately cancelled. A callback throwing
                // cancellation still leaves release unconfirmed and needs a log.
                if (failure is CancellationException) {
                    try { onFailure(failure) }
                    catch (reportFailure: Throwable) {
                        if (reportFailure !== failure) reportFailure.addSuppressed(failure)
                        throw reportFailure
                    }
                }
                throw failure
            }
        }
        releaseJob = cleanup
        cleanup.invokeOnCompletion { cleanupScope.cancel() }
        scope.cancel()
        if (worker == null) closeDispatcher()
        // Never queue this behind a native operation it may need to interrupt.
        cleanup.start()
        true
    }

    fun finishRegistration(unregister: () -> Unit) {
        // Native registration cannot hold our lock: independent release must
        // still interrupt it. Remove an entry published after that release.
        if (synchronized(lock) { stopped }) {
            unregister()
            throw CancellationException("Source stopped during output registration")
        }
    }

    suspend fun awaitStopped(timeoutMs: Long = 1500): Boolean {
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return withTimeoutOrNull(timeoutMs) {
            synchronized(lock) { worker }?.join()
            synchronized(lock) { releaseJob }?.join()
            val remaining = deadline - System.nanoTime()
            val terminated = when {
                executor.isTerminated -> true
                remaining <= 0 -> false
                else -> runInterruptible(Dispatchers.IO) {
                    executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)
                }
            }
            terminated && !cleanupUnconfirmed.get()
        } ?: false
    }

    private fun closeDispatcher() {
        if (closed.compareAndSet(false, true)) {
            try {
                scope.cancel()
                dispatcher.close()
            } catch (failure: Throwable) {
                cleanupUnconfirmed.set(true)
                throw failure
            }
        }
    }
}
