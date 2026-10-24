import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.SourceDataLine

/** Fault and continuity checks ported from Android test21, with a Windows output boundary. */
internal object DesktopPlayoutRegressionChecks {
    fun runChecks() {
        smallPacketPumpAndPreemptedContinuation()
        partialWritesAndConcurrentEviction()
        silenceBudgetAndClockNeutrality()
        actualProgressSurvivesRebuild()
        softNudgeBeforeRebuild()
        softNudgePreservesNativePcmAndSurfacesFailure()
        blockedNativeCloseKeepsOwnership()
        failedReleaseDoesNotCompleteCleanup()
        actualPlayerGainUsesSoftKnee()
        largeManualTargetStartsWithinBoundedFifo()
        silenceResumeNeverRepaysPaddingByDroppingRealPcm()
        println("test21 Windows PCM queue, bounded pump, silence/resume, drift, real progress and 400ms startup passed")
    }

    private fun smallPacketPumpAndPreemptedContinuation() {
        val queue = PcmQueue(4, 11_520)
        var native = 480L
        var received = 0
        val pump = PcmDrainPump(queue, 48_000, 4) { 0 }
        repeat(125) {
            native = (native - 384).coerceAtLeast(0)
            repeat(6) { queue.offer(ByteArray(256)) }
            val result = pump.drain(20, { native }) { _, _, length ->
                received += length; native += length / 4; length
            }
            check(result.writes == 6 && !result.blocked && queue.bytes() == 0)
        }
        check(received == 192_000 && queue.discardedBytes == 0L)

        val original = ByteArray(11_008) { (it * 31).toByte() }
        original.asList().chunked(256).forEach { queue.offer(it.toByteArray()) }
        native = 0
        var clock = 0L
        val timed = PcmDrainPump(queue, 48_000, 4) { clock }
        val accepted = ByteArrayOutputStream()
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val first = timed.drain(20, { native }) { pcm, offset, length ->
            accepted.write(pcm, offset, length); native += length / 4
            clock += 42_000_000; length
        }
        check(first.limited && first.writes == 1 && queue.bytes() == 10_752)
        check(policy.softwareLimit(42, queue.bytes(), first.limited) == null)
        var iterations = 0
        while (queue.bytes() > 0) {
            val result = pump.drain(20, { native }) { pcm, offset, length ->
                accepted.write(pcm, offset, length); native += length / 4
                check(native <= 1_680); length
            }
            check(policy.softwareLimit(clock / 1_000_000, queue.bytes(), result.limited) == null)
            if (result.blocked) { native = (native - 960).coerceAtLeast(0); clock += 20_000_000 }
            check(++iterations < 10)
        }
        check(accepted.toByteArray().contentEquals(original) && queue.discardedBytes == 0L)

        // Packet duration need not divide 60ms. Exact prefix eviction keeps a
        // reachable bounded startup threshold rather than oscillating at40ms.
        val uneven = PcmQueue(4, 11_520)
        repeat(2) { uneven.offer(ByteArray(7_680)) }
        check(uneven.bytes() == 11_520 && uneven.discardedBytes == 3_840L)
    }

