/** Direct production-helper regressions; no network, native output or real recording. */
internal object DesktopPcmContinuityChecks {
    fun runChecks() {
        contiguousAudioAndSmallPacketFade()
        shortGapsUseExactSamplePositions()
        oneFrameGapHasNoZeroSpike()
        longOrInconsistentGapsNeverRepeatOldAudio()
        contiguousLegacyPositionsDoNotRestartRamps()
        silenceDoesNotBecomePcmDebt()
        sequenceAndSamplePositionWrap()
        independentSourcesAndValidation()
        println("PCM continuity: short PLC/crossfade, variable frames, wrap, late rejection, silence and long gaps passed")
    }

    @JvmStatic fun main(args: Array<String>) = runChecks()

    private fun contiguousAudioAndSmallPacketFade() {
        val continuity = DesktopPcmContinuity(48_000, 2)
        val original = constant(32)
        val before = original.copyOf()
        val first = continuity.accept(0, 0, false, original)
        check(first.accepted && !first.silence && first.concealment == null && !first.discontinuity)
        check(sample(first.pcm!!, 0, 0) == 125 && sample(first.pcm, 31, 0) == 4_000)
        check(original.contentEquals(before)) { "Crossfade modified the transport's input" }
        val second = continuity.accept(1, 32, false, constant(32))
        check(sample(second.pcm!!, 0, 0) == 4_125)
        val third = continuity.accept(2, 64, false, constant(32))
        check(sample(third.pcm!!, 31, 0) == 12_000)
        val fourthPcm = constant(168)
        val fourth = continuity.accept(3, 96, false, fourthPcm)
        check(fourth.pcm === fourthPcm && fourth.concealment == null && !fourth.discontinuity)
    }

    private fun shortGapsUseExactSamplePositions() {
        val continuity = DesktopPcmContinuity(48_000, 2)
        continuity.accept(10, 0, false, constant(168))
        val resumed = continuity.accept(12, 336, false, constant(71))
        val tail = checkNotNull(resumed.concealment)
        check(tail.size == 168 * 4 && !resumed.discontinuity)
        check(sample(tail, 0, 0) == 12_000 && sample(tail, 0, 1) == -9_000)
        check(sample(tail, 167, 0) == 0 && sample(tail, 167, 1) == 0)
        check((0 until 168).map { sample(tail, it, 0) }.zipWithNext().all { (a, b) -> b <= a })
        check(sample(resumed.pcm!!, 0, 0) == 125)
        // Last packet has71frames, but the next two-packet hole is300frames.
        // Packet count or old payload length must not substitute for media time.
        val variable = continuity.accept(15, 707, false, constant(168))
        check(variable.concealment!!.size == 300 * 4 && !variable.discontinuity)
        check(sample(variable.pcm!!, 95, 0) == 12_000)

        val ceiling = DesktopPcmContinuity(48_000, 2)
        ceiling.accept(0, 0, false, constant(168))
        check(ceiling.accept(4, 648, false, constant(168)).concealment!!.size == 480 * 4)
    }

    private fun oneFrameGapHasNoZeroSpike() {
        val continuity = DesktopPcmContinuity(48_000, 2)
        continuity.accept(0, 0, false, constant(168))
        val next = continuity.accept(2, 169, false, constant(168, -12_000, 9_000))
        check(next.concealment!!.size == 4 && sample(next.concealment, 0, 0) == 12_000)
        check(!next.discontinuity && sample(next.pcm!!, 0, 0) == 11_750)
        check(sample(next.pcm, 95, 0) == -12_000)
    }

    private fun longOrInconsistentGapsNeverRepeatOldAudio() {
        for ((sequence, position) in listOf(2 to 649L, 5 to 336L, 2 to 10L)) {
            val continuity = DesktopPcmContinuity(48_000, 2)
            continuity.accept(0, 0, false, constant(168))
            val pcm = constant(168, -1_000, 2_000)
            val result = continuity.accept(sequence, position, false, pcm)
            check(result.accepted && result.concealment == null && result.discontinuity)
            check(result.pcm === pcm) { "Long gaps must use the output boundary ramp without inserting audio" }
        }
        val continuity = DesktopPcmContinuity(48_000, 2)
        continuity.accept(0, 0, false, constant(168))
        // Lost zero-duration transport packets do not prove a media hole.
        val adjacentMedia = continuity.accept(2, 168, false, constant(168))
        check(adjacentMedia.concealment == null && !adjacentMedia.discontinuity)
    }

