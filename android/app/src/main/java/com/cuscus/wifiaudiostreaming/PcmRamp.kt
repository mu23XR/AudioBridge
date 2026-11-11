package com.cuscus.wifiaudiostreaming

/** Fade position follows accepted output frames, not queue chunks or retries. */
internal class PcmRamp(private val channels: Int, private val durationFrames: Int) {
    private val frameSize = channels * 2
    private var position = durationFrames
    init { require(channels in 1..2 && durationFrames > 0) }

    fun restart() { position = 0 }

    /** Apply to a disposable output copy; the queue must keep its original PCM. */
    fun applyInPlace(pcm: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= pcm.size - length &&
            offset % frameSize == 0 && length % frameSize == 0)
        val fadingFrames = minOf(length / frameSize, durationFrames - position)
        for (i in 0 until fadingFrames) {
            val gain = (position + i + 1).toDouble() / durationFrames
            for (channel in 0 until channels) {
                val at = offset + i * frameSize + channel * 2
                val sample = ((pcm[at].toInt() and 255) or
                    (pcm[at + 1].toInt() shl 8)).toShort().toInt()
                val scaled = (sample * gain).toInt()
                pcm[at] = scaled.toByte()
                pcm[at + 1] = (scaled shr 8).toByte()
            }
        }
    }

    fun acceptedBytes(bytes: Int) {
        require(bytes >= 0 && bytes % frameSize == 0)
        position += minOf(bytes / frameSize, durationFrames - position)
    }
}
