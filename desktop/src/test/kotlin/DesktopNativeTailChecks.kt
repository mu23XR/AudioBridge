import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.SourceDataLine

/** Exercise stale real PCM versus deliberate silence through the actual player worker. */
internal object DesktopNativeTailChecks {
    private const val STALL_MS = 900L

    fun runChecks() {
        frozenNativeRealTailStillRecoversAfterInputGoesStale()
        explicitSilenceDoesNotRebuildItsFrozenTail()
        println("Native-only real PCM recovery and intentional-silence exclusion passed")
    }

    private fun frozenNativeRealTailStillRecoversAfterInputGoesStale() {
        val first = FrozenLine()
        val replacement = FrozenLine()
        val rebuilds = AtomicInteger()
        val recoveryAt = AtomicLong()
        val health = PlaybackHealth(false, 20)
        val player = DesktopJitterAudioPlayer(first.line, 48_000, 2, health = health,
            watchdogTimeoutMs = STALL_MS, lineFactory = {
                recoveryAt.set(System.nanoTime())
                rebuilds.incrementAndGet()
                replacement.line
            })
        val pcm = constantPcm(480)
        player.start()
        try {
            val submittedAt = System.nanoTime()
            player.submit(pcm, 0, pcm.size)
            await(player, "Initial real PCM did not reach the native device") { first.queuedBytes() == pcm.size }
            first.consumeFrames(120)
            await(player, "Consumed prefix was not observed before native stall") {
                player.snapshot().playedFrames == 120L && player.snapshot().softwareBytes == 0
            }
            Thread.sleep(320)
            player.throwIfFailed()
            check(rebuilds.get() == 0) { "A stale input timestamp alone triggered premature rebuilding" }
            check(player.snapshot().nativeBytes == 360 * 4 && player.snapshot().softwareBytes == 0) {
                "The fixture must retain only a frozen native real-audio tail"
            }
            await(player, "Native-only real PCM stopped qualifying for stall recovery after250ms") {
                player.snapshot().rebuilds == 1
            }
            val snapshot = player.snapshot()
            check(TimeUnit.NANOSECONDS.toMillis(recoveryAt.get() - submittedAt) >= 250) {
                "Recovery never exercised input older than the fresh-PCM window"
            }
            check(rebuilds.get() == 1 && first.closed && !replacement.closed)
            check(snapshot.playedFrames == 120L) { "Rebuilding counted lost native PCM as played or reset progress" }
            check(snapshot.nativeDiscardedBytes == 360L * 4 && snapshot.discardedBytes == 0L) {
                "Unplayed native tail was not accounted separately from software loss"
            }
            check(snapshot.targetMs == 20 && health.targetMs == 20)
        } finally { player.stop() }
        check(player.awaitStopped(2_000) && replacement.closed)
    }

    private fun explicitSilenceDoesNotRebuildItsFrozenTail() {
        val native = FrozenLine()
        val rebuilds = AtomicInteger()
        val health = PlaybackHealth(true, 40)
        val player = DesktopJitterAudioPlayer(native.line, 48_000, 2, health = health,
            watchdogTimeoutMs = STALL_MS, lineFactory = {
                rebuilds.incrementAndGet()
                FrozenLine().line
            })
        val pcm = constantPcm(960)
        player.start()
        try {
            player.submit(pcm, 0, pcm.size)
            await(player, "Silence-control PCM did not reach the native device") { native.queuedBytes() == pcm.size }
            native.consumeFrames(120)
            await(player, "Silence-control prefix progress was not observed") { player.snapshot().playedFrames == 120L }
            player.noteAudioFlow(false)
            await(player, "Explicit silence did not drain its bounded fade") { player.snapshot().softwareBytes == 0 }
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STALL_MS * 2 + 100)
            while (System.nanoTime() < until) {
                player.throwIfFailed()
                check(rebuilds.get() == 0) { "Intentional silence was mistaken for a stalled real-audio source" }
                Thread.sleep(10)
            }
            val snapshot = player.snapshot()
            check(snapshot.rebuilds == 0 && snapshot.nativeDiscardedBytes == 0L && snapshot.discardedBytes == 0L)
            check(snapshot.playedFrames == 120L && snapshot.softwareBytes == 0)
            check(native.queuedBytes() in 1..(40 * 192)) { "Silent native refill exceeded the current target" }
            check(snapshot.targetMs == 40 && health.targetMs == 40) { "Intentional silence changed the adaptive target" }
        } finally { player.stop() }
        check(player.awaitStopped(2_000) && native.closed)
    }

    private fun await(player: DesktopJitterAudioPlayer, message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            player.throwIfFailed()
            Thread.sleep(2)
        }
        player.throwIfFailed()
        check(condition()) { message }
    }

    private fun constantPcm(frames: Int) = ByteArray(frames * 4).also { bytes ->
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        repeat(frames * 2) { samples.put(10_000.toShort()) }
    }

    /** Consumption is explicit, so a frozen tail cannot accidentally pass as a draining device. */
    private class FrozenLine {
        private val lock = Any()
        private val capacity = 19_200
        private var queued = 0
        private var position = 0L
        @Volatile var closed = false
            private set

        fun queuedBytes(): Int = synchronized(lock) { queued }
        fun consumeFrames(frames: Int) = synchronized(lock) {
            require(frames >= 0 && frames * 4 <= queued)
            queued -= frames * 4
            position += frames
        }

        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            when (method.name) {
                "getBufferSize" -> capacity
                "available" -> synchronized(lock) { capacity - queued }
                "getLongFramePosition" -> synchronized(lock) { position }
                "write" -> synchronized(lock) {
                    check(!closed)
                    val count = args!![2] as Int
                    check(count in 0..(capacity - queued) && count % 4 == 0)
                    queued += count
                    count
                }
                "flush" -> { synchronized(lock) { queued = 0 }; null }
                "close" -> { closed = true; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Frozen native-tail fixture"
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
