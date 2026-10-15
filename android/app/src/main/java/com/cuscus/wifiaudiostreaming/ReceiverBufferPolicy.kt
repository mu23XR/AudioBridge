package com.cuscus.wifiaudiostreaming

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
        val ceiling = framesForMs(targetMs + NATIVE_CEILING_MS)
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

    private companion object {
        /** Kept in step with the drain pump's admission ceiling. */
        const val NATIVE_CEILING_MS = 15
    }
}

/** Network bursts govern stale queue disposal, never instantaneous playback pitch. */
internal class ReceiverBacklogPolicy(private val sampleRate: Int, private val frameSize: Int) {
    /**
     * The queue is the pump's source, never its sink. [PcmDrainPump] admits at
     * most [NATIVE_CEILING_MS] beyond the target before it stops, so total
     * latency is bounded by that ceiling plus the FIFO capacity in
     * [ReceiverPlayout]; the bound does not move with the target because both
     * sides do. Deriving the software allowance from the *current* native
     * frames instead charged the pump's own admission against the queue and
     * collapsed to zero exactly once the pump had filled, which is an ordinary
     * post-silence state: a receiver that just resumed would then empty its
     * whole queue on every trim and drop roughly a tenth of a second of audio
     * instead of the excess. Trimming is also immediate rather than debounced,
     * because a deferred trim leaves the queue to overflow its hard capacity
     * first, which discards the oldest audio instead of the excess.
     */
    private val ceilingFrames = framesForMs(NATIVE_CEILING_MS)
    private val capacityFrames = framesForMs(ReceiverPlayout.QUEUE_CAPACITY_MS)
    private val retainedFrames = framesForMs(ReceiverPlayout.QUEUE_RETENTION_MS)
    private var lastTrimAt: Long? = null

    fun softwareLimit(nowMs: Long, nativeFrames: Long, queuedBytes: Int): Int? {
        val queuedFrames = queuedBytes / frameSize
        // Only a backlog beyond the queue's own capacity is stale.
        if (queuedBytes == 0 || queuedFrames <= capacityFrames) return null
        // An overrun is trimmed on the first sample that sees it. Waiting for a
        // debounce window lets the producer fill the queue to its hard capacity,
        // where PcmQueue.offer starts dropping the oldest audio — losing far
        // more than the excess the trim would have removed.
        if (lastTrimAt?.let { nowMs - it < 250 } == true) return null
        // Reserve what the pump may still be holding, then discard only what
        // exceeds the retention target. Computing the excess first and
        // subtracting it cannot retain zero bytes; the old target-minus-native
        // subtraction could, and did.
        val reservation = nativeFrames.coerceAtMost(ceilingFrames).coerceAtLeast(0L)
        val keep = (queuedFrames.toLong() - reservation - retainedFrames).coerceAtLeast(0L).coerceAtMost(queuedFrames.toLong()).toInt() * frameSize
        if (keep >= queuedBytes) return null
        lastTrimAt = nowMs
        return keep
    }

    fun reset() { lastTrimAt = null }
    private fun framesForMs(ms: Int): Long = sampleRate.toLong() * ms / 1000

    private companion object {
        /** Kept in step with the drain pump's admission ceiling. */
        const val NATIVE_CEILING_MS = 15
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