    private fun partialWritesAndConcurrentEviction() {
        val queue = PcmQueue(4, 64)
        val original = constant(4)
        queue.offer(original)
        val ramp = PcmRamp(2, 4).apply { restart() }
        val heard = ByteArrayOutputStream()
        for (accept in listOf(0, 4, 0, 8, 4)) queue.drain { pcm, offset, length ->
            val copy = pcm.copyOfRange(offset, offset + length)
            ramp.applyInPlace(copy, 0, copy.size)
            val count = minOf(accept, length)
            heard.write(copy, 0, count); ramp.acceptedBytes(count); count
        }
        val samples = ByteBuffer.wrap(heard.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        check((0 until 4).map { samples[it * 2].toInt() } == listOf(3_000, 6_000, 9_000, 12_000))
        check(original.contentEquals(constant(4)) && queue.discardedBytes == 0L)

        val inFlight = PcmQueue(4, 16).apply { offer(ByteArray(16)) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var problem: Throwable? = null
        val writer = Thread {
            try {
                inFlight.drain { _, _, _ -> entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); 8 }
            } catch (failure: Throwable) { problem = failure }
        }.apply { start() }
        check(entered.await(2, TimeUnit.SECONDS))
        inFlight.offer(ByteArray(16) { 9 })
        check(inFlight.bytes() == 16 && inFlight.discardedBytes == 0L)
        release.countDown(); writer.join(2000)
        check(!writer.isAlive && problem == null)
        check(inFlight.bytes() == 16 && inFlight.discardedBytes == 8L)
        inFlight.drain { pcm, offset, length -> check(pcm[offset] == 9.toByte()); length }
    }

    private fun silenceBudgetAndClockNeutrality() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        var native = 0L
        var consumed = 0L
        repeat(7_000) {
            val played = minOf(native, 240)
            native -= played; consumed += played
            var calls = 0
            feed.refill(20, { native }) { _, length ->
                check(++calls <= 2 && length <= 1920)
                native += length / 4; length
            }
            check(native <= 960)
        }
        check(consumed >= 48_000 * 34L)
        val drift = DesktopPlayoutDrift(48_000)
        repeat(100) { drift.sample(true, 20_000, 960) }
        check(drift.ratio > 1)
        check(drift.sample(false, 0, 960) == 1.0)
        val resampler = DesktopPcmDriftResampler(2).apply { ratio = 1.005 }
        resampler.process(constant(100))
        resampler.reset()
        val resumed = constant(2)
        check(resampler.process(resumed).contentEquals(resumed))
        val health = PlaybackHealth(true, 20)
        repeat(10) { health.sample(it * 1000L, it * 300L, false, 0, 0, true) }
        check(health.targetMs == 20 && !health.rebuildRequested)
        health.sample(11_000, 2700, false, 6000, 0, true)
        check(health.rebuildRequested)
    }

    private fun actualProgressSurvivesRebuild() {
        class Device : PlaybackOutput {
            override val capacityBytes = 4096
            var position = 0L
            var queued = 0
            var closed = false
            override fun available() = capacityBytes - queued
            override fun framePosition() = position
            override fun start() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int { queued += length; return length }
            override fun close() { closed = true }
        }
        val first = Device()
        val next = Device()
        var clock = 0L
        val output = RecoverableAudioOutput(first, 4, { next }, clockMs = { clock })
        output.start()
        output.writeSome(ByteArray(400), 0, 400)
        first.position = 50; first.queued = 200
        output.poll()
        check(output.playedFrames() == 50L)
        // Stale queued PCM, with no further write admission, must still recover.
        clock = 6000
        output.poll()
        check(first.closed && output.rebuilds == 1 && output.discardedNativeBytes == 200L)
        output.writeSome(ByteArray(200), 0, 200)
        next.position = 10; next.queued = 160; output.poll()
        check(output.playedFrames() == 60L) { "Submitted or flushed PCM was counted as played" }
        output.close()
    }

    private fun softNudgeBeforeRebuild() {
        class Device : PlaybackOutput {
            override val capacityBytes = 4096
            var position = 0L
            var queued = 0
            var nudges = 0
            override fun available() = capacityBytes - queued
            override fun framePosition() = position
            override fun start() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int { queued += length; return length }
            override fun close() {}
            override fun nudge() { nudges++ }
        }
        val device = Device()
        val replacement = Device()
        var built = 0
        var clock = 0L
        val output = RecoverableAudioOutput(device, 4, { built++; replacement }, clockMs = { clock })
        output.start()
        output.writeSome(ByteArray(400), 0, 400)

        // Half a second without progress is a wedged line, not a dead one: the
        // cheap restart runs and the rebuild budget stays untouched.
        clock = 600
        output.poll()
        check(device.nudges == 1 && built == 0) { "A 500ms stall must nudge, not rebuild" }

        // The nudge is rate limited, so a line that ignores it is not hammered.
        clock = 900
        output.poll()
        check(device.nudges == 1) { "Nudges must stay one per soft window" }
        clock = 1_200
        output.poll()
        check(device.nudges == 2)

        // Only the full stall window may rebuild.
        clock = 6_000
        output.poll()
        check(built == 1 && output.rebuilds == 1) { "Five seconds without progress must rebuild" }
        output.close()
    }

