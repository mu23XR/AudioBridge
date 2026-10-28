import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise raw-input rendering and actual pump admission without opening a device. */
internal object DesktopPcmRenderStageChecks {
    fun runChecks() {
        queuedInputUsesTheConsumersNewRatio()
        partialAndZeroWritesRetainTheRenderedTail()
        nativeWriteDoesNotOwnTheProducerLock()
        aRenderedTailPrecedesTheNextDiscontinuity()
        oneFrameWithoutOutputRetainsItsDiscontinuity()
        unusualRatesKeepCompleteFrames()
        println("Render stage: consumer ratio, partial/zero acceptance, producer lock, carried discontinuities and frame-aligned rates passed")
    }

    @JvmStatic fun main(args: Array<String>) = runChecks()

    private fun unusualRatesKeepCompleteFrames() {
        for (rate in listOf(4_001, 4_013, 44_101, 191_999)) for (channels in 1..2) {
            val frameBytes = channels * 2
            val input = PcmQueue(frameBytes, rate * frameBytes)
            input.offer(ByteArray(rate / 10 * frameBytes))
            val stage = PcmRenderStage(input, rate, channels,
                DesktopPcmDriftResampler(channels).apply { ratio = .992 }, Any())
            check(stage.renderNext())
            check(stage.output.bytes() > 0 && stage.output.bytes() % frameBytes == 0)
            check(input.bytes() == (rate / 10 - rate / 50) * frameBytes)
            check(stage.discardedBytes() == 0L)
        }
    }

    private class Fixture(ratio: Double = 1.0) {
        val input = PcmQueue(4, 192_000)
        val resampler = DesktopPcmDriftResampler(2).apply { this.ratio = ratio }
        val producer = Any()
        val stage = PcmRenderStage(input, 48_000, 2, resampler, producer)
        val pump = PcmDrainPump(stage.output, 48_000, 4,
            refill = stage::renderNext, hasInput = stage::hasInput, nowNanos = { 0 })
    }

    private fun queuedInputUsesTheConsumersNewRatio() {
        val fixture = Fixture()
        val raw = pcm(4_800, 0)
        fixture.input.offer(raw)
        check(fixture.stage.renderNext())
        check(fixture.input.bytes() == (4_800 - 960) * 4)
        val first = ByteArrayOutputStream()
        fixture.stage.output.drain(12) { pcm, at, length -> first.write(pcm, at, length); length }
        val remainingRaw = fixture.input.bytes()
        synchronized(fixture.producer) { fixture.resampler.ratio = .992 }
        check(fixture.stage.renderNext())
        check(fixture.input.bytes() == remainingRaw) { "A rendered partial tail was replaced by a new ratio" }
        takeAll(fixture.stage.output).also(first::write)
        check(first.toByteArray().contentEquals(raw.copyOfRange(0, 960 * 4)))

        // This input arrived before the ratio change. It must still be rendered
        // at the new consumer rate, rather than frozen at offer()/receive time.
        check(fixture.stage.renderNext())
        val slower = takeAll(fixture.stage.output)
        check(slower.size / 4 == 967) { "Queued raw tail ignored the new consumer ratio: ${slower.size / 4}" }
        check(left(slower, 0) == 960 && left(slower, 966) == 1_918)
        check(fixture.input.bytes() == (4_800 - 1_920) * 4)
        check(fixture.stage.discardedBytes() == 0L)
    }

    private fun partialAndZeroWritesRetainTheRenderedTail() {
        fun prepared(): Fixture = Fixture(.996).also { fixture ->
            var frame = 0
            for (count in listOf(1, 2, 168, 73, 959, 15, 960, 3)) {
                fixture.input.offer(pcm(count, frame))
                frame += count
            }
        }
        val complete = prepared()
        val expected = drainAll(complete) { requested -> requested }
        val fragmented = prepared()
        val quotas = intArrayOf(0, 4, 0, 12, 8, 0, 36, 4, 20)
        var call = 0
        var zeros = 0
        var partials = 0
        val actual = drainAll(fragmented) { requested ->
            minOf(requested, quotas[call++ % quotas.size]).also {
                if (it == 0) zeros++ else if (it < requested) partials++
            }
        }
        check(zeros > 0 && partials > 0)
        check(actual.contentEquals(expected)) { "Write fragmentation lost, repeated or re-rendered accepted PCM" }
        check(fragmented.stage.bytes() == 0 && fragmented.stage.discardedBytes() == 0L)
    }