    private fun contiguousLegacyPositionsDoNotRestartRamps() {
        // Position-only jump assertions previously treated legacy metadata as an
        // audio discontinuity. Correcting that compatibility rule must leave
        // real sequence-loss bounds unchanged; small packets must not repeatedly
        // restart a longer device fade and create a gain sawtooth.
        for (constantZero in listOf(true, false)) {
            val continuity = DesktopPcmContinuity(48_000, 2)
            repeat(32) { index ->
                val position = if (constantZero) 0L else when (index % 4) {
                    0 -> 0L
                    1 -> 100_000L
                    2 -> 1L
                    else -> 0xffffffd0L
                }
                val input = constant(168)
                val original = input.copyOf()
                val result = continuity.accept((65520 + index) and 65535, position, false, input)
                check(result.accepted && !result.silence && result.concealment == null && !result.discontinuity) {
                    "Contiguous legacy packet$index restarted its output ramp"
                }
                check(input.contentEquals(original)) { "Legacy-position handling modified source PCM" }
                if (index > 0) check(result.pcm === input && result.pcm.contentEquals(original)) {
                    "Contiguous packet$index received a repeated compatibility fade"
                }
            }
        }
    }

    private fun silenceDoesNotBecomePcmDebt() {
        val continuity = DesktopPcmContinuity(48_000, 2)
        continuity.accept(10, 0, false, constant(168))
        for ((seq, position) in listOf(11 to 168L, 12 to 48_000L, 13 to 1_000_000L)) {
            val result = continuity.accept(seq, position, true, ByteArray(0))
            check(result.accepted && result.silence && result.pcm == null && result.concealment == null)
        }
        val resumed = continuity.accept(15, 2_000_000, false, constant(168))
        check(resumed.accepted && !resumed.silence && resumed.concealment == null && !resumed.discontinuity)
        check(resumed.pcm!!.size == 168 * 4 && sample(resumed.pcm, 0, 0) == 125)
        check(sample(resumed.pcm, 95, 0) == 12_000)
        // A late silence marker must not clear the newly established real source.
        check(!continuity.accept(14, 1_000_100, true, ByteArray(0)).accepted)
        val following = continuity.accept(16, 2_000_168, false, constant(168))
        check(following.concealment == null && sample(following.pcm!!, 0, 0) == 12_000)
    }

    private fun sequenceAndSamplePositionWrap() {
        val continuity = DesktopPcmContinuity(48_000, 2)
        check(continuity.accept(65535, 0xfffffff0L, false, constant(16)).accepted)
        val wrapped = continuity.accept(0, 0, false, constant(16))
        check(wrapped.accepted && wrapped.concealment == null && !wrapped.discontinuity)
        val afterLoss = continuity.accept(2, 32, false, constant(16))
        check(afterLoss.accepted && afterLoss.concealment!!.size == 16 * 4)
        check(!continuity.accept(1, 16, false, constant(16)).accepted)
        check(!continuity.accept(2, 32, false, constant(16)).accepted)
        val following = continuity.accept(3, 48, false, constant(16))
        check(following.accepted && following.concealment == null && !following.discontinuity)
    }

    private fun independentSourcesAndValidation() {
        val first = DesktopPcmContinuity(48_000, 2)
        val second = DesktopPcmContinuity(48_000, 2)
        first.accept(0, 0, false, constant(168, 12_000, -9_000))
        second.accept(500, 0, false, constant(168, -7_000, 5_000))
        check(sample(first.accept(2, 336, false, constant(168)).concealment!!, 0, 0) == 12_000)
        check(sample(second.accept(502, 336, false, constant(168)).concealment!!, 0, 0) == -7_000)
        check(runCatching { first.accept(3, 504, false, ByteArray(3)) }.isFailure)
        check(first.accept(3, 504, false, constant(168)).accepted)
        check(runCatching { first.accept(65536, 0, true, ByteArray(0)) }.isFailure)
        check(runCatching { first.accept(4, 0x100000000L, true, ByteArray(0)) }.isFailure)
        check(runCatching { first.accept(4, 0, false, ByteArray(0)) }.isFailure)
        first.reset()
        check(first.accept(0, 0, false, constant(168)).accepted)
        val mono = DesktopPcmContinuity(4_000, 1).accept(1, 0, false, ByteArray(32))
        check(mono.accepted && mono.pcm!!.size % 2 == 0)
        check(runCatching { DesktopPcmContinuity(48_000, 3) }.isFailure)
        check(runCatching { DesktopPcmContinuity(3999, 2) }.isFailure)
    }

    private fun constant(frames: Int, left: Int = 12_000, right: Int = -9_000): ByteArray =
        ByteArray(frames * 4).also { pcm ->
            for (frame in 0 until frames) for ((channel, value) in listOf(left, right).withIndex()) {
                val at = frame * 4 + channel * 2
                pcm[at] = value.toByte(); pcm[at + 1] = (value shr 8).toByte()
            }
        }

    private fun sample(pcm: ByteArray, frame: Int, channel: Int): Int {
        val at = frame * 4 + channel * 2
        return ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
    }
}
