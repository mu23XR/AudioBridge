/**
 * Receive-loop-owned continuity state for one validated WFAS source.
 * Short-loss policy follows Android6a41f95: at most three missing packets and
 * ten milliseconds of decaying PCM. Sample positions determine the exact hole;
 * silence and long gaps never replay an earlier waveform or accrue padding debt.
 */
internal class DesktopPcmContinuity(private val sampleRate: Int, private val channels: Int) {
    data class Result(
        val accepted: Boolean,
        val silence: Boolean,
        val pcm: ByteArray? = null,
        val concealment: ByteArray? = null,
        // Only long/invalid-position boundaries need the player's queue ramp.
        // Brief PLC and return from intentional silence are smoothed here.
        val discontinuity: Boolean = false
    )

    init { require(sampleRate in 4000..192000 && channels in 1..2) }
    private val frameBytes = channels * 2
    private val maxConcealFrames = sampleRate / 100
    private val fadeFrames = minOf(96, maxOf(1, sampleRate / 500))
    private var previousSequence = -1
    private var expectedPosition: Long? = null
    private var previousFrame: IntArray? = null
    private var fadeFrom = IntArray(channels)
    private var fadePosition = 0

    fun reset() {
        previousSequence = -1
        expectedPosition = null
        previousFrame = null
        fadeFrom = IntArray(channels)
        fadePosition = 0
    }

    /** Caller has already checked peer/authentication/version and classified silence. */
    fun accept(sequence: Int, samplePosition: Long, silence: Boolean, pcm: ByteArray): Result {
        require(sequence in 0..65535 && samplePosition in 0..0xffffffffL)
        require(pcm.size % frameBytes == 0 && (silence || pcm.isNotEmpty())) {
            "Continuity input must contain complete PCM frames or explicit silence"
        }
        val distance = if (previousSequence < 0) 1 else (sequence - previousSequence) and 65535
        // Match the existing modular packet-history rule, including sequence wrap.
        if (distance !in 1..32768) return Result(accepted = false, silence = false)
        previousSequence = sequence
        if (silence) {
            // A header-only silence packet gives no PCM duration to repay later.
            expectedPosition = null
            previousFrame = null
            fadeFrom = IntArray(channels)
            fadePosition = 0
            return Result(accepted = true, silence = true)
        }

        val missingPackets = distance - 1
        val gap = expectedPosition?.let { (samplePosition - it) and 0xffffffffL }
        var concealment: ByteArray? = null
        var discontinuity = false
        val last = previousFrame
        // Older peers can keep samplePosition at zero or reset it while their
        // sequence remains contiguous. Position metadata alone must not restart
        // the output ramp on every small packet; use it only for an actual
        // sequence hole, as the established receiver did.
        if (last != null && missingPackets > 0 && gap != null && gap != 0L) {
            if (missingPackets in 1..3 && gap in 1..maxConcealFrames.toLong()) {
                concealment = decayingTail(last, gap.toInt())
                fadeFrom = lastFrame(concealment)
                fadePosition = 0
            } else {
                // Backward/oversized positions or a long sequence hole cannot
                // authorize repeating old sound. Let the output ramp the new boundary.
                discontinuity = true
                fadePosition = fadeFrames
            }
        }
        val rendered = crossfade(pcm)
        previousFrame = lastFrame(rendered)
        expectedPosition = (samplePosition + pcm.size / frameBytes) and 0xffffffffL
        return Result(accepted = true, silence = false, pcm = rendered,
            concealment = concealment, discontinuity = discontinuity)
    }

    private fun decayingTail(last: IntArray, frames: Int): ByteArray = ByteArray(frames * frameBytes).also { pcm ->
        for (frame in 0 until frames) for (channel in 0 until channels) {
            // A one-frame hole keeps the previous endpoint; the following
            // crossfade starts from it instead of introducing a zero spike.
            val value = if (frames == 1) last[channel]
                else last[channel] * (frames - frame - 1) / (frames - 1)
            put(pcm, frame * frameBytes + channel * 2, value)
        }
    }

    private fun crossfade(pcm: ByteArray): ByteArray {
        if (fadePosition >= fadeFrames) return pcm
        val output = pcm.copyOf()
        val frames = minOf(pcm.size / frameBytes, fadeFrames - fadePosition)
        for (frame in 0 until frames) for (channel in 0 until channels) {
            val at = frame * frameBytes + channel * 2
            val step = fadePosition + frame + 1
            val value = (fadeFrom[channel] * (fadeFrames - step) + sample(pcm, at) * step) / fadeFrames
            put(output, at, value)
        }
        fadePosition += frames
        return output
    }

    private fun lastFrame(pcm: ByteArray): IntArray = IntArray(channels) { channel ->
        sample(pcm, pcm.size - frameBytes + channel * 2)
    }

    private fun sample(pcm: ByteArray, at: Int): Int =
        ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    private fun put(pcm: ByteArray, at: Int, value: Int) {
        pcm[at] = value.toByte()
        pcm[at + 1] = (value shr 8).toByte()
    }
}
