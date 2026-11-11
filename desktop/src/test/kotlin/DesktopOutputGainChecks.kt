import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.SourceDataLine

/** Live gain acts on queued PCM at output admission, without changing FIFO ownership. */
internal object DesktopOutputGainChecks {
    fun runChecks() {
        queuedRawPcmUsesMuteBeforeItsFirstWrite()
        queuedRenderedAndRawPcmUseGainAfterPartialAndZeroWrites()
        unequalSourceBacklogsUseTheNewMixWeights()
        fragmentedWritesMatchWholeWritesAcrossGainChanges()
        failedWriteAndReplacementDoNotAdvanceTheGain()
        staticGainKeepsTheExistingLimiterAndQuantization()
        println("Output gain: queued raw/rendered PCM, mute/mix ramps, partial/zero/error writes and soft knee passed")
    }

    @JvmStatic fun main(args: Array<String>) = runChecks()

    private fun queuedRawPcmUsesMuteBeforeItsFirstWrite() {
        val volume = AtomicReference(1f)
        val fixture = QueuedLine()
        val player = player(fixture, volume)
        val original = pcm(4_800)
        player.start()
        try {
            player.submit(original, 0, original.size)
            await(player, "Raw PCM was not queued behind the unavailable device") {
                player.snapshot().softwareBytes == original.size && fixture.frames() == 0
            }
            volume.set(0f)
            fixture.allow()
            await(player, "Muted queued PCM did not drain") { player.snapshot().softwareBytes == 0 }
            check(fixture.captured().isNotEmpty() && fixture.captured().all { it == 0.toByte() }) {
                "Mute left old gain baked into PCM queued before its first native write"
            }
            check(player.snapshot().discardedBytes == 0L && player.snapshot().nativeDiscardedBytes == 0L)
            check(original.contentEquals(pcm(4_800))) { "Output gain changed the caller's PCM" }
        } finally { stop(player) }
    }

    private fun queuedRenderedAndRawPcmUseGainAfterPartialAndZeroWrites() {
        val volume = AtomicReference(1f)
        val fixture = QueuedLine(intArrayOf(0, 1, 0, 3, 2, 0, 9, 16))
        val player = player(fixture, volume)
        val original = pcm(4_800)
        fixture.allow(1)
        player.start()
        try {
            player.submit(original, 0, original.size)
            await(player, "A partial write did not leave both render and raw tails queued") {
                fixture.frames() == 1 && player.snapshot().softwareBytes >= 4_000 * 4
            }
            volume.set(0f)
            fixture.allow()
            await(player, "Partially accepted queued PCM did not drain after mute") {
                player.snapshot().softwareBytes == 0
            }
            val heard = fixture.captured()
            check(left(heard, 0) == 12_000)
            val transition = (1..240).map { left(heard, it) }
            check(transition.first() in 11_949..11_951 && transition.last() == 0 &&
                transition.zipWithNext().all { (before, after) -> after <= before }) {
                "Queued render/raw PCM ignored the mute ramp or retried an already scaled tail"
            }
            check((241 until heard.size / 4).all { left(heard, it) == 0 }) {
                "An old-gain software tail played after the 5ms accepted-frame transition"
            }
            check(fixture.zeroWrites > 0 && fixture.partialWrites > 0)
            check(player.snapshot().discardedBytes == 0L && player.snapshot().nativeDiscardedBytes == 0L)
            check(original.contentEquals(pcm(4_800)))
        } finally { stop(player) }
    }

