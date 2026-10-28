
/** Packet size must not determine how quickly a source can feed the device. */
internal class PcmDrainPump(
    private val queue: PcmQueue,
    private val sampleRate: Int,
    private val frameSize: Int,
    private val refill: () -> Boolean = { false },
    private val hasInput: () -> Boolean = { false },
    private val nowNanos: () -> Long = System::nanoTime
) {
    data class Result(val bytes: Int, val writes: Int, val blocked: Boolean, val limited: Boolean)

    fun drain(targetMs: Int, nativeFrames: () -> Long,
              write: (ByteArray, Int, Int) -> Int): Result {
        val started = nowNanos()
        val batchBytes = framesForMs(20) * frameSize
        val ceiling = framesForMs(targetMs + DesktopPlayoutLimits.NATIVE_HEADROOM_MS)
        var bytes = 0
        var calls = 0
        while (bytes < batchBytes && calls < 64 &&
            (calls == 0 || nowNanos() - started < 2_000_000)) {
            val room = (ceiling - nativeFrames()).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong() / frameSize)
            val limit = minOf(batchBytes - bytes, room.toInt() * frameSize)
            if (limit < frameSize) return Result(bytes, calls, blocked = true, limited = false)
            if (queue.bytes() == 0 && !refill()) return Result(bytes, calls, blocked = false, limited = false)
            var requested = 0
            val accepted = queue.drain(limit) { pcm, offset, length ->
                requested = length
                write(pcm, offset, length)
            }
            calls++
            bytes += accepted
            // Retain the exact tail and wait for output progress rather than
            // hammering a nonblocking track that has just refused data.
            if (accepted < requested || accepted == 0) return Result(bytes, calls, blocked = true, limited = false)
        }
        return Result(bytes, calls, blocked = false, limited = queue.bytes() > 0 || hasInput())
    }

    private fun framesForMs(ms: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()

}

/** The low-water level separates persistent clock error from an arrival burst's temporary reserve. */
internal class DesktopPlayoutLevel {
    private data class Bucket(val at: Long, var minimum: Long)
    private val buckets = java.util.ArrayDeque<Bucket>()
    private var firstAt: Long? = null
    private var lastAt: Long? = null

    fun observe(nowMs: Long, bufferedFrames: Long) {
        require(bufferedFrames >= 0)
        // Missing observations during a long descheduling gap cannot prove
        // that the two high endpoints were a sustained backlog between them.
        if (lastAt?.let { nowMs < it || nowMs - it > DesktopPlayoutLimits.LEVEL_WINDOW_MS / 4 } == true) reset()
        if (firstAt == null) firstAt = nowMs
        lastAt = nowMs
        val bucketAt = Math.floorDiv(nowMs, 50L) * 50L
        val tail = buckets.peekLast()
        if (tail?.at == bucketAt) tail.minimum = minOf(tail.minimum, bufferedFrames)
        else buckets.addLast(Bucket(bucketAt, bufferedFrames))
        val oldest = nowMs - DesktopPlayoutLimits.LEVEL_WINDOW_MS
        while (buckets.isNotEmpty() && buckets.first.at + 50 <= oldest) buckets.removeFirst()
    }

    fun floorFrames(): Long? = if (firstAt == null || lastAt!! - firstAt!! < DesktopPlayoutLimits.LEVEL_WINDOW_MS)
        null else buckets.minOf { it.minimum }

    /** Warmup may build missing reserve, but a first burst cannot justify speeding up. */
    fun clockFrames(targetFrames: Long): Long? {
        require(targetFrames > 0)
        if (buckets.isEmpty()) return null
        val minimum = buckets.minOf { it.minimum }
        return if (floorFrames() == null) minOf(minimum, targetFrames) else minimum
    }

    fun reset() { firstAt = null; lastAt = null; buckets.clear() }
}

/** Keep an intentionally silent track active without inserting an unbudgeted zero block. */
internal class ReceiverSilenceFeed(private val sampleRate: Int, private val frameSize: Int) {
    val pcm = ByteArray((sampleRate.toLong() * 10 / 1000).toInt() * frameSize)

    fun refill(targetMs: Int, nativeFrames: () -> Long, write: (ByteArray, Int) -> Int): Int {
        var acceptedBytes = 0
        // A quantized playback head can expose an entire target's headroom in
        // one tick. Refill it without an extra scheduler gap between10ms chunks,
        // while bounding this tick to two calls and20ms of submitted silence.
        repeat(2) {
            val requested = bytesToWrite(targetMs, nativeFrames())
            if (requested == 0) return acceptedBytes
            val accepted = write(pcm, requested)
            check(accepted in 0..requested && accepted % frameSize == 0) {
                "Invalid silent PCM write result: $accepted/$requested"
            }
            acceptedBytes += accepted
            if (accepted < requested) return acceptedBytes
        }
        return acceptedBytes
    }

    fun bytesToWrite(targetMs: Int, nativeFrames: Long): Int {
        val room = (sampleRate.toLong() * targetMs / 1000 - nativeFrames).coerceAtLeast(0L)
        return minOf(room, (pcm.size / frameSize).toLong()).toInt() * frameSize
    }
}

/** Only fresh input, not old queued audio, establishes a flowing source. */
internal class ReceiverInputActivity {
    private var lastPcmAt = Long.MIN_VALUE
    private var silent = true
    @Synchronized fun pcm(nowMs: Long) { lastPcmAt = nowMs; silent = false }
    @Synchronized fun silence() { silent = true }
    @Synchronized fun isSilent(): Boolean = silent
    @Synchronized fun isFlowing(nowMs: Long, windowMs: Long = 250): Boolean = !silent && nowMs - lastPcmAt in 0..windowMs
}

/** A bounded fade to zero at intentional silence, rather than an abrupt PCM edge. */
internal class PcmSilenceTail(private val channels: Int, private val frames: Int) {
    private val last = IntArray(channels)
    private var active = false
    init { require(channels in 1..2 && frames > 0) }
    @Synchronized fun remember(pcm: ByteArray) {
        val frame = channels * 2
        require(pcm.size >= frame && pcm.size % frame == 0)
        for (channel in 0 until channels) {
            val at = pcm.size - frame + channel * 2
            last[channel] = ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
        }
        active = true
    }
    @Synchronized fun begin(): ByteArray? {
        if (!active) return null
        active = false
        if (last.all { it == 0 }) return null
        return ByteArray(frames * channels * 2).also { pcm ->
            for (i in 0 until frames) for (channel in 0 until channels) {
                val sample = last[channel] * (frames - i - 1) / frames
                val at = (i * channels + channel) * 2
                pcm[at] = sample.toByte(); pcm[at + 1] = (sample shr 8).toByte()
            }
        }
    }
}

internal object DesktopPlayoutLimits {
    const val NATIVE_HEADROOM_MS = 15
    // Allocation absorbs receive/worker scheduling bursts, without waiting to
    // fill it. Clock correction consumes sustained excess gradually.
    const val QUEUE_CAPACITY_MS = 600
    const val LEVEL_WINDOW_MS = 1000L

    fun nativeCapacityMs(health: PlaybackHealth): Int =
        // Reserve adaptive growth without rebuilding a healthy device. Allocation
        // is capacity, not admitted latency: pump/output still use the live target.
        (if (health.adaptive) PlaybackHealth.MAX_ADAPTIVE_TARGET_MS else health.targetMs) + NATIVE_HEADROOM_MS

    // Allocation must not turn into a new startup-delay requirement.
    fun startupPrerollMs(targetMs: Int): Int = minOf(maxOf(1, targetMs / 2), 40)
}

