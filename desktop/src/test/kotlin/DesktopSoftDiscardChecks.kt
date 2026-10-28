import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import javax.sound.sampled.SourceDataLine

/** Runs the production worker, including its real silence/clock/reset branches. */
internal object DesktopSoftDiscardChecks {
    fun runChecks() {
        irregularArrivalsKeepAnExistingReserve()
        capacityOverflowRemainsExplicitAndBounded()
        silenceMarkersPreserveAQueuedClockButIdleResetsIt()
        println("Full player: irregular94ms-target arrivals retain PCM;600ms overflow guard remains active")
    }

    @JvmStatic fun main(args: Array<String>) = runChecks()

    private fun silenceMarkersPreserveAQueuedClockButIdleResetsIt() {
        val device = ClockedLine(55)
        val player = DesktopJitterAudioPlayer(device.line, 48_000, 2,
            prebufferMs = 40, health = PlaybackHealth(false, 40))
        player.start()
        try {
            pcm(550).let { player.submit(it, 0, it.size) }
            var due = System.nanoTime()
            repeat(12) { index ->
                due += TimeUnit.MILLISECONDS.toNanos(350)
                await(due)
                // The worker has not reached silence: real PCM is still queued.
                // Resetting on ingress markers prevented any catch-up window.
                if (index % 2 == 1) player.noteAudioFlow(false)
                val burst = pcm(350)
                for (offset in burst.indices step 672) {
                    player.submit(burst, offset, minOf(672, burst.size - offset))
                }
            }
            Thread.sleep(50)
            player.throwIfFailed()
            val busy = player.snapshot()
            println("[full worker queued clock] ratio=${busy.driftRatio} discarded=${busy.discardedBytes} bufferedMs=${player.bufferedMs()}")
            check(busy.driftRatio > 1.0005) { "Ingress silence kept resetting the queued real-audio clock: ${busy.driftRatio}" }
            check(busy.discardedBytes == 0L && busy.rebuilds == 0)

            player.noteAudioFlow(false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (System.nanoTime() < deadline) {
                player.throwIfFailed()
                val idle = player.snapshot()
                if (idle.softwareBytes == 0 && idle.driftRatio == 1.0) break
                Thread.sleep(10)
            }
            val idle = player.snapshot()
            check(idle.softwareBytes == 0 && idle.driftRatio == 1.0 && idle.discardedBytes == 0L) {
                "Actual idle did not restore nominal playback without deleting queued PCM: $idle"
            }
        } finally { player.stop() }
        check(player.awaitStopped(2_000))
    }

    private fun irregularArrivalsKeepAnExistingReserve() {
        val device = ClockedLine(109)
        val player = DesktopJitterAudioPlayer(device.line, 48_000, 2,
            prebufferMs = 94, health = PlaybackHealth(false, 94))
        player.start()
        try {
            // An accumulated reserve below capacity must not be destroyed to
            // reach94ms. The old low-water policy cuts this after one second.
            pcm(550).let { player.submit(it, 0, it.size) }
            val began = System.nanoTime()
            var due = began
            val gaps = listOf(320, 295, 340, 305, 330, 300, 335, 310)
            for ((index, gap) in gaps.withIndex()) {
                due += TimeUnit.MILLISECONDS.toNanos(gap.toLong())
                await(due)
                player.throwIfFailed()
                // Same arrival envelope as the observation, synthesized rather
                // than claiming a packet trace was captured. Exercise silence
                // and resume through the actual production player's methods.
                if (index == 4) player.noteAudioFlow(false)
                val burst = pcm(gap)
                var offset = 0
                while (offset < burst.size) {
                    val count = minOf(168 * 4, burst.size - offset)
                    player.submit(burst, offset, count)
                    offset += count
                }
            }
            Thread.sleep(40)
            player.throwIfFailed()
            val state = player.snapshot()
            val elapsedFrames = (System.nanoTime() - began) * 48_000 / 1_000_000_000
            println("[full worker reserve] discarded=${state.discardedBytes} bufferedMs=${player.bufferedMs()} " +
                "playedFrames=${device.played()} elapsedFrames=$elapsedFrames rebuilds=${state.rebuilds}")
            check(state.discardedBytes == 0L) { "Production player deleted an admitted reserve: ${state.discardedBytes}B" }
            check(device.played() >= elapsedFrames * 9 / 10) { "Keeping input masked a stalled playback worker" }
            check(player.bufferedMs() <= 749 && state.rebuilds == 0)
        } finally { player.stop() }
        check(player.awaitStopped(2_000)) { "Reserve regression retained native ownership" }
    }

    private fun capacityOverflowRemainsExplicitAndBounded() {
        val device = ClockedLine(109)
        val player = DesktopJitterAudioPlayer(device.line, 48_000, 2,
            prebufferMs = 94, health = PlaybackHealth(false, 94))
        player.start()
        try {
            // A single650ms offer has an exact50ms excess regardless of the
            // worker's schedule; bounded memory must survive soft-policy removal.
            pcm(650).let { player.submit(it, 0, it.size) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (player.snapshot().discardedBytes == 0L && System.nanoTime() < deadline) {
                player.throwIfFailed(); Thread.sleep(2)
            }
            check(player.snapshot().discardedBytes == 50L * 192) {
                "Hard overflow disappeared or deleted more than its exact excess: ${player.snapshot().discardedBytes}"
            }
            check(player.snapshot().softwareBytes <= 640 * 192)
        } finally { player.stop() }
        check(player.awaitStopped(2_000))
    }

    private fun pcm(ms: Int) = ByteArray(ms * 192).also { pcm ->
        for (at in pcm.indices step 2) { pcm[at] = 0x10; pcm[at + 1] = 0x27 }
    }

    private fun await(due: Long) {
        while (true) {
            val remaining = due - System.nanoTime()
            if (remaining <= 0) return
            TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(10)))
        }
    }

    private class ClockedLine(capacityMs: Int) {
        private val lock = Any()
        private val capacity = capacityMs * 48
        private var buffered = 0L
        private var consumed = 0L
        private var lastAt = System.nanoTime()
        private var fraction = 0L
        private var started = false
        private var closed = false

        private fun advance() {
            val now = System.nanoTime()
            if (started && !closed) {
                val units = (now - lastAt) * 48_000 + fraction
                val take = minOf(buffered, units / 1_000_000_000)
                buffered -= take; consumed += take
                fraction = units % 1_000_000_000
            }
            lastAt = now
        }

        fun played(): Long = synchronized(lock) { advance(); consumed }

        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader,
            arrayOf(SourceDataLine::class.java)) { proxy, method, args ->
            synchronized(lock) {
                advance()
                when (method.name) {
                    "getBufferSize" -> capacity * 4
                    "available" -> (capacity - buffered).toInt() * 4
                    "getLongFramePosition" -> consumed
                    "start" -> { started = true; null }
                    "stop" -> { started = false; null }
                    "flush" -> { buffered = 0; null }
                    "close" -> { closed = true; null }
                    "write" -> {
                        check(!closed)
                        val count = minOf(args!![2] as Int, (capacity - buffered).toInt() * 4)
                        buffered += count / 4
                        count
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        java.lang.Float.TYPE -> 0f
                        else -> null
                    }
                }
            }
        } as SourceDataLine
    }
}