    private fun unequalSourceBacklogsUseTheNewMixWeights() {
        val firstGain = AtomicReference(.5f)
        val secondGain = AtomicReference(.5f)
        val first = QueuedLine()
        val second = QueuedLine()
        val firstPlayer = player(first, firstGain)
        val secondPlayer = player(second, secondGain)
        first.allow(1); second.allow(1)
        firstPlayer.start(); secondPlayer.start()
        try {
            firstPlayer.submit(pcm(4_800), 0, 4_800 * 4)
            secondPlayer.submit(pcm(14_400), 0, 14_400 * 4)
            await(firstPlayer, "First source did not retain its queued tail") {
                first.frames() == 1 && firstPlayer.snapshot().softwareBytes > 0
            }
            await(secondPlayer, "Second source did not retain its longer queued tail") {
                second.frames() == 1 && secondPlayer.snapshot().softwareBytes > 0
            }
            // These normalized targets replace .5 + .5 with 0 + 1. They must
            // affect both already queued streams, independently of arrival age.
            firstGain.set(0f); secondGain.set(1f)
            first.allow(); second.allow()
            await(firstPlayer, "First queued source did not drain") { firstPlayer.snapshot().softwareBytes == 0 }
            await(secondPlayer, "Second queued source did not drain") { secondPlayer.snapshot().softwareBytes == 0 }
            val a = first.captured()
            val b = second.captured()
            for (frame in 1..240) {
                check(left(a, frame) + left(b, frame) in 11_998..12_001) {
                    "Exchanged source weights changed the normalized paired-frame budget at $frame"
                }
            }
            check(left(a, 241) == 0 && left(b, 241) == 12_000) {
                "Unequal software backlogs retained the old mix weights after the output ramp"
            }
            check(firstPlayer.snapshot().discardedBytes == 0L && secondPlayer.snapshot().discardedBytes == 0L)
            // Native admission/scheduling is independent for each source. This
            // paired-frame check cannot establish the OS mixer's acoustic peak.
        } finally { stop(firstPlayer); stop(secondPlayer) }
    }

    private fun fragmentedWritesMatchWholeWritesAcrossGainChanges() {
        fun render(quotas: IntArray): ByteArray {
            val original = pcm(1_100, varying = true)
            val queue = PcmQueue(4, original.size)
            queue.offer(original)
            val gain = PcmOutputGain(2, 240)
            val heard = ByteArrayOutputStream()
            val changes = listOf(0 to .5f, 100 to 1f, 340 to 0f, 610 to 2f, 777 to .8f, 1_100 to .8f)
            var acceptedFrames = 0
            var call = 0
            for (index in 0 until changes.lastIndex) {
                val (start, target) = changes[index]
                check(acceptedFrames == start)
                val end = changes[index + 1].first
                while (acceptedFrames < end) {
                    queue.drain((end - acceptedFrames) * 4) { bytes, at, count ->
                        val copy = bytes.copyOfRange(at, at + count)
                        gain.applyInPlace(copy, 0, count, target)
                        val accepted = minOf(count, quotas[call++ % quotas.size] * 4)
                        heard.write(copy, 0, accepted)
                        gain.acceptedBytes(accepted)
                        acceptedFrames += accepted / 4
                        accepted
                    }
                }
            }
            check(queue.bytes() == 0 && queue.discardedBytes == 0L && acceptedFrames == 1_100)
            check(original.contentEquals(pcm(1_100, varying = true)))
            return heard.toByteArray()
        }
        val whole = render(intArrayOf(10_000))
        val fragmented = render(intArrayOf(0, 1, 0, 3, 2, 0, 9, 4, 20))
        check(fragmented.contentEquals(whole)) {
            "Gain changes across partial/zero writes lost, repeated or rescaled accepted samples"
        }
    }

