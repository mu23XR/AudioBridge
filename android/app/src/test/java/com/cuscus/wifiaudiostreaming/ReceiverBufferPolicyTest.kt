package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class ReceiverBufferPolicyTest {
    @Test fun smallPacketsKeepUpEvenWhenTheWorkerRunsOnlyEveryEightMilliseconds() {
        val queue = PcmQueue(4, 11_520)
        val pump = PcmDrainPump(queue, 48_000, 4) { 0L }
        var nativeFrames = 480L
        var writtenBytes = 0
        repeat(125) {
            nativeFrames = (nativeFrames - 384).coerceAtLeast(0)
            // Six 256B packets are exactly 8ms at 48kHz/stereo/PCM16.
            repeat(6) { queue.offer(ByteArray(256)) }
            val result = pump.drain(20, { nativeFrames }) { _, _, length ->
                nativeFrames += length / 4
                writtenBytes += length
                length
            }
            assertEquals(6, result.writes)
            assertFalse(result.blocked)
            assertEquals(0, queue.bytes())
        }
        assertEquals(192_000, writtenBytes)
        assertEquals(0L, queue.discardedBytes)
    }

    @Test fun writesStopExactlyAtNativeHeadroomAndKeepTheUnwrittenTail() {
        val queue = PcmQueue(4, 8_000)
        val original = ByteArray(4_000) { it.toByte() }
        queue.offer(original)
        var nativeFrames = 1_670L // 35ms ceiling =1680 frames; room=10 frames.
        val accepted = ArrayList<Byte>()
        val result = PcmDrainPump(queue, 48_000, 4) { 0L }.drain(20, { nativeFrames }) { pcm, offset, length ->
            assertEquals(40, length)
            accepted.addAll(pcm.copyOfRange(offset, offset + length).toList())
            nativeFrames += length / 4
            length
        }
        assertTrue(result.blocked)
        assertEquals(40, result.bytes)
        assertEquals(3_960, queue.bytes())
        queue.drain { pcm, offset, length ->
            accepted.addAll(pcm.copyOfRange(offset, offset + length).toList()); length
        }
        assertArrayEquals(original, accepted.toByteArray())
    }

    @Test fun zeroAndShortWritesWaitWithoutLosingOrRescalingTheTail() {
        val queue = PcmQueue(4, 1_024)
        val original = ByteArray(256) { it.toByte() }
        queue.offer(original)
        val pump = PcmDrainPump(queue, 48_000, 4) { 0L }
        val blocked = pump.drain(20, { 0 }) { _, _, _ -> 0 }
        assertEquals(1, blocked.writes)
        assertTrue(blocked.blocked)
        assertEquals(256, queue.bytes())
        val partial = pump.drain(20, { 0 }) { _, _, _ -> 12 }
        assertEquals(1, partial.writes)
        assertTrue(partial.blocked)
        queue.drain { pcm, offset, length ->
            assertEquals(12, offset)
            assertArrayEquals(original.copyOfRange(12, 256), pcm.copyOfRange(offset, offset + length))
            length
        }
        assertEquals(0, queue.bytes())
    }

    @Test fun longBatchesYieldByDurationAndByCpuBudget() {
        val queue = PcmQueue(4, 11_520)
        repeat(45) { queue.offer(ByteArray(256)) }
        val pump = PcmDrainPump(queue, 48_000, 4) { 0L }
        val result = pump.drain(400, { 0 }) { _, _, length -> length }
        assertEquals(3_840, result.bytes)
        assertEquals(15, result.writes)
        assertTrue(result.limited)
        assertFalse(result.blocked)
        var now = 0L
        val timed = PcmDrainPump(queue, 48_000, 4) { now }.drain(400, { 0 }) { _, _, length ->
            now += 3_000_000
            length
        }
        assertEquals(1, timed.writes)
        assertTrue(timed.limited)
    }

    @Test fun transientBurstsDoNotDiscardButSustainedExcessHasACooldown() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val native = 30L * 48
        val queued = 40 * 192
        assertNull(policy.softwareLimit(0, 20, native, queued))
        assertNull(policy.softwareLimit(99, 20, native, queued))
        assertNull(policy.softwareLimit(100, 20, 0, 192)) // Burst drained normally.
        assertNull(policy.softwareLimit(200, 20, native, queued))
        assertEquals(5 * 192, policy.softwareLimit(300, 20, native, queued))
        assertNull(policy.softwareLimit(301, 20, native, queued))
        assertNull(policy.softwareLimit(549, 20, native, queued))
        assertEquals(5 * 192, policy.softwareLimit(550, 20, native, queued))
    }

    @Test fun largeManualTargetsAreNotReplacedByAnAdaptiveBudget() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        assertNull(policy.softwareLimit(0, 400, 400L * 48, 60 * 192))
        assertNull(policy.softwareLimit(5_000, 400, 400L * 48, 60 * 192))
    }

    @Test fun naturallyDrainedBurstCannotArmImmediateTrimmingOfANewBurst() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        assertNull(policy.softwareLimit(0, 20, 30L * 48, 40 * 192))
        assertNull(policy.softwareLimit(50, 20, 30L * 48, 0))
        assertNull(policy.softwareLimit(500, 20, 30L * 48, 40 * 192))
        assertEquals(5 * 192, policy.softwareLimit(600, 20, 30L * 48, 40 * 192))
    }

    @Test fun trimmingRemovesOnlyOldAlignedFramesAfterAPartialWrite() {
        val queue = PcmQueue(4, 32)
        val pcm = ByteArray(24) { it.toByte() }
        queue.offer(pcm)
        queue.drain { _, _, _ -> 4 }
        assertEquals(12, queue.trimTo(8))
        assertEquals(12L, queue.discardedBytes)
        assertTrue(queue.takeDiscontinuity())
        queue.drain { bytes, offset, length ->
            assertArrayEquals(pcm.copyOfRange(16, 24), bytes.copyOfRange(offset, offset + length)); length
        }
    }

    @Test fun freshInputAndIntentionalSilenceAreIndependentFromQueueOccupancy() {
        val input = ReceiverInputActivity()
        assertFalse(input.isFlowing(0))
        input.pcm(0)
        assertTrue(input.isFlowing(200))
        input.silence()
        assertFalse(input.isFlowing(201))
        input.pcm(300)
        assertTrue(input.isFlowing(500))
        assertFalse(input.isFlowing(551)) // Old undrained audio cannot renew this.
    }

    @Test fun silenceDoesNotRaiseTargetButAStalledQueuedTrackStillRecovers() {
        val health = PlaybackHealth(true)
        repeat(5) { second ->
            val decision = health.sample(second * 1_000L, second * 300, false, 0, 0, hasQueuedAudio = true)
            assertEquals(0, decision.targetDeltaMs)
            assertFalse(decision.rebuild)
        }
        assertTrue(health.sample(6_000, 1_500, false, 6_000, 0, hasQueuedAudio = true).rebuild)
        assertFalse(health.sample(7_000, 1_500, false, 6_000, 0, hasQueuedAudio = false).rebuild)
    }

    @Test fun intentionalSilenceHasOneBoundedStereoFadeOut() {
        val fade = PcmSilenceTail(2, 4)
        assertNull(fade.begin())
        val pcm = byteArrayOf(0x10, 0x27, 0xf0.toByte(), 0xd8.toByte()) // +10000,-10000
        fade.remember(pcm)
        val tail = fade.begin()!!
        fun sample(frame: Int, channel: Int): Int {
            val at = (frame * 2 + channel) * 2
            return ((tail[at].toInt() and 255) or (tail[at + 1].toInt() shl 8)).toShort().toInt()
        }
        assertEquals(16, tail.size)
        assertEquals(7_500, sample(0, 0))
        assertEquals(-7_500, sample(0, 1))
        assertEquals(0, sample(3, 0))
        assertEquals(0, sample(3, 1))
        assertNull(fade.begin())
        assertArrayEquals(byteArrayOf(0x10, 0x27, 0xf0.toByte(), 0xd8.toByte()), pcm)
    }
}
