
/** Packet size must not determine how quickly a source can feed the device. */
internal class PcmDrainPump(
    private val queue: PcmQueue,
    private val sampleRate: Int,
    private val frameSize: Int,
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
        while (queue.bytes() > 0 && bytes < batchBytes && calls < 64 &&
            (calls == 0 || nowNanos() - started < 2_000_000)) {
            val room = (ceiling - nativeFrames()).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong() / frameSize)
            val limit = minOf(batchBytes - bytes, room.toInt() * frameSize)
            if (limit < frameSize) return Result(bytes, calls, blocked = true, limited = false)
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
        return Result(bytes, calls, blocked = false, limited = queue.bytes() > 0)
    }

    private fun framesForMs(ms: Int): Int = (sampleRate.toLong() * ms / 1000).toInt()

}

/** Discard persistent excess latency, not a fresh receive-thread burst. */
internal class ReceiverBacklogPolicy(private val sampleRate: Int, private val frameSize: Int) {
    private var overBudgetSince: Long? = null
    private var lastObservedAt: Long? = null

    fun softwareLimit(nowMs: Long, queuedBytes: Int, nativeFrames: Long, targetMs: Int,
                      hasWritableContinuation: Boolean = false): Int? {
        require(queuedBytes >= 0 && queuedBytes % frameSize == 0 && nativeFrames >= 0 && targetMs > 0)
        if (lastObservedAt?.let { nowMs < it } == true) reset()
        lastObservedAt = nowMs
        val totalFrames = queuedBytes.toLong() / frameSize + nativeFrames
        if (queuedBytes == 0 || totalFrames <= framesForMs(targetMs + DesktopPlayoutLimits.BURST_HEADROOM_MS)) {
            overBudgetSince = null
            return null
        }
        val since = overBudgetSince ?: nowMs.also { overBudgetSince = it }
        // A full native line can leave new PCM in software for a while. Only
        // sustained excess is stale; bounded writable continuations run first.
        if (hasWritableContinuation || nowMs - since < DesktopPlayoutLimits.BACKLOG_GRACE_MS) return null
        overBudgetSince = null
        val keepFrames = (framesForMs(targetMs + DesktopPlayoutLimits.NATIVE_HEADROOM_MS) - nativeFrames)
            .coerceAtLeast(0)
        return (keepFrames * frameSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { it - it % frameSize }
    }

    fun reset() { overBudgetSince = null; lastObservedAt = null }
    private fun framesForMs(ms: Int): Long = sampleRate.toLong() * ms / 1000

}

/** Time-weight the whole backlog so a periodic burst cannot alias the clock-control tick. */
internal class DesktopPlayoutLevel {
    private var lastAt: Long? = null
    private var lastFrames = 0L
    private var weightedFrames = 0.0
    private var elapsedMs = 0L

    fun observe(nowMs: Long, bufferedFrames: Long) {
        require(bufferedFrames >= 0)
        lastAt?.let { previous ->
            val elapsed = nowMs - previous
            if (elapsed !in 0..2500) reset()
            else { weightedFrames += lastFrames * elapsed.toDouble(); elapsedMs += elapsed }
        }
        lastAt = nowMs
        lastFrames = bufferedFrames
    }

    fun averageFrames(): Long {
        val average = if (elapsedMs == 0L) lastFrames else (weightedFrames / elapsedMs).toLong()
        weightedFrames = 0.0; elapsedMs = 0
        return average
    }

    fun reset() { lastAt = null; lastFrames = 0; weightedFrames = 0.0; elapsedMs = 0 }
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
    @Synchronized fun isFlowing(nowMs: Long): Boolean = !silent && nowMs - lastPcmAt in 0..250
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
    // fill it. Persistent software+native latency is governed separately.
    const val QUEUE_CAPACITY_MS = 400
    const val BURST_HEADROOM_MS = 60
    const val BACKLOG_GRACE_MS = 250L

    fun nativeCapacityMs(health: PlaybackHealth): Int =
        // Reserve adaptive growth without rebuilding a healthy device. Allocation
        // is capacity, not admitted latency: pump/output still use the live target.
        (if (health.adaptive) PlaybackHealth.MAX_ADAPTIVE_TARGET_MS else health.targetMs) + NATIVE_HEADROOM_MS

    // Allocation must not turn into a new startup-delay requirement.
    fun startupPrerollMs(targetMs: Int): Int = minOf(maxOf(1, targetMs / 2), 40)
}

