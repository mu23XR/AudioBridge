package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class PcmGainLimiterTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = value.toByte(); bytes[index * 2 + 1] = (value shr 8).toByte()
        }
    }
    private fun samples(pcm: ByteArray) = (pcm.indices step 2).map { at ->
        ((pcm[at].toInt() and 255) or (pcm[at + 1].toInt() shl 8)).toShort().toInt()
    }

    @Test fun unityRetainsEveryPcm16ValueBitForBit() {
        val original = pcm(*(-32768..32767).toList().toIntArray())
        val result = original.copyOf()
        assertEquals(0L, PcmGainLimiter.applyInPlace(result, 0, result.size, 1f))
        assertArrayEquals(original, result)
    }

    @Test fun attenuationAndLowLevelAmplificationRemainLinear() {
        val quiet = pcm(-10000, -100, 0, 100, 10000)
        assertEquals(0L, PcmGainLimiter.applyInPlace(quiet, 0, quiet.size, 2f))
        assertEquals(listOf(-20000, -200, 0, 200, 20000), samples(quiet))
        PcmGainLimiter.applyInPlace(quiet, 0, quiet.size, .5f)
        assertEquals(listOf(-10000, -100, 0, 100, 10000), samples(quiet))
        PcmGainLimiter.applyInPlace(quiet, 0, quiet.size, 0f)
        assertEquals(List(5) { 0 }, samples(quiet))
    }

    @Test fun boostedPeaksAreBoundedMonotonicAndCounted() {
        val input = pcm(*(-32768..32767).toList().toIntArray())
        val before = input.copyOf()
        val wouldClip = PcmGainLimiter.countWouldClip(before, 0, before.size, 2f)
        val softened = PcmGainLimiter.applyInPlace(input, 0, input.size, 2f)
        val output = samples(input)
        assertTrue(softened > wouldClip)
        assertTrue(wouldClip > 0)
        assertTrue(output.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(output.first() > -32768)
        assertTrue(output.last() < 32767)
        assertTrue(output[32768 + 20000] > 20000) // Boost still raises loud signals.
    }

    @Test fun responseDoesNotDependOnPacketBoundariesOrJumpAtUnity() {
        val before = pcm(-32768, -30000, -14000, 0, 14000, 30000, 32767)
        val whole = before.copyOf()
        val chunked = before.copyOf()
        PcmGainLimiter.applyInPlace(whole, 0, whole.size, 1.6f)
        for (at in chunked.indices step 2) PcmGainLimiter.applyInPlace(chunked, at, 2, 1.6f)
        assertArrayEquals(whole, chunked)
        val nearUnity = before.copyOf()
        PcmGainLimiter.applyInPlace(nearUnity, 0, nearUnity.size, 1.0001f)
        assertTrue(samples(before).zip(samples(nearUnity)).all { (a, b) -> kotlin.math.abs(a - b) <= 4 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedSampleBoundsAreRejected() {
        PcmGainLimiter.applyInPlace(ByteArray(4), 1, 2, 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteGainIsRejected() {
        PcmGainLimiter.applyInPlace(ByteArray(4), 0, 4, Float.NaN)
    }
}
