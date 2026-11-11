/** Wire-level checks for the same clock used by native and JavaCV sender loops. */
internal object WfasPacketTimelineChecks {
    fun runChecks() {
        for (channels in 1..2) variablePayloadAndElapsedSilence(channels)
        wrappingAndDroppedCaptureTail()
        invalidPacketsDoNotConsumeClock()
        println("WFAS sender timeline: mono/stereo, variable payload, real silence time, dropped capture and counter wraps passed")
    }

    private fun variablePayloadAndElapsedSilence(channels: Int) {
        val timeline = WfasPacketTimeline(channels)
        val silence = SilenceTimeline(44_100)
        val packet = ByteArray(1400) { 0x5A }
        var sequence = 0
        var position = 0L
        for (frames in listOf(1, 63, 173, 240, 2)) {
            val payload = frames * channels * 2
            timeline.stampPcm(packet, payload)
            header(packet, silence = false, sequence++, position)
            check(packet.drop(10).all { it == 0x5A.toByte() }) { "Header stamping changed PCM payload" }
            position += frames
        }

        silence.onAudio(0)
        var nowNs = 0L
        repeat(100) { index ->
            nowNs += if (index % 2 == 0) 10_999_999L else 11_123_457L
            val elapsed = silence.onSilence(nowNs)
            timeline.stampSilence(packet, elapsed)
            header(packet, silence = true, sequence++, position)
            position += elapsed
        }
        check(position == 479L + nowNs * 44_100 / 1_000_000_000L)
        timeline.stampPcm(packet, channels * 2 * 7)
        header(packet, silence = false, sequence, position)
    }

    private fun wrappingAndDroppedCaptureTail() {
        val packet = ByteArray(42)
        val wrap = WfasPacketTimeline(1, initialSequence = 0xFFFF, initialSamplePosition = 0xFFFF_FFFDL)
        wrap.stampPcm(packet, 8)
        header(packet, silence = false, 0xFFFF, 0xFFFF_FFFDL)
        wrap.stampSilence(packet, 5)
        header(packet, silence = true, 0, 1)
        wrap.stampPcm(packet, 2)
        header(packet, silence = false, 1, 6)

        // The first chunk failed to send, so the remainder of that captured
        // block is not sent under later sequence IDs. Its time still elapses.
        val dropped = WfasPacketTimeline(2)
        dropped.stampPcm(packet, 8)
        dropped.skipPcm(24)
        dropped.stampPcm(packet, 4)
        header(packet, silence = false, 1, 8)
        dropped.stampSilence(packet, 480)
        header(packet, silence = true, 2, 9)
        dropped.stampPcm(packet, 4)
        header(packet, silence = false, 3, 489)
    }

    private fun invalidPacketsDoNotConsumeClock() {
        val timeline = WfasPacketTimeline(2)
        val packet = ByteArray(14)
        check(runCatching { timeline.stampPcm(packet, 2) }.isFailure)
        check(runCatching { timeline.stampPcm(packet, 8) }.isFailure)
        check(runCatching { timeline.stampSilence(ByteArray(9), 480) }.isFailure)
        check(runCatching { timeline.stampSilence(packet, -1) }.isFailure)
        check(runCatching { timeline.skipPcm(2) }.isFailure)
        timeline.stampPcm(packet, 4)
        header(packet, silence = false, 0, 0)
    }

    private fun header(packet: ByteArray, silence: Boolean, sequence: Int, position: Long) {
        check(packet[0] == 0x57.toByte() && packet[1] == 0x46.toByte())
        check(packet[2] == NetworkHandler_v1.WFAS_PROTOCOL_VERSION.toByte() && packet[3] == (if (silence) 1 else 0).toByte())
        val actualSequence = ((packet[4].toInt() and 255) shl 8) or (packet[5].toInt() and 255)
        var actualPosition = 0L
        for (index in 6..9) actualPosition = (actualPosition shl 8) or (packet[index].toLong() and 255)
        check(actualSequence == sequence && actualPosition == position) {
            "Invalid WFAS clock: seq=$actualSequence/$sequence pos=$actualPosition/$position"
        }
    }
}
