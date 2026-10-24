import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class CaptureLifecycleFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** Only requestStop may run outside the capture's COM owner thread. */
internal interface WindowsCaptureBackend {
    fun start(): Boolean
    fun read(): ShortArray?
    fun stop()
    fun requestStop()
    fun cleanupComplete(): Boolean
}

/** The JNI loopback capture is process-global, so its thread and lease must be global too. */
internal class WindowsCaptureThread : AutoCloseable {
    internal val lock = Any()
    internal var owner: WindowsCaptureLifecycle? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "audiobridge-windows-capture").apply { isDaemon = true }
    }
    internal fun <T> submit(operation: () -> T): Future<T> = executor.submit(Callable(operation))
    override fun close() {
        check(synchronized(lock) { owner == null }) { "Cannot discard an unreleased capture owner" }
        executor.shutdown()
        check(executor.awaitTermination(2, TimeUnit.SECONDS)) { "Capture executor did not terminate" }
    }
    companion object {
        val shared by lazy { WindowsCaptureThread() }
        fun stopPendingOwner() {
            val pending = synchronized(shared.lock) { shared.owner }
            pending?.stop()
        }
    }
}

/**
 * A timed out call loses intent, never native ownership. The same executor closes
 * a late start before the lease can move to another engine. No COM call is made
 * by a stopping network coroutine or by a completion callback on another thread.
 */
internal class WindowsCaptureLifecycle(
    private val backend: WindowsCaptureBackend,
    private val thread: WindowsCaptureThread = WindowsCaptureThread.shared,
    private val startTimeoutMs: Long = 3_000,
    private val stopTimeoutMs: Long = 3_500,
    private val readTimeoutMs: Long = 5_000
) {
    private var generation = 0L
    private var desired = false
    private var started = false
    private var cleanup: Future<Unit>? = null
    val isStarted: Boolean get() = synchronized(thread.lock) { thread.owner === this && desired && started }

    fun start(): Boolean {
        val previous = synchronized(thread.lock) {
            thread.owner?.also {
                if (it === this && it.desired && it.started) return true
                if (it.desired) throw CaptureLifecycleFailure("Another Windows capture operation owns the native engine")
            }
        }
        previous?.stop()
        val future = synchronized(thread.lock) {
            if (thread.owner === this && desired && started) return true
            if (thread.owner != null) throw CaptureLifecycleFailure("Previous Windows capture cleanup is still pending")
            thread.owner = this
            desired = true; started = false; cleanup = null
            val token = ++generation
            thread.submit {
                if (!ownsIntent(token)) {
                    releaseOnOwnerThread()
                    false
                } else {
                    try {
                        val opened = backend.start()
                        val adopted = synchronized(thread.lock) {
                            if (opened && thread.owner === this && generation == token && desired) {
                                started = true; true
                            } else false
                        }
                        if (!adopted) releaseOnOwnerThread()
                        adopted
                    } catch (problem: Throwable) {
                        try { releaseOnOwnerThread() } catch (closing: Throwable) { problem.addSuppressed(closing) }
                        throw problem
                    }
                }
            }
        }
        return try { await(future, startTimeoutMs, "start") }
        catch (problem: CaptureLifecycleFailure) {
            try { retire() } catch (closing: CaptureLifecycleFailure) { problem.addSuppressed(closing) }
            throw problem
        }
    }

    fun read(): ShortArray? {
        val future = synchronized(thread.lock) {
            if (thread.owner !== this || !desired || !started) return null
            val token = generation
            thread.submit {
                if (!ownsIntent(token)) null else {
                    val samples = backend.read()
                    if (ownsIntent(token)) samples else null
                }
            }
        }
        return try { await(future, readTimeoutMs, "read") }
        catch (problem: CaptureLifecycleFailure) {
            try { retire() } catch (closing: CaptureLifecycleFailure) { problem.addSuppressed(closing) }
            throw problem
        }
    }

    fun stop() {
        val future = retire() ?: return
        await(future, stopTimeoutMs, "stop")
    }

    private fun ownsIntent(token: Long): Boolean = synchronized(thread.lock) {
        thread.owner === this && desired && generation == token
    }

    private fun retire(): Future<Unit>? {
        var wakeFailure: Exception? = null
        val future = synchronized(thread.lock) {
            if (thread.owner !== this) return null
            desired = false; started = false; generation++
            // requestStop only writes an atomic intent flag. Keep the lease
            // locked until it has run, otherwise a late wake-up could stop a
            // replacement engine after the old cleanup released its lease.
            try { backend.requestStop() } catch (problem: Exception) { wakeFailure = problem }
            // Keep a running cleanup future on timeout. Repeated buttons must not
            // fill the executor queue or discard a still-running native call.
            cleanup?.takeUnless { it.isDone } ?: thread.submit { releaseOnOwnerThread() }.also { cleanup = it }
        }
        wakeFailure?.let { problem ->
            // The queued owner-thread release still owns the engine. Surface
            // the failed wake-up rather than pretending shutdown completed.
            throw CaptureLifecycleFailure("Cannot request Windows capture shutdown", problem)
        }
        return future
    }

    private fun releaseOnOwnerThread() {
        if (synchronized(thread.lock) { thread.owner !== this }) return
        backend.stop()
        if (!backend.cleanupComplete()) throw CaptureLifecycleFailure("Windows capture native resources were not released")
        synchronized(thread.lock) {
            if (thread.owner === this) {
                desired = false; started = false; thread.owner = null
            }
        }
    }

    private fun <T> await(future: Future<T>, timeoutMs: Long, operation: String): T {
        try { return future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (problem: TimeoutException) {
            throw CaptureLifecycleFailure("Windows capture $operation exceeded $timeoutMs ms; native ownership retained", problem)
        } catch (problem: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CaptureLifecycleFailure("Windows capture $operation interrupted; native ownership retained", problem)
        } catch (problem: ExecutionException) {
            val cause = problem.cause ?: problem
            throw if (cause is CaptureLifecycleFailure) cause else CaptureLifecycleFailure("Windows capture $operation failed", cause)
        }
    }
}
