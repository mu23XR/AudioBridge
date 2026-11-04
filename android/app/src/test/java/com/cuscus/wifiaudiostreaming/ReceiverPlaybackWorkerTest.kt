package com.cuscus.wifiaudiostreaming

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReceiverPlaybackWorkerTest {
    private class Fixture(name: String) {
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val worker = ReceiverPlaybackWorker(name) { failures.add(it) }
    }

    @Test fun suspendingPlaybackKeepsOneOwnedThread() = runBlocking {
        val fixture = Fixture("test-playout-thread")
        val observed = Collections.synchronizedList(mutableListOf<Thread>())
        val ready = CompletableDeferred<Unit>()
        fixture.worker.start {
            repeat(20) {
                observed.add(Thread.currentThread())
                yield()
                delay(1)
            }
            ready.complete(Unit)
            awaitCancellation()
        }
        try {
            withTimeout(2_000) { ready.await() }
            assertEquals(1, observed.toSet().size)
            assertEquals("test-playout-thread", observed.first().name)
        } finally { fixture.worker.stop {}; assertTrue(fixture.worker.awaitStopped()) }
        val thread = observed.first()
        thread.join(1_000)
        assertFalse("The owned playback thread survived shutdown", thread.isAlive)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun concurrentStartsChooseOnePlaybackOwner() = runBlocking {
        val fixture = Fixture("test-playout-single-owner")
        val pool = Executors.newFixedThreadPool(8)
        val gate = CountDownLatch(1)
        val ready = CountDownLatch(8)
        val submitted = CountDownLatch(8)
        val accepted = AtomicInteger()
        val plays = AtomicInteger()
        val began = CompletableDeferred<Unit>()
        try {
            repeat(8) {
                pool.execute {
                    ready.countDown(); gate.await()
                    if (fixture.worker.start { plays.incrementAndGet(); began.complete(Unit); awaitCancellation() }) {
                        accepted.incrementAndGet()
                    }
                    submitted.countDown()
                }
            }
            assertTrue(ready.await(1, TimeUnit.SECONDS))
            gate.countDown()
            assertTrue(submitted.await(1, TimeUnit.SECONDS))
            withTimeout(2_000) { began.await() }
            assertEquals(1, accepted.get())
            assertEquals(1, plays.get())
        } finally {
            gate.countDown()
            fixture.worker.stop {}
            assertTrue(fixture.worker.awaitStopped())
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(1, TimeUnit.SECONDS))
        }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun repeatedStopCannotForgetItsBlockedFirstCleanup() = runBlocking {
        val fixture = Fixture("test-playout-first-cleanup")
        val began = CompletableDeferred<Unit>()
        val enteredRelease = CountDownLatch(1)
        val finishRelease = CountDownLatch(1)
        val releases = AtomicInteger()
        val replacementReleases = AtomicInteger()
        fixture.worker.start { began.complete(Unit); awaitCancellation() }
        withTimeout(2_000) { began.await() }
        try {
            assertTrue(fixture.worker.stop {
                releases.incrementAndGet(); enteredRelease.countDown(); finishRelease.await()
            })
            assertTrue(enteredRelease.await(1, TimeUnit.SECONDS))
            assertFalse(fixture.worker.stop { replacementReleases.incrementAndGet() })
            assertFalse("A later no-op stop hid the first cleanup", fixture.worker.awaitStopped(60))
        } finally { finishRelease.countDown(); assertTrue(fixture.worker.awaitStopped()) }
        assertEquals(1, releases.get())
        assertEquals(0, replacementReleases.get())
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun independentCleanupCanReleaseABlockedNativeOperation() = runBlocking {
        val fixture = Fixture("test-playout-native-block")
        val entered = CountDownLatch(1)
        val finishNative = CountDownLatch(1)
        val audioThread = AtomicReference<Thread>()
        val cleanupThread = AtomicReference<Thread>()
        val completed = AtomicInteger()
        fixture.worker.start {
            audioThread.set(Thread.currentThread()); entered.countDown()
            try { awaitUninterruptibly(finishNative) } finally { completed.incrementAndGet() }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.worker.stop {
                cleanupThread.set(Thread.currentThread()); finishNative.countDown()
            }
            assertTrue(fixture.worker.awaitStopped())
            assertNotSame(audioThread.get(), cleanupThread.get())
            assertEquals(1, completed.get())
        } finally { finishNative.countDown(); fixture.worker.stop {}; fixture.worker.awaitStopped() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun nativeWorkThatIgnoresReleaseKeepsTheStopDeadlineVisible() = runBlocking {
        val fixture = Fixture("test-playout-stop-timeout")
        val entered = CountDownLatch(1)
        val finishNative = CountDownLatch(1)
        fixture.worker.start { entered.countDown(); awaitUninterruptibly(finishNative) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.worker.stop {}
            assertFalse(fixture.worker.awaitStopped(60))
        } finally { finishNative.countDown(); assertTrue(fixture.worker.awaitStopped()) }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun stopBeforeFirstInputClosesTheUnusedExecutorAndRejectsStart() = runBlocking {
        val fixture = Fixture("test-playout-no-input")
        val plays = AtomicInteger()
        val releases = AtomicInteger()
        assertTrue(fixture.worker.stop { releases.incrementAndGet() })
        assertFalse(fixture.worker.start { plays.incrementAndGet() })
        assertTrue(fixture.worker.awaitStopped())
        assertEquals(0, plays.get())
        assertEquals(1, releases.get())
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun initializationFailureAndNaturalCompletionCloseTheirExecutors() = runBlocking {
        for (fail in listOf(false, true)) {
            val original = IllegalStateException("synthetic playback initialization failure")
            val finished = CompletableDeferred<Unit>()
            val reported = CompletableDeferred<Unit>()
            val thread = AtomicReference<Thread>()
            val failures = Collections.synchronizedList(mutableListOf<Throwable>())
            val worker = ReceiverPlaybackWorker("test-playout-ended-$fail") {
                failures.add(it); reported.complete(Unit)
            }
            worker.start { thread.set(Thread.currentThread()); finished.complete(Unit); if (fail) throw original }
            withTimeout(2_000) { finished.await() }
            if (fail) withTimeout(2_000) { reported.await() }
            assertEquals("An unexpected worker failure must retain its cleanup barrier", !fail, worker.awaitStopped())
            assertFalse(worker.start { error("Completed playback restarted") })
            assertEquals(if (fail) listOf(original) else emptyList<Throwable>(), failures)
            worker.stop {}
            assertEquals(!fail, worker.awaitStopped())
            thread.get().join(1_000)
            assertFalse("Worker exit did not release its thread", thread.get().isAlive)
        }
    }

    @Test fun releaseFailureInThePlaybackFinallyCannotConfirmCleanup() = runBlocking {
        val original = IllegalStateException("synthetic playback-finally release failure")
        val observed = CompletableDeferred<Throwable>()
        val began = CompletableDeferred<Unit>()
        val thread = AtomicReference<Thread>()
        val worker = ReceiverPlaybackWorker("test-playout-finally-release-failure") { observed.complete(it) }
        worker.start {
            thread.set(Thread.currentThread())
            try { began.complete(Unit); awaitCancellation() }
            finally { throw original }
        }
        withTimeout(2_000) { began.await() }
        worker.stop {}
        assertSame(original, withTimeout(2_000) { observed.await() })
        assertFalse("A failed finally release was treated as successful stop", worker.awaitStopped())
        thread.get().join(1_000)
        assertFalse(thread.get().isAlive)
    }

    @Test fun registrationThatCompletesAfterIndependentReleaseCannotLeaveAMixerEntry() = runBlocking {
        val fixture = Fixture("test-playout-late-registration")
        val output = Any()
        val owned = AtomicReference<Any>(output)
        val registered = AtomicReference<Any>()
        val entered = CountDownLatch(1)
        val finishRegister = CountDownLatch(1)
        val released = CountDownLatch(1)
        fun releaseOutput() {
            owned.getAndSet(null)?.let { registered.compareAndSet(it, null) }
        }
        fixture.worker.start {
            try {
                entered.countDown(); awaitUninterruptibly(finishRegister)
                registered.set(output)
                fixture.worker.finishRegistration { registered.compareAndSet(output, null) }
            } finally { releaseOutput() }
        }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            fixture.worker.stop { releaseOutput(); released.countDown() }
            assertTrue(released.await(1, TimeUnit.SECONDS))
            assertNull(owned.get())
            finishRegister.countDown()
            assertTrue(fixture.worker.awaitStopped())
            assertNull("Late registration survived its already-released output", registered.get())
        } finally { finishRegister.countDown(); fixture.worker.stop {}; fixture.worker.awaitStopped() }
        assertTrue(fixture.failures.isEmpty())
    }

    @Test fun liveRegistrationDoesNotRemoveItsOutput() = runBlocking {
        val fixture = Fixture("test-playout-live-registration")
        val unregisters = AtomicInteger()
        fixture.worker.finishRegistration { unregisters.incrementAndGet() }
        assertEquals(0, unregisters.get())
        fixture.worker.stop {}
        assertTrue(fixture.worker.awaitStopped())
    }

    @Test fun cleanupFailureIsReportedAndCannotConfirmOutputRelease() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val original = if (cancelled) CancellationException("synthetic release cancellation")
                else IllegalStateException("synthetic native cleanup failure")
            val observed = CompletableDeferred<Throwable>()
            val thread = AtomicReference<Thread>()
            val began = CompletableDeferred<Unit>()
            val worker = ReceiverPlaybackWorker("test-playout-cleanup-failure-$cancelled") { observed.complete(it) }
            worker.start { thread.set(Thread.currentThread()); began.complete(Unit); awaitCancellation() }
            withTimeout(2_000) { began.await() }
            worker.stop { throw original }
            assertSame(original, withTimeout(2_000) { observed.await() })
            assertFalse("Unconfirmed native release became eligible for reconnect", worker.awaitStopped())
            thread.get().join(1_000)
            assertFalse("Release failure leaked the owned thread", thread.get().isAlive)
        }
    }

    @Test fun blockedSourceCannotOccupyAnotherSourcesThread() = runBlocking {
        val first = Fixture("test-playout-independent-a")
        val second = Fixture("test-playout-independent-b")
        val entered = CountDownLatch(1)
        val finishNative = CountDownLatch(1)
        val secondBegan = CompletableDeferred<Thread>()
        val firstThread = AtomicReference<Thread>()
        first.worker.start { firstThread.set(Thread.currentThread()); entered.countDown(); awaitUninterruptibly(finishNative) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            second.worker.start { secondBegan.complete(Thread.currentThread()); awaitCancellation() }
            val secondThread = withTimeout(1_000) { secondBegan.await() }
            assertNotSame(firstThread.get(), secondThread)
            assertTrue(secondThread.name.startsWith("test-playout-independent-b"))
        } finally {
            first.worker.stop { finishNative.countDown() }; second.worker.stop {}
            assertTrue(first.worker.awaitStopped()); assertTrue(second.worker.awaitStopped())
        }
        assertTrue(first.failures.isEmpty()); assertTrue(second.failures.isEmpty())
    }

    @Test fun repeatedStartStopRacesDoNotLeaveThreadsOrCleanupBehind() = runBlocking {
        repeat(30) { attempt ->
            val fixture = Fixture("test-playout-race-$attempt")
            val releases = AtomicInteger()
            val begin = CountDownLatch(1)
            val starter = Thread({ begin.await(); fixture.worker.start { awaitCancellation() } })
            val stopper = Thread({ begin.await(); fixture.worker.stop { releases.incrementAndGet() } })
            starter.start(); stopper.start(); begin.countDown()
            starter.join(1_000); stopper.join(1_000)
            assertFalse(starter.isAlive); assertFalse(stopper.isAlive)
            assertTrue(fixture.worker.awaitStopped())
            assertEquals(1, releases.get())
            assertTrue(fixture.failures.isEmpty())
        }
        val survivors = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("test-playout-race-") }
        survivors.forEach { it.join(1_000) }
        assertTrue("Playback threads survived their awaitStopped contract", survivors.none { it.isAlive })
    }

    private fun awaitUninterruptibly(latch: CountDownLatch) {
        var interrupted = false
        while (true) {
            try { latch.await(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