    private fun nativeWriteDoesNotOwnTheProducerLock() {
        val fixture = Fixture()
        val first = pcm(168, 0)
        val second = pcm(168, 168)
        fixture.input.offer(first)
        val produced = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val heard = ByteArrayOutputStream()
        var producerThread: Thread? = null
        var firstWrite = true
        try {
            val result = fixture.pump.drain(400, { 0L }) { bytes, at, length ->
                check(firstWrite)
                firstWrite = false
                producerThread = Thread({
                    try { synchronized(fixture.producer) { fixture.input.offer(second) } }
                    catch (problem: Throwable) { failure.set(problem) }
                    finally { produced.countDown() }
                }, "render-stage-producer-check").apply { start() }
                check(produced.await(2, TimeUnit.SECONDS)) { "Native write kept the receive/producer lock" }
                failure.get()?.let { throw AssertionError("Producer failed during native write", it) }
                val accepted = minOf(4, length)
                heard.write(bytes, at, accepted)
                accepted
            }
            check(result.blocked && produced.count == 0L)
            heard.write(drainAll(fixture) { requested -> requested })
            check(heard.toByteArray().contentEquals(first + second))
            check(fixture.stage.discardedBytes() == 0L)
        } finally {
            producerThread?.join(2_000)
            check(producerThread?.isAlive != true) { "Producer fixture did not finish" }
        }
    }

    private fun aRenderedTailPrecedesTheNextDiscontinuity() {
        val fixture = Fixture(1.008)
        fixture.input.offer(pcm(4, 1_000))
        fixture.input.offer(pcm(4, 2_000), boundary = true)
        check(fixture.stage.renderNext())
        check(!fixture.stage.output.takeDiscontinuity())
        val first = ByteArrayOutputStream()
        fixture.stage.output.drain(4) { bytes, at, length -> first.write(bytes, at, length); length }
        val pendingInput = fixture.input.bytes()
        check(fixture.stage.renderNext())
        check(fixture.input.bytes() == pendingInput) { "Next boundary overtook an unaccepted rendered tail" }
        first.write(takeAll(fixture.stage.output))
        check((0 until first.size() / 4).all { left(first.toByteArray(), it) in 1_000..1_003 })
        check(fixture.stage.renderNext())
        check(fixture.stage.output.takeDiscontinuity())
        val next = takeAll(fixture.stage.output)
        check(left(next, 0) == 2_000) { "A discontinuity interpolated from the preceding source tail" }
        check(fixture.stage.discardedBytes() == 0L)
    }

    private fun oneFrameWithoutOutputRetainsItsDiscontinuity() {
        for (ratio in listOf(.992, 1.008)) {
            val fixture = Fixture(ratio)
            fixture.input.offer(pcm(1, 12_000), boundary = true)
            fixture.input.offer(pcm(1, 12_001))
            check(fixture.stage.renderNext())
            check(fixture.stage.output.bytes() > 0)
            check(fixture.stage.output.takeDiscontinuity()) {
                "A zero-output one-frame boundary was lost before the first rendered frame (ratio=$ratio)"
            }
            check(!fixture.stage.output.takeDiscontinuity()) { "A boundary was emitted more than once" }
            val output = takeAll(fixture.stage.output)
            check(left(output, 0) == 12_000 && fixture.stage.discardedBytes() == 0L)
        }
    }

    private fun drainAll(fixture: Fixture, acceptedBytes: (Int) -> Int): ByteArray {
        val heard = ByteArrayOutputStream()
        var iterations = 0
        while (fixture.stage.bytes() > 0) {
            check(++iterations <= 5_000) { "PCM render fixture did not drain" }
            fixture.pump.drain(400, { 0L }) { bytes, at, length ->
                val count = acceptedBytes(length)
                check(count in 0..length && count % 4 == 0)
                heard.write(bytes, at, count)
                count
            }
        }
        return heard.toByteArray()
    }

    private fun takeAll(queue: PcmQueue): ByteArray = ByteArrayOutputStream().also { output ->
        while (queue.bytes() > 0) queue.drain { bytes, at, length -> output.write(bytes, at, length); length }
    }.toByteArray()

    private fun pcm(frames: Int, start: Int): ByteArray = ByteArray(frames * 4).also { bytes ->
        for (frame in 0 until frames) for (channel in 0..1) {
            val sample = if (channel == 0) start + frame else -(start + frame)
            val at = frame * 4 + channel * 2
            bytes[at] = sample.toByte(); bytes[at + 1] = (sample shr 8).toByte()
        }
    }

    private fun left(bytes: ByteArray, frame: Int): Int {
        val at = frame * 4
        return ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
    }
}
