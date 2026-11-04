package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class PcmRampTest {
    private fun constant(frames: Int, channels: Int = 1, value: Int = 12000) =
        ByteArray(frames * channels * 2).also { pcm ->
            for (at in pcm.indices step 2) {
                pcm[at] = value.toByte(); pcm[at + 1] = (value shr 8).toByte()
            }
        }

    private fun sample(pcm: ByteArray, at: Int = 0) =
        ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()

    @Test fun fiveMillisecondFadeSpansShortStereoChunks() {
        val ramp = PcmRamp(2, 240).apply { restart() }
        val result = ArrayList<Byte>()
        repeat(4) { block ->
            val pcm = constant(60, 2)
            ramp.applyInPlace(pcm, 0, pcm.size)
            assertEquals(12000 * ((block + 1) * 60) / 240, sample(pcm, pcm.size - 4))
            assertEquals(sample(pcm, pcm.size - 4), sample(pcm, pcm.size - 2))
            result.addAll(pcm.toList()); ramp.acceptedBytes(pcm.size)
        }
        val single = constant(240, 2)
        PcmRamp(2, 240).apply { restart(); applyInPlace(single, 0, single.size) }
        assertArrayEquals(single, result.toByteArray())
        val after = constant(1, 2)
        ramp.applyInPlace(after, 0, after.size)
        assertEquals(12000, sample(after))
    }

    @Test fun zeroAndPartialWritesNeverRescaleQueueTails() {
        val original = constant(4)
        val queue = PcmQueue(2, 32).apply { offer(original) }
        val ramp = PcmRamp(1, 4).apply { restart() }
        val accepted = ArrayList<Byte>()
        val writeSizes = ArrayDeque(listOf(0, 2, 0, 4, 2))
        while (queue.bytes() > 0) queue.drain { bytes, offset, length ->
            val copy = bytes.copyOfRange(offset, offset + length)
            ramp.applyInPlace(copy, 0, copy.size)
            val written = minOf(writeSizes.removeFirst(), length)
            accepted.addAll(copy.copyOfRange(0, written).toList())
            ramp.acceptedBytes(written)
            written
        }
        assertEquals(listOf(3000, 6000, 9000, 12000),
            accepted.toByteArray().let { pcm -> (pcm.indices step 2).map { sample(pcm, it) } })
        assertArrayEquals(constant(4), original)
        assertEquals(0L, queue.discardedBytes)
    }

    @Test fun localFlushRestartsFadeBeforeItsFirstOutputWrite() {
        var now = 0L
        val ramp = PcmRamp(1, 4)
        var flushed = false
        val output = object : PlayoutGovernor.Output {
            override var playbackHeadPosition = 0
            val heard = ArrayList<Int>()
            override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int {
                heard.add(sample(pcm, offset)); return length
            }
            override fun pause() {}
            override fun flush() { flushed = true; playbackHeadPosition = 0 }
            override fun play() {}
            override fun setPlaybackRate(rate: Int) = 0
        }
        val governor = PlayoutGovernor(output, 48000, 2, 20, "test", { now }, {},
            onDiscontinuity = ramp::restart)
        governor.writePcm(constant(4), 0, 8)
        now = 500
        val next = constant(2)
        val written = governor.writePcm(next, 0, next.size) {
            assertTrue(flushed)
            ramp.applyInPlace(next, 0, next.size)
        }
        ramp.acceptedBytes(written)
        assertEquals(listOf(12000, 3000), output.heard)
        val continuation = constant(2)
        ramp.applyInPlace(continuation, 0, continuation.size)
        assertEquals(9000, sample(continuation))
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteStereoFrameIsRejected() {
        PcmRamp(2, 240).applyInPlace(ByteArray(6), 0, 6)
    }
}
