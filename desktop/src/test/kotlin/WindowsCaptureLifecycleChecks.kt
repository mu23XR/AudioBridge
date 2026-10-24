import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Native calls are in-memory fakes. These checks never load the native library. */
internal object WindowsCaptureLifecycleChecks {
    fun runChecks() {
        allNativeOperationsHaveOneOwner()
        lateStartIsClosedBeforeReplacement()
        stoppingQueuedStartPreventsAdoption()
        stopWakesAQuietRead()
        timedOutCloseRetainsItsLease()
        failedCloseCanBeRetriedWithoutLosingItsOwner()
        failedReadReleasesItsOwnerAndReportsTheNativeFailure()
        println("Windows capture owner thread, queued/late start cancellation, quiet-read wake-up and retained cleanup leases passed")
    }

    private fun allNativeOperationsHaveOneOwner() {
        WindowsCaptureThread().use { runtime ->
            val first = FakeCapture()
            val second = FakeCapture()
            val capture = WindowsCaptureLifecycle(first, runtime)
            val replacement = WindowsCaptureLifecycle(second, runtime)
            check(capture.start() && capture.isStarted)
            check(capture.read()!!.contentEquals(shortArrayOf(12, 34)))
            check(runCatching { replacement.start() }.exceptionOrNull() is CaptureLifecycleFailure)
            check(second.starts.get() == 0)
            capture.stop()
            check(!capture.isStarted && first.stops.get() == 1)
            check(replacement.start())
            replacement.stop()
            val nativeThreads = first.calls.map { it.second } + second.calls.map { it.second }
            check(nativeThreads.distinct().size == 1 && nativeThreads.first() != Thread.currentThread().id)
            check(first.calls.map { it.first } == listOf("start", "read", "stop"))
        }
    }

    private fun lateStartIsClosedBeforeReplacement() {
        WindowsCaptureThread().use { runtime ->
            val startEntered = CountDownLatch(1)
            val startRelease = CountDownLatch(1)
            val first = FakeCapture(onStart = { startEntered.countDown(); startRelease.await() })
            val capture = WindowsCaptureLifecycle(first, runtime, startTimeoutMs = 40, stopTimeoutMs = 40)
            val second = FakeCapture()
            val replacement = WindowsCaptureLifecycle(second, runtime, stopTimeoutMs = 40)
            try {
                check(runCatching { capture.start() }.exceptionOrNull() is CaptureLifecycleFailure)
                check(startEntered.count == 0L && !capture.isStarted)
                check(runCatching { replacement.start() }.exceptionOrNull() is CaptureLifecycleFailure)
                check(second.starts.get() == 0)
                startRelease.countDown()
                await { synchronized(runtime.lock) { runtime.owner == null } }
                check(first.stops.get() == 1 && !first.live)
                check(replacement.start())
                replacement.stop()
            } finally { startRelease.countDown(); capture.stop(); replacement.stop() }
        }
    }

    private fun stoppingQueuedStartPreventsAdoption() {
        WindowsCaptureThread().use { runtime ->
            val workerEntered = CountDownLatch(1)
            val workerRelease = CountDownLatch(1)
            runtime.submit { workerEntered.countDown(); workerRelease.await() }
            check(workerEntered.await(1, TimeUnit.SECONDS))
            val backend = FakeCapture()
            val capture = WindowsCaptureLifecycle(backend, runtime, startTimeoutMs = 1_000, stopTimeoutMs = 40)
            val start = background { capture.start() }
            try {
                await { synchronized(runtime.lock) { runtime.owner === capture } }
                check(runCatching { capture.stop() }.exceptionOrNull() is CaptureLifecycleFailure)
                workerRelease.countDown()
                check(!start.get(1, TimeUnit.SECONDS))
                await { synchronized(runtime.lock) { runtime.owner == null } }
                check(backend.starts.get() == 0 && backend.stops.get() == 1 && !capture.isStarted)
            } finally { workerRelease.countDown(); capture.stop() }
        }
    }

    private fun stopWakesAQuietRead() {
        WindowsCaptureThread().use { runtime ->
            val readEntered = CountDownLatch(1)
            val readRelease = CountDownLatch(1)
            val backend = FakeCapture(onRead = { readEntered.countDown(); readRelease.await() },
                onStopRequest = { readRelease.countDown() })
            val capture = WindowsCaptureLifecycle(backend, runtime, stopTimeoutMs = 500)
            check(capture.start())
            val read = background { capture.read() }
            try {
                check(readEntered.await(1, TimeUnit.SECONDS))
                capture.stop()
                check(read.get(1, TimeUnit.SECONDS) == null)
                check(backend.requests.get() > 0 && backend.stops.get() == 1)
                check(backend.calls.map { it.second }.distinct().size == 1)
                check(backend.calls.map { it.first } == listOf("start", "read", "stop"))
            } finally { readRelease.countDown(); capture.stop() }
        }
    }