    private fun failedWriteAndReplacementDoNotAdvanceTheGain() {
        class Output : PlaybackOutput {
            override val capacityBytes = 96_000
            var position = 0L
            var failNext = false
            val heard = ByteArrayOutputStream()
            override fun available() = capacityBytes
            override fun framePosition() = position
            override fun start() {}
            override fun close() {}
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                if (failNext) { failNext = false; error("injected device write failure") }
                heard.write(bytes, offset, length); position += length / 4
                return length
            }
        }
        val initial = Output()
        val replacement = Output()
        val output = RecoverableAudioOutput(initial, 4, factory = { replacement })
        val gain = PcmOutputGain(2, 240)
        fun write(original: ByteArray, target: Float): Int {
            val copy = original.copyOf()
            return output.writeSome(copy, 0, copy.size) { count ->
                gain.applyInPlace(copy, 0, count, target)
            }.also(gain::acceptedBytes)
        }
        try {
            output.start()
            check(write(pcm(1), 1f) == 4)
            initial.failNext = true
            check(write(pcm(480), 0f) == 0)
            check(output.rebuilds == 1 && output.discardedNativeBytes == 0L)
            check(write(pcm(480), 0f) == 480 * 4)
            val heard = replacement.heard.toByteArray()
            check(left(heard, 0) in 11_949..11_951 && left(heard, 239) == 0 && left(heard, 240) == 0) {
                "An unaccepted failed write advanced or compounded the mute transition"
            }
        } finally { output.close() }
    }

    private fun staticGainKeepsTheExistingLimiterAndQuantization() {
        for (target in listOf(0f, .37f, 1f, 1.4f, 2f)) {
            val original = pcm(480, varying = true)
            val expected = original.copyOf()
            if (target > 1f) DesktopGainLimiter.applyPcm16(expected, 0, expected.size, target)
            else if (target < 1f) for (at in expected.indices step 2) {
                val sample = ((expected[at].toInt() and 255) or (expected[at + 1].toInt() shl 8)).toShort().toInt()
                val value = (sample * target).toInt()
                expected[at] = value.toByte(); expected[at + 1] = (value shr 8).toByte()
            }
            val actual = original.copyOf()
            PcmOutputGain(2, 240).applyInPlace(actual, 0, actual.size, target)
            check(actual.contentEquals(expected)) { "Static gain-$target changed the established PCM curve" }
        }
        val gain = PcmOutputGain(1, 20)
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -.1f, 2.1f)) {
            check(runCatching { gain.applyInPlace(ByteArray(2), 0, 2, invalid) }.isFailure)
        }
    }

    private fun player(fixture: QueuedLine, gain: AtomicReference<Float>) = DesktopJitterAudioPlayer(
        fixture.line, 48_000, 2, health = PlaybackHealth(false, 400), currentVolume = gain::get)

    private fun stop(player: DesktopJitterAudioPlayer) {
        player.stop()
        check(player.awaitStopped(2_000)) { "Gain fixture retained native output ownership after stop" }
    }

    private fun await(player: DesktopJitterAudioPlayer, message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!condition() && System.nanoTime() < deadline) {
            player.throwIfFailed(); Thread.sleep(2)
        }
        player.throwIfFailed()
        check(condition()) { "$message; snapshot=${player.snapshot()}" }
    }

    private fun pcm(frames: Int, varying: Boolean = false): ByteArray = ByteArray(frames * 4).also { bytes ->
        val values = intArrayOf(-32768, -30000, -20001, -10001, -1, 0, 1, 10001, 20001, 30000, 32767)
        for (frame in 0 until frames) for (channel in 0..1) {
            val value = if (varying) values[(frame * 2 + channel) % values.size] else if (channel == 0) 12_000 else -9_000
            val at = frame * 4 + channel * 2
            bytes[at] = value.toByte(); bytes[at + 1] = (value shr 8).toByte()
        }
    }

    private fun left(bytes: ByteArray, frame: Int): Int {
        val at = frame * 4
        return ((bytes[at].toInt() and 255) or (bytes[at + 1].toInt() shl 8)).toShort().toInt()
    }

    private class QueuedLine(private val fragments: IntArray = intArrayOf(Int.MAX_VALUE)) {
        private val lock = Any()
        private val heard = ByteArrayOutputStream()
        private var permittedFrames = 0
        private var position = 0L
        private var call = 0
        private var closed = false
        var zeroWrites = 0
            private set
        var partialWrites = 0
            private set
        fun allow(frames: Int = Int.MAX_VALUE) = synchronized(lock) { permittedFrames = frames }
        fun frames(): Int = synchronized(lock) { heard.size() / 4 }
        fun captured(): ByteArray = synchronized(lock) { heard.toByteArray() }

        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize" -> 96_000
                "available" -> synchronized(lock) { if (permittedFrames > 0) 96_000 else 0 }
                "getLongFramePosition" -> synchronized(lock) { position }
                "write" -> synchronized(lock) {
                    check(!closed)
                    val requested = args!![2] as Int
                    val count = minOf(requested / 4, permittedFrames, fragments[call++ % fragments.size]) * 4
                    heard.write(args[0] as ByteArray, args[1] as Int, count)
                    position += count / 4; permittedFrames -= count / 4
                    if (count == 0) zeroWrites++ else if (count < requested) partialWrites++
                    count
                }
                "close" -> synchronized(lock) { closed = true; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "queued live gain fixture"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    else -> null
                }
            }
        } as SourceDataLine
    }
}
