/** One sender session owns the mandatory WFAS v2 packet clock, regardless of capture backend. */
internal class WfasPacketTimeline(
    val channels: Int,
    initialSequence: Int = 0,
    initialSamplePosition: Long = 0
) {
    private val frameBytes = channels * 2
    private var sequence = initialSequence
    private var samplePosition = initialSamplePosition

    init {
        require(channels in 1..2)
        require(initialSequence in 0..0xFFFF && initialSamplePosition in 0..0xFFFF_FFFFL)
    }

    fun stampPcm(packet: ByteArray, payloadBytes: Int) {
        require(payloadBytes > 0 && payloadBytes % frameBytes == 0 && payloadBytes <= packet.size - HEADER_BYTES) {
            "WFAS PCM packet must contain complete per-channel frames"
        }
        stamp(packet, silence = false, frames = payloadBytes / frameBytes)
    }

    fun stampSilence(packet: ByteArray, elapsedFrames: Int) {
        require(elapsedFrames >= 0)
        stamp(packet, silence = true, frames = elapsedFrames)
    }

    /** Captured PCM omitted after a failed send is a timeline gap, not time that disappeared. */
    fun skipPcm(payloadBytes: Int) {
        require(payloadBytes >= 0 && payloadBytes % frameBytes == 0)
        advance(payloadBytes / frameBytes)
    }

    private fun stamp(packet: ByteArray, silence: Boolean, frames: Int) {
        require(packet.size >= HEADER_BYTES)
        packet[0] = 0x57
        packet[1] = 0x46
        packet[2] = NetworkHandler_v1.WFAS_PROTOCOL_VERSION.toByte()
        packet[3] = if (silence) 1 else 0
        packet[4] = (sequence ushr 8).toByte()
        packet[5] = sequence.toByte()
        packet[6] = (samplePosition ushr 24).toByte()
        packet[7] = (samplePosition ushr 16).toByte()
        packet[8] = (samplePosition ushr 8).toByte()
        packet[9] = samplePosition.toByte()
        sequence = (sequence + 1) and 0xFFFF
        advance(frames)
    }

    private fun advance(frames: Int) { samplePosition = (samplePosition + frames) and 0xFFFF_FFFFL }

    private companion object { const val HEADER_BYTES = 10 }
}
