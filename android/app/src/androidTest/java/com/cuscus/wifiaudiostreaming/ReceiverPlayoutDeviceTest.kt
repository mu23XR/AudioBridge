package com.cuscus.wifiaudiostreaming

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Explicit emulator-only AudioTrack regression, without a service, socket or saved-setting writes.
 * IDs never enter ReceiverMixer's weights, so register()'s zero gain remains zero throughout.
 * Native progress/queue bounds do not establish end-to-end latency or audible quality.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverPlayoutDeviceTest {
    private val failures = ConcurrentHashMap<String, String>()

    private fun requireEmulatorWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // MuMu can report a physical brand/hardware. The host must verify its
        // emulator serial and opt in; this test must never target a user's phone.
        assumeTrue("Requires an explicitly selected debug emulator",
            context.packageName.endsWith(".debug") &&
                InstrumentationRegistry.getArguments().getString("allowPlayoutFixture") == "true")
    }

    @Test fun smallPayloadsSustainSingleAndTwoIndependentClocks() = runBlocking {
        requireEmulatorWindow()
        val firstOutput = player("first", false, 45)
        val secondOutput = player("second", false, 73)
        val first = firstOutput.player
        val second = secondOutput.player
        val firstInput = PacedPcm { first.offer(it) }
        val secondInput = PacedPcm { second.offer(it) }
        try {
            firstInput.resume()
            await("first output starts", firstInput) { first.playedFrames > 2_400 }
            val single = observe(first, firstInput, 6_000, label = "manual45-single")
            assertSustained("single source", single)
            assertEquals(45, first.targetMs)

            secondInput.resume()
            await("second output starts independently", firstInput, secondInput) {
                second.playedFrames > 2_400
            }
            val firstAtTwo = first.playedFrames
            val secondAtTwo = second.playedFrames
            val firstDiscardAtTwo = first.snapshot().discardedBytes
            val secondDiscardAtTwo = second.snapshot().discardedBytes
            val secondNativeDiscardAtTwo = second.snapshot().nativeDiscardedFrames
            val both = observe(first, firstInput, 6_000, second, secondInput, "manual45-and73")
            assertSustained("first of two sources", both)
            assertProgress("second source", second.playedFrames - secondAtTwo, both.elapsedMs)
            val secondEnd = second.snapshot()
            assertDiscardBound("second source", secondEnd.discardedBytes - secondDiscardAtTwo +
                (secondEnd.nativeDiscardedFrames - secondNativeDiscardAtTwo) * FRAME_BYTES)
            assertTrue("first source stopped when another output was added", first.playedFrames > firstAtTwo)
            assertDiscardBound("first of two sources", first.snapshot().discardedBytes - firstDiscardAtTwo)
            assertEquals("adding a source changed the first target", 45, first.targetMs)
            assertEquals("second source lost its own manual target", 73, second.targetMs)

            firstInput.pauseAndAwait()
            first.noteSilence()
            val secondDuringPause = second.playedFrames
            observe(second, secondInput, 3_000, label = "manual73-other-source-silent")
            assertTrue("a silent/absent peer became a common playback barrier",
                second.playedFrames - secondDuringPause > RATE * 2)
            assertTrue("pause released the first source's output", ReceiverMixer.hasOutput(firstOutput.id))
            firstInput.resume()
            val resumed = observe(first, firstInput, 3_000, second, secondInput, "manual45-dual-resume")
            assertProgress("resumed first source", resumed.frames, resumed.elapsedMs)
            assertEquals(45, first.targetMs)
            assertEquals(73, second.targetMs)
        } finally {
            cleanup(listOf(firstOutput, secondOutput), listOf(firstInput, secondInput))
        }
    }

    @Test fun intentionalSilenceDoesNotRaiseAdaptiveTargetOrRebuildOutput() = runBlocking {
        requireEmulatorWindow()
        soleOutputSilenceAndResume(adaptive = true, targetMs = 20)
    }

    @Test fun soleManualOutputResumesAfterIntentionalSilenceWithoutDiscardStorm() = runBlocking {
        requireEmulatorWindow()
        soleOutputSilenceAndResume(adaptive = false, targetMs = 45)
    }

    @Test fun minimumManualTargetKeepsClockCorrectionAndSilenceResume() = runBlocking {
        requireEmulatorWindow()
        soleOutputSilenceAndResume(adaptive = false, targetMs = 20)
    }

    private suspend fun soleOutputSilenceAndResume(adaptive: Boolean, targetMs: Int) {
        val mode = if (adaptive) "adaptive" else "manual$targetMs"
        val output = player("sole-$mode-silence", adaptive, targetMs)
        val player = output.player
        val input = PacedPcm { player.offer(it) }
        try {
            input.resume()
            await("$mode output starts", input) { player.playedFrames > 2_400 }
            val initial = observe(player, input, 3_000, label = "$mode-sole-before-silence")
            assertSustained("$mode before silence", initial)
            assertTrue("$mode output never applied native clock correction: $initial", initial.rateCorrectionApplied)
            input.pauseAndAwait()
            player.noteSilence()
            val before = player.snapshot()
            observe(player, input, 4_500, label = "$mode-sole-intentional-silence")
            val silent = player.snapshot()
            assertTrue("intentional silence did not keep the output consuming: before=$before after=$silent",
                silent.playedFrames - before.playedFrames >= RATE * 3)
            assertEquals("intentional silence changed $mode target", before.targetMs, silent.targetMs)
            assertEquals("intentional silence rebuilt the output", before.rebuilds, silent.rebuilds)
            assertTrue("intentional silence lost its AudioTrack", ReceiverMixer.hasOutput(output.id))

            input.resume()
            // Measure from resume immediately. A warmup here would conceal the
            // sole-output idle failure that this regression must keep catching.
            val resumed = observe(player, input, 3_000, label = "$mode-sole-after-silence")
            assertSustained("$mode after silence", resumed)
            assertEquals("resume unnecessarily rebuilt the output", before.rebuilds, player.snapshot().rebuilds)
            if (!adaptive) assertEquals("resume changed the sole source's manual target",
                targetMs, player.snapshot().targetMs)
        } finally {
            cleanup(listOf(output), listOf(input))
        }
    }

    @Test fun prolongedSilenceKeepsAdvancingWithinTheNativeTargetBudget() = runBlocking {
        requireEmulatorWindow()
        val output = player("prolonged-silence", true, 20)
        val input = PacedPcm { output.player.offer(it) }
        try {
            input.resume()
            await("long-silence output starts", input) { output.player.playedFrames > 2_400 }
            input.pauseAndAwait()
            output.player.noteSilence()
            SystemClock.sleep(500) //Initial real PCM/fade drains before the idle budget measurement.
            val before = output.player.snapshot()
            val begin = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - begin < 35_000) {
                checkHealthy(input)
                val current = output.player.snapshot()
                assertTrue("silent output exceeded native target: $current",
                    current.nativeBufferedMs <= current.targetMs + 1)
                assertEquals("silent source grew a software backlog", 0, current.queuedBytes)
                SystemClock.sleep(20)
            }
            val after = output.player.snapshot()
            assertTrue("long silence let the sink stop", after.playedFrames - before.playedFrames >= RATE * 30)
            assertEquals(before.targetMs, after.targetMs)
            assertEquals(before.rebuilds, after.rebuilds)
            input.resume()
            assertSustained("after35s silence", observe(output.player, input, 3_000, label = "after-prolonged-silence"))
        } finally { cleanup(listOf(output), listOf(input)) }
    }

    private data class Output(val id: String, val player: ReceiverPlayout)

    private fun player(label: String, adaptive: Boolean, targetMs: Int): Output {
        val id = "playout-fixture-$label-${UUID.randomUUID()}"
        val player = ReceiverPlayout(id, StreamAudioFormat(RATE, 2, 16), adaptive, targetMs) {
            failures[id] = it
        }
        return Output(id, player)
    }

    private data class Observation(val elapsedMs: Long, val frames: Long, val discardedBytes: Long,
                                   val nativeDiscardedFrames: Long, val rateCorrectionApplied: Boolean)

    private fun observe(player: ReceiverPlayout, input: PacedPcm, durationMs: Long,
                        other: ReceiverPlayout? = null, otherInput: PacedPcm? = null,
                        label: String): Observation {
        val begin = SystemClock.elapsedRealtime()
        val beginWall = System.currentTimeMillis()
        val start = player.snapshot()
        val inputStart = input.snapshot()
        Log.i("PLAYOUT_FIXTURE", "phase=$label START wallMs=$beginWall elapsedMs=$begin durationMs=$durationMs player=$start producer=$inputStart otherPlayer=${other?.snapshot()} otherProducer=${otherInput?.snapshot()}")
        var maximumBufferedMs = 0
        var maximumQueuedBytes = 0
        var rateCorrectionApplied = false
        var lastDiagnosticAt = begin
        while (SystemClock.elapsedRealtime() - begin < durationMs) {
            checkHealthy(input, otherInput)
            assertBufferBound(player)
            other?.let(::assertBufferBound)
            player.snapshot().let {
                rateCorrectionApplied = rateCorrectionApplied ||
                    (it.rateHz != RATE && it.actualRateHz == it.rateHz)
                maximumBufferedMs = maxOf(maximumBufferedMs, it.totalBufferedMs)
                maximumQueuedBytes = maxOf(maximumQueuedBytes, it.queuedBytes)
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastDiagnosticAt >= 200) {
                Log.i("PLAYOUT_FIXTURE", "phase=$label sampleMs=${now - begin} wallMs=${System.currentTimeMillis()} player=${player.snapshot()} producer=${input.snapshot()} otherPlayer=${other?.snapshot()} otherProducer=${otherInput?.snapshot()}")
                lastDiagnosticAt = now
            }
            SystemClock.sleep(20)
        }
        checkHealthy(input, otherInput)
        val end = player.snapshot()
        val observed = Observation(SystemClock.elapsedRealtime() - begin,
            end.playedFrames - start.playedFrames, end.discardedBytes - start.discardedBytes,
            end.nativeDiscardedFrames - start.nativeDiscardedFrames, rateCorrectionApplied)
        Log.i("PLAYOUT_FIXTURE", "phase=$label END wallMs=${System.currentTimeMillis()} $observed producedPackets=${input.snapshot().producedPackets - inputStart.producedPackets} maxBufferedMs=$maximumBufferedMs maxQueuedBytes=$maximumQueuedBytes end=$end producer=${input.snapshot()} otherPlayer=${other?.snapshot()} otherProducer=${otherInput?.snapshot()}")
        return observed
    }

    private fun assertBufferBound(player: ReceiverPlayout) {
        // More permissive than the intended target budget, but still excludes
        // an extra unbounded/native-capacity wait masked by playback progress.
        val snapshot = player.snapshot()
        assertTrue("native + software buffering escaped its bound: $snapshot",
            snapshot.totalBufferedMs <= snapshot.targetMs + 90)
        assertTrue("software FIFO exceeded 60 ms: $snapshot",
            snapshot.queuedBytes <= RATE * FRAME_BYTES * 60 / 1_000)
    }

    private fun assertSustained(label: String, observation: Observation) {
        assertProgress(label, observation.frames, observation.elapsedMs)
        // A local native flush must not hide audio loss behind monotonic head
        // accounting. Apply the existing bound to software and native loss.
        assertDiscardBound(label, observation.discardedBytes + observation.nativeDiscardedFrames * FRAME_BYTES)
    }

    private fun assertProgress(label: String, frames: Long, elapsedMs: Long) {
        val nominal = RATE * elapsedMs / 1_000
        assertTrue("$label consumed only $frames/$nominal frames at 750 packets/s",
            frames >= nominal * 9 / 10)
        assertTrue("$label unexpectedly accelerated playback: $frames/$nominal frames",
            frames <= nominal * 11 / 10)
    }

    private fun assertDiscardBound(label: String, bytes: Long) {
        // Allow 100 ms of isolated emulator scheduling noise per measured
        // phase. The previous one-chunk/2 ms loop loses seconds, not this tail.
        assertTrue("$label discarded sustained small-payload input: $bytes bytes",
            bytes <= RATE * FRAME_BYTES / 10)
    }

    private fun await(label: String, vararg inputs: PacedPcm, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (SystemClock.elapsedRealtime() < deadline) {
            checkHealthy(*inputs)
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail(label)
    }

    private fun checkHealthy(vararg inputs: PacedPcm?) {
        inputs.forEach { it?.checkHealthy() }
        assertTrue("Playback failed: $failures", failures.isEmpty())
    }

    private suspend fun cleanup(outputs: List<Output>, inputs: List<PacedPcm>) {
        // Signal every worker before joining any one worker, including when an
        // earlier assertion failed. None of these outputs owns saved settings.
        inputs.forEach { it.requestStop() }
        outputs.forEach { it.player.stop() }
        val producersStopped = inputs.map { it.awaitStopped() }
        val outputsStopped = outputs.map { it.player.awaitStopped() }
        assertTrue("PCM producer cleanup timed out", producersStopped.all { it })
        assertTrue("AudioTrack cleanup timed out", outputsStopped.all { it })
        assertTrue("released source still registered", outputs.none { ReceiverMixer.hasOutput(it.id) })
        assertTrue("Normal output cancellation reported a failure: $failures", failures.isEmpty())
    }

    /** Six 64-frame packets per 8 ms, paced against elapsed time rather than repeated sleeps. */
    private class PacedPcm(private val offer: (ByteArray) -> Unit) {
        private val running = AtomicBoolean(true)
        private val paused = AtomicBoolean(true)
        private val acknowledgedPause = AtomicBoolean(false)
        private val problem = AtomicReference<Throwable?>()
        private val produced = AtomicLong()
        private val maximumLagNanos = AtomicLong()
        private val epochStartedAtNanos = AtomicLong(-1)
        private val epochStartedAtWallMs = AtomicLong(-1)
        private val pcm = ByteArray(PAYLOAD_BYTES).apply {
            for (offset in indices step 2) {
                this[offset] = 0x20
                this[offset + 1] = 0x03
            }
        }
        private val worker = Thread {
            try {
                var epoch = -1L
                var epochPackets = 0L
                while (running.get()) {
                    if (paused.get()) {
                        acknowledgedPause.set(true)
                        epoch = -1L
                        LockSupport.parkNanos(500_000)
                        continue
                    }
                    acknowledgedPause.set(false)
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (epoch < 0) {
                        epoch = now; epochPackets = 0
                        epochStartedAtNanos.set(now)
                        epochStartedAtWallMs.set(System.currentTimeMillis())
                    }
                    val due = (now - epoch) * PACKETS_PER_SECOND / 1_000_000_000
                    val pending = due - epochPackets
                    val lagNanos = ((now - epoch) - epochPackets * 1_000_000_000 / PACKETS_PER_SECOND).coerceAtLeast(0)
                    noteMaximumLag(lagNanos)
                    check(pending <= 60) {
                        "Fixture producer stalled for >80 ms; pendingPackets=$pending maxLagUs=${maximumLagNanos.get() / 1_000}; cannot attribute queue loss to playout"
                    }
                    if (pending < 6) {
                        LockSupport.parkNanos(500_000)
                        continue
                    }
                    repeat(minOf(pending, 24).toInt()) {
                        if (!paused.get() && running.get()) {
                            offer(pcm.copyOf())
                            produced.incrementAndGet()
                            epochPackets++
                        }
                    }
                }
            } catch (failure: Throwable) { problem.set(failure) }
        }.apply { name = "wfas-paced-750pps"; isDaemon = true; start() }

        data class Snapshot(val producedPackets: Long, val maxLagUs: Long,
                            val epochStartNs: Long, val epochStartWallMs: Long, val paused: Boolean)

        fun snapshot() = Snapshot(produced.get(), maximumLagNanos.get() / 1_000,
            epochStartedAtNanos.get(), epochStartedAtWallMs.get(), paused.get())

        private fun noteMaximumLag(value: Long) {
            while (true) {
                val previous = maximumLagNanos.get()
                if (value <= previous || maximumLagNanos.compareAndSet(previous, value)) return
            }
        }

        fun resume() { checkHealthy(); paused.set(false); LockSupport.unpark(worker) }
        fun pauseAndAwait() {
            paused.set(true)
            val deadline = SystemClock.elapsedRealtime() + 1_000
            while (!acknowledgedPause.get() && SystemClock.elapsedRealtime() < deadline) {
                checkHealthy(); SystemClock.sleep(1)
            }
            check(acknowledgedPause.get()) { "Fixture producer did not acknowledge pause" }
        }
        fun checkHealthy() {
            problem.get()?.let { throw AssertionError("Paced PCM producer failed: ${snapshot()}", it) }
        }
        fun requestStop() { running.set(false); LockSupport.unpark(worker) }
        fun awaitStopped(): Boolean { worker.join(1_000); return !worker.isAlive }
    }

    private companion object {
        const val RATE = 48_000
        const val FRAME_BYTES = 4
        const val PAYLOAD_BYTES = 256
        const val PACKETS_PER_SECOND = 750L
    }
}
