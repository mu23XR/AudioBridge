import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.Line

/** Force the publication/accounting interleaving without opening an OS sound device. */
internal object DesktopAudioInitializationChecks {
    @JvmStatic fun main(args: Array<String>) = runChecks()

    fun runChecks() {
        failedOpenWaitsForOwnershipRelease()
        successfulOpenCanTransferBeforeInitializationFinishes()
        finishFailureIsReportedWithoutReplacingTheOpenFailure()
        finishFailureDoesNotReclaimADeliveredOutput()
        println("Audio initialization failure ordering and ownership transfer checks passed")
    }

    private fun failedOpenWaitsForOwnershipRelease() {
        val enteredFinish = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val owners = AtomicInteger(1)
        val ownersAtFailure = AtomicInteger(-1)
        val original = IllegalStateException("synthetic native open failure")
        val caller = Thread({
            try {
                DesktopAudioInitialization.open<Line>(onFinished = {
                    enteredFinish.countDown()
                    releaseFinish.await()
                    owners.decrementAndGet()
                }) { throw original }
            } catch (problem: Throwable) {
                ownersAtFailure.set(owners.get())
                failure.set(problem)
            } finally { returned.countDown() }
        }, "test-failed-output-open").apply { isDaemon = true; start() }
        try {
            check(enteredFinish.await(1, TimeUnit.SECONDS)) { "Native open did not reach ownership release" }
            check(!returned.await(150, TimeUnit.MILLISECONDS)) {
                "Open failure escaped while native initialization still owned the output"
            }
            releaseFinish.countDown()
            check(returned.await(1, TimeUnit.SECONDS)) { "Open failure was not published after ownership release" }
            check(ownersAtFailure.get() == 0)
            check(failure.get() is PlaybackOutputFailure && failure.get().cause?.cause === original)
        } finally {
            releaseFinish.countDown()
            caller.join(1_000)
            check(!caller.isAlive) { "Failed-open test left its caller running" }
        }
    }

    private fun successfulOpenCanTransferBeforeInitializationFinishes() {
        val enteredFinish = CountDownLatch(1)
        val releaseFinish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val result = AtomicReference<Line>()
        val failure = AtomicReference<Throwable>()
        val closes = AtomicInteger()
        val line = output(closes)
        val caller = Thread({
            try {
                result.set(DesktopAudioInitialization.open(onFinished = {
                    enteredFinish.countDown(); releaseFinish.await()
                }) { line })
            } catch (problem: Throwable) { failure.set(problem) }
            finally { returned.countDown() }
        }, "test-successful-output-open").apply { isDaemon = true; start() }
        try {
            check(enteredFinish.await(1, TimeUnit.SECONDS))
            check(returned.await(1, TimeUnit.SECONDS)) { "Successful output transfer was delayed by initialization accounting" }
            check(failure.get() == null && result.get() === line && closes.get() == 0)
        } finally {
            releaseFinish.countDown()
            caller.join(1_000)
            check(!caller.isAlive)
            result.get()?.close()
        }
        check(closes.get() == 1) { "Delivered output ownership was lost or closed twice" }
    }

    private fun finishFailureIsReportedWithoutReplacingTheOpenFailure() {
        for (reportThrows in listOf(false, true)) {
            val original = IllegalStateException("synthetic native open failure")
            val finishFailure = IllegalStateException("synthetic accounting failure")
            val reportFailure = IllegalStateException("synthetic cleanup reporter failure")
            val reported = AtomicReference<Throwable>()
            val failure = runCatching {
                DesktopAudioInitialization.open<Line>(onFinished = { throw finishFailure },
                    onCleanupFailure = { reported.set(it); if (reportThrows) throw reportFailure }) {
                    throw original
                }
            }.exceptionOrNull()
            check(failure is PlaybackOutputFailure && failure.cause?.cause === original)
            check(reported.get() === finishFailure)
            check(original.suppressed.singleOrNull() === finishFailure)
            check(finishFailure.suppressed.toList() == if (reportThrows) listOf(reportFailure) else emptyList())
        }
    }

    private fun finishFailureDoesNotReclaimADeliveredOutput() {
        val reported = CountDownLatch(1)
        val closes = AtomicInteger()
        val line = output(closes)
        val failure = IllegalStateException("synthetic post-transfer accounting failure")
        val actual = DesktopAudioInitialization.open(onFinished = { throw failure },
            onCleanupFailure = { check(it === failure); reported.countDown() }) { line }
        try {
            check(reported.await(1, TimeUnit.SECONDS)) { "Post-transfer accounting failure was not reported" }
            check(actual === line && closes.get() == 0) { "Accounting failure reclaimed the caller's output" }
        } finally { actual.close() }
        check(closes.get() == 1)
    }

    private fun output(closes: AtomicInteger): Line = Proxy.newProxyInstance(Line::class.java.classLoader,
        arrayOf(Line::class.java)) { proxy, method, args ->
        when (method.name) {
            "close" -> { closes.incrementAndGet(); null }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "synthetic initialization output"
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        }
    } as Line
}