    private fun softNudgePreservesNativePcmAndSurfacesFailure() {
        class NativeLine {
            val accepted = ByteArrayOutputStream()
            var queued = 0
            var position = 0L
            var starts = 0
            var stops = 0
            var flushes = 0
            var closed = false
            var failStart = false
            val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
                arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
                when (method.name) {
                    "getBufferSize" -> 4096
                    "available" -> 4096 - queued
                    "getLongFramePosition" -> position
                    "start" -> { check(!failStart) { "injected restart failure" }; starts++; null }
                    "stop" -> { stops++; null }
                    "flush" -> { flushes++; queued = 0; null }
                    "write" -> {
                        val count = args!![2] as Int
                        accepted.write(args[0] as ByteArray, args[1] as Int, count)
                        queued += count; count
                    }
                    "close" -> { closed = true; null }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            } as SourceDataLine
        }
        val native = NativeLine()
        var now = 0L
        val output = RecoverableAudioOutput(JavaSoundPlaybackOutput(native.line), 4, clockMs = { now })
        val pcm = constant(100)
        output.start()
        check(output.writeSome(pcm, 0, pcm.size) == pcm.size)
        now = 600
        output.poll()
        check(native.starts == 2 && native.stops == 0 && native.flushes == 0 && native.queued == pcm.size) {
            "Soft restart discarded already accepted native PCM"
        }
        check(native.accepted.toByteArray().contentEquals(pcm) && output.playedFrames() == 0L)
        native.position = 100; native.queued = 0
        output.poll()
        check(output.playedFrames() == 100L && output.discardedNativeBytes == 0L)
        output.close()
        check(native.closed && output.isCleanupComplete())

        val broken = NativeLine()
        val replacement = NativeLine()
        now = 0L
        val recovered = RecoverableAudioOutput(JavaSoundPlaybackOutput(broken.line), 4,
            factory = { JavaSoundPlaybackOutput(replacement.line) }, clockMs = { now })
        recovered.start(); recovered.writeSome(pcm, 0, pcm.size)
        broken.failStart = true; now = 600
        recovered.poll()
        check(broken.closed && recovered.rebuilds == 1 && recovered.discardedNativeBytes == pcm.size.toLong()) {
            "A failed soft restart must trigger explicit, accounted recovery"
        }
        recovered.close()
    }

    private fun blockedNativeCloseKeepsOwnership() {
        val fixture = LineFixture(blockClose = true)
        val player = DesktopJitterAudioPlayer(fixture.line, 48_000, 2)
        player.start()
        try {
            player.stop()
            check(fixture.closeEntered.await(1, TimeUnit.SECONDS))
            check(!player.isCleanupComplete() && !player.awaitStopped(20)) {
                "The source slot was released before native close actually returned"
            }
        } finally { fixture.closeRelease.countDown() }
        check(player.awaitStopped(2_000) && fixture.closed) { "Native cleanup completion was never observed" }
    }

