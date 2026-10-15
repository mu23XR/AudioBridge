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
            val single = observe(first, firstInput, 6_000)
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
            val both = observe(first, firstInput, 6_000, second, secondInput)
            assertSustained("first of two sources", both)
            assertProgress("second source", second.playedFrames - secondAtTwo, both.elapsedMs)
            assertDiscardBound("second source", second.snapshot().discardedBytes - secondDiscardAtTwo)
            assertTrue("first source stopped when another output was added", first.playedFrames > firstAtTwo)
            assertDiscardBound("first of two sources", first.snapshot().discardedBytes - firstDiscardAtTwo)
            assertEquals("adding a source changed the first target", 45, first.targetMs)
            assertEquals("second source lost its own manual target", 73, second.targetMs)

            firstInput.pauseAndAwait()
            first.noteSilence()
            val secondDuringPause = second.playedFrames
            observe(second, secondInput, 3_000)
            assertTrue("a silent/absent peer became a common playback barrier",
                second.playedFrames - secondDuringPause > RATE * 2)
            assertTrue("pause released the first source's output", ReceiverMixer.hasOutput(firstOutput.id))
            firstInput.resume()
            val resumed = observe(first, firstInput, 3_000, second, secondInput)
            assertProgress("resumed first source", resumed.frames, resumed.elapsedMs)
            assertEquals(45, first.targetMs)
            assertEquals(73, second.targetMs)
        } finally {
            cleanup(listOf(firstOutput, secondOutput), listOf(firstInput, secondInput))
        }
    }

    @Test fun intentionalSilenceDoesNotRaiseAdaptiveTargetOrRebuildOutput() = runBlocking {
        requireEmulatorWindow()
        val output = player("silence", true, 20)
        val player = output.player
        val input = PacedPcm { player.offer(it) }
        try {
            input.resume()
            await("adaptive output starts", input) { player.playedFrames > 2_400 }
            assertSustained("adaptive before silence", observe(player, input, 3_000))
            input.pauseAndAwait()
            player.noteSilence()
            val before = player.snapshot()
            observe(player, input, 4_500)
            val silent = player.snapshot()
            assertEquals("intentional silence raised the adaptive target", before.targetMs, silent.targetMs)
            assertEquals("intentional silence rebuilt the output", before.rebuilds, silent.rebuilds)
            assertTrue("intentional silence lost its AudioTrack", ReceiverMixer.hasOutput(output.id))

            input.resume()
            val resumed = observe(player, input, 3_000)
            assertSustained("adaptive after silence", resumed)
            assertEquals("resume unnecessarily rebuilt the output", before.rebuilds, player.snapshot().rebuilds)
        } finally {
            cleanup(listOf(output), listOf(input))
        }
    }

    private data class Output(val id: String, val player: ReceiverPlayout)

    private fun player(label: String, adaptive: Boolean, targetMs: Int): Output {
        val id = "playout-fixture-$label-${UUID.randomUUID()}"
        val player = ReceiverPlayout(id, StreamAudioFormat(RATE, 2, 16), adaptive, targetMs) {
            failures[id] = it
        }
        return Output(id, player)
    }

    private data class Observation(val elapsedMs: Long, val frames: Long, val discardedBytes: Long)

    private fun observe(player: ReceiverPlayout, input: PacedPcm, durationMs: Long,
                        other: ReceiverPlayout? = null, otherInput: PacedPcm? = null): Observation {
        val begin = SystemClock.elapsedRealtime()
        val start = player.snapshot()
        var maximumBufferedMs = 0
        var maximumQueuedBytes = 0
        while (SystemClock.elapsedRealtime() - begin < durationMs) {
            checkHealthy(input, otherInput)
            assertBufferBound(player)
            other?.let(::assertBufferBound)
            player.snapshot().let {
                maximumBufferedMs = maxOf(maximumBufferedMs, it.totalBufferedMs)
                maximumQueuedBytes = maxOf(maximumQueuedBytes, it.queuedBytes)
            }
            SystemClock.sleep(20)
        }
        checkHealthy(input, otherInput)
        val end = player.snapshot()
        val observed = Observation(SystemClock.elapsedRealtime() - begin,
            end.playedFrames - start.playedFrames, end.discardedBytes - start.discardedBytes)
        Log.i("PLAYOUT_FIXTURE", "$observed maxBufferedMs=$maximumBufferedMs maxQueuedBytes=$maximumQueuedBytes end=$end")
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
        assertDiscardBound(label, observation.discardedBytes)
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
    }

    /** Six 64-frame packets per 8 ms, paced against elapsed time rather than repeated sleeps. */
    private class PacedPcm(private val offer: (ByteArray) -> Unit) {
        private val running = AtomicBoolean(true)
        private val paused = AtomicBoolean(true)
        private val acknowledgedPause = AtomicBoolean(false)
        private val problem = AtomicReference<Throwable?>()
        private val produced = AtomicLong()
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
                    if (epoch < 0) { epoch = now; epochPackets = 0 }
                    val due = (now - epoch) * PACKETS_PER_SECOND / 1_000_000_000
                    val pending = due - epochPackets
                    check(pending <= 60) { "Fixture producer stalled for >80 ms; cannot attribute queue loss to playout" }
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
            problem.get()?.let { throw AssertionError("Paced PCM producer failed after ${produced.get()} packets", it) }
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