    private fun timedOutCloseRetainsItsLease() {
        WindowsCaptureThread().use { runtime ->
            val closeEntered = CountDownLatch(1)
            val closeRelease = CountDownLatch(1)
            val backend = FakeCapture(onStop = { closeEntered.countDown(); closeRelease.await() })
            val capture = WindowsCaptureLifecycle(backend, runtime, stopTimeoutMs = 40)
            val next = FakeCapture()
            val replacement = WindowsCaptureLifecycle(next, runtime, stopTimeoutMs = 40)
            check(capture.start())
            try {
                check(runCatching { capture.stop() }.exceptionOrNull() is CaptureLifecycleFailure)
                check(closeEntered.count == 0L)
                check(runCatching { replacement.start() }.exceptionOrNull() is CaptureLifecycleFailure)
                check(next.starts.get() == 0 && backend.stops.get() == 1)
                closeRelease.countDown()
                await { synchronized(runtime.lock) { runtime.owner == null } }
                check(replacement.start())
                replacement.stop()
            } finally { closeRelease.countDown(); capture.stop(); replacement.stop() }
        }
    }

    private fun failedCloseCanBeRetriedWithoutLosingItsOwner() {
        WindowsCaptureThread().use { runtime ->
            val backend = FakeCapture()
            val capture = WindowsCaptureLifecycle(backend, runtime)
            check(capture.start())
            backend.releaseAllowed = false
            try {
                check(runCatching { capture.stop() }.exceptionOrNull() is CaptureLifecycleFailure)
                check(synchronized(runtime.lock) { runtime.owner === capture })
                backend.releaseAllowed = true
                capture.stop()
                check(backend.stops.get() == 2 && synchronized(runtime.lock) { runtime.owner == null })
            } finally { backend.releaseAllowed = true; capture.stop() }
        }
    }

    private fun failedReadReleasesItsOwnerAndReportsTheNativeFailure() {
        WindowsCaptureThread().use { runtime ->
            val backend = FakeCapture(onRead = {
                throw CaptureLifecycleFailure("IAudioCaptureClient_GetBuffer failed: 0x88890004")
            })
            val capture = WindowsCaptureLifecycle(backend, runtime)
            val replacement = WindowsCaptureLifecycle(FakeCapture(), runtime)
            check(capture.start())
            try {
                val failure = runCatching { capture.read() }.exceptionOrNull()
                check(failure is CaptureLifecycleFailure && failure.message!!.contains("0x88890004"))
                await { synchronized(runtime.lock) { runtime.owner == null } }
                check(backend.stops.get() == 1 && !backend.live)
                check(replacement.start())
                replacement.stop()
            } finally { capture.stop(); replacement.stop() }
        }
    }

    private class FakeCapture(
        private val onStart: () -> Unit = {},
        private val onRead: () -> Unit = {},
        private val onStop: () -> Unit = {},
        private val onStopRequest: () -> Unit = {}
    ) : WindowsCaptureBackend {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val requests = AtomicInteger()
        val calls = ConcurrentLinkedQueue<Pair<String, Long>>()
        @Volatile var live = false
        @Volatile var releaseAllowed = true
        override fun start(): Boolean {
            calls.add("start" to Thread.currentThread().id); starts.incrementAndGet()
            onStart(); live = true; return true
        }
        override fun read(): ShortArray {
            calls.add("read" to Thread.currentThread().id); check(live)
            onRead(); return shortArrayOf(12, 34)
        }
        override fun stop() {
            calls.add("stop" to Thread.currentThread().id); stops.incrementAndGet()
            onStop(); if (releaseAllowed) live = false
        }
        override fun requestStop() { requests.incrementAndGet(); onStopRequest() }
        override fun cleanupComplete() = !live
    }

    private fun <T> background(operation: () -> T): FutureTask<T> = FutureTask(operation).also {
        Thread(it, "fake-windows-capture-caller").apply { isDaemon = true; start() }
    }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(2)
        check(predicate()) { "Fake capture operation did not complete" }
    }
}