    private fun failedReleaseDoesNotCompleteCleanup() {
        val broken = object : PlaybackOutput {
            override val capacityBytes = 4096
            override fun available() = capacityBytes
            override fun framePosition() = 0L
            override fun start() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int) = length
            override fun close() { error("injected native release failure") }
        }
        val output = RecoverableAudioOutput(broken, 4, factory = { broken })
        check(runCatching { output.requestRecovery("injected failure") }.exceptionOrNull() is PlaybackOutputFailure)
        // recover() already transferred the reference before close failed.
        // The subsequent worker-finally close must not turn that into success.
        output.close()
        check(!output.isCleanupComplete()) { "A second no-op close concealed the failed native release" }
    }

    private fun actualPlayerGainUsesSoftKnee() {
        val values = intArrayOf(-32768, -30000, -20001, -10001, -1, 0, 1, 10001, 20001, 30000, 32767)
        val pcm = ByteArray(480 * 4).also { bytes ->
            val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            repeat(samples.limit()) { samples.put(values[it % values.size].toShort()) }
        }
        for (gain in listOf(0.5f, 1f, 2f)) {
            val expected = pcm.copyOf()
            if (gain > 1f) DesktopGainLimiter.applyPcm16(expected, 0, expected.size, gain)
            else if (gain < 1f) {
                val samples = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                for (index in 0 until samples.limit()) samples.put(index, (samples[index] * gain).toInt().toShort())
            }
            val fixture = LineFixture()
            val player = DesktopJitterAudioPlayer(fixture.line, 48_000, 2,
                health = PlaybackHealth(false, 20), currentVolume = { gain })
            player.start()
            try {
                player.submit(pcm, 0, pcm.size)
                await("Player did not write the gain-$gain fixture") { fixture.recordedBytes() >= expected.size }
                check(fixture.recordedPcm().contentEquals(expected)) {
                    "Real player gain-$gain output did not match the intended PCM curve"
                }
                player.throwIfFailed()
            } finally { player.stop() }
            check(player.awaitStopped(2_000))
        }
    }

    private fun largeManualTargetStartsWithinBoundedFifo() {
        val fixture = LineFixture()
        val player = DesktopJitterAudioPlayer(fixture.line, 48_000, 2, health = PlaybackHealth(false, 400))
        player.start()
        try {
            // Ordinary345-frame packets cross a60ms hard ceiling. Start with
            // room left for the next packet instead of evicting the first one.
            val pcm = constant(345)
            repeat(10) { player.submit(pcm, 0, pcm.size); Thread.sleep(10) }
            val leastOutput = (3450 / 1.008).toInt() - 1 // Worst allowed resampling plus the carried frame.
            await("400ms manual target never started") { fixture.nonzeroFrames() >= leastOutput }
            check(player.snapshot().softwareBytes <= 11_520)
            check(player.snapshot().discardedBytes == 0L) { "Startup evicted the first PCM packet" }
            check(player.snapshot().targetMs == 400)
            player.throwIfFailed()
        } finally { player.stop() }
        check(fixture.closed)
    }

    private fun silenceResumeNeverRepaysPaddingByDroppingRealPcm() {
        val fixture = LineFixture(maxWrite = 12)
        val player = DesktopJitterAudioPlayer(fixture.line, 48_000, 2, health = PlaybackHealth(false, 20))
        player.start()
        try {
            val initial = constant(480)
            player.submit(initial, 0, initial.size)
            try {
                await("Initial PCM did not drain") { fixture.nonzeroFrames() >= 480 }
            } catch (timeout: IllegalStateException) {
                // Surface the worker's own failure instead of only the wait timeout.
                player.throwIfFailed()
                println("[diag] snapshot=${player.snapshot()} recorded=${fixture.recordedBytes()} nonzero=${fixture.nonzeroFrames()}")
                throw timeout
            }
            player.noteAudioFlow(false)
            await("Idle buffer did not start") { player.snapshot().softwareBytes == 0 }
            Thread.sleep(80)
            fixture.clearRecorded()
            val resumed = constant(480)
            player.submit(resumed, 0, resumed.size)
            await("Silence padding swallowed returning real PCM") { fixture.nonzeroFrames() >= 480 }
            val values = fixture.nonzeroLeftSamples()
            check(values.take(240).zipWithNext().all { (before, after) -> after >= before })
            check(values.first() == 50 && values[239] == 12_000)
            check(values.size == 480) { "A partial native write lost or duplicated returning PCM: ${values.size}" }
            player.throwIfFailed()
        } finally { player.stop() }
        check(fixture.closed)
    }

    private fun constant(frames: Int) = ByteArray(frames * 4).also { pcm ->
        val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(frames * 2) { shorts.put(12_000.toShort()) }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(2)
        check(predicate()) { message }
    }

    /** A partial-write JavaSound boundary; tests do not open or alter a real audio device. */
    private class LineFixture(private val maxWrite: Int = Int.MAX_VALUE, private val blockClose: Boolean = false) {
        private val captured = ByteArrayOutputStream()
        private var frames = 0L
        val closeEntered = CountDownLatch(1)
        val closeRelease = CountDownLatch(1)
        @Volatile var closed = false
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize", "available" -> 96_000
                "getLongFramePosition" -> synchronized(captured) { frames }
                "write" -> synchronized(captured) {
                    check(!closed)
                    val count = minOf(args!![2] as Int, maxWrite)
                    captured.write(args[0] as ByteArray, args[1] as Int, count)
                    frames += count / 4
                    count
                }
                "close" -> {
                    closeEntered.countDown()
                    if (blockClose) {
                        var interrupted = false
                        while (true) {
                            try { closeRelease.await(); break }
                            catch (_: InterruptedException) { interrupted = true }
                        }
                        if (interrupted) Thread.currentThread().interrupt()
                    }
                    closed = true; null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "test21 JavaSound fixture"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as SourceDataLine
        fun nonzeroLeftSamples(): List<Int> = synchronized(captured) {
            val pcm = captured.toByteArray()
            (pcm.indices step 4).map {
                ((pcm[it].toInt() and 255) or (pcm[it + 1].toInt() shl 8)).toShort().toInt()
            }.filter { it != 0 }
        }
        fun nonzeroFrames() = nonzeroLeftSamples().size
        fun recordedBytes() = synchronized(captured) { captured.size() }
        fun recordedPcm() = synchronized(captured) { captured.toByteArray() }
        fun clearRecorded() = synchronized(captured) { captured.reset() }
    }
}
