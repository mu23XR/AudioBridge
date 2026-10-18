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
    // Sample after draining: a burst that fits in native headroom needs no cut.
    // The soft trigger must be below PcmQueue's hard ceiling, which offer()
    // enforces before the player can observe it. Return bytes to KEEP, not drop.
    private val triggerBytes = framesForMs(ReceiverPlayout.QUEUE_TRIM_TRIGGER_MS) * frameSize
    private val retainedBytes = framesForMs(ReceiverPlayout.QUEUE_RETENTION_MS) * frameSize
    private var lastTrimAt: Long? = null

    fun softwareLimit(nowMs: Long, queuedBytes: Int): Int? {
        if (queuedBytes <= triggerBytes) return null
        if (lastTrimAt?.let { nowMs - it < 250 } == true) return null
        lastTrimAt = nowMs
        return retainedBytes.toInt()
    }

    fun reset() { lastTrimAt = null }
    private fun framesForMs(ms: Int): Long = sampleRate.toLong() * ms / 1000

}

/** Keep an intentionally silent track active without inserting an unbudgeted zero block. */
internal class ReceiverSilenceFeed(private val sampleRate: Int, private val frameSize: Int) {
    val pcm = ByteArray((sampleRate.toLong() * 10 / 1000).toInt() * frameSize)

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
