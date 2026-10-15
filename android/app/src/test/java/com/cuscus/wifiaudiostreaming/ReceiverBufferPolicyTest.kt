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

    @Test fun oneNormalQueueCannotOverflowTheBoundAfterAResumedPumpFilledItsReservation() {
        // The FIFO's own capacity is the budget. A queue that fits inside it is
        // ordinary traffic, not stale backlog, even when the pump has already
        // filled the native side.
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val fullReservation = 15L * 48
        val normalQueue = (ms(ReceiverPlayout.QUEUE_CAPACITY_MS) * 4).toInt()
        assertNull(policy.softwareLimit(0, fullReservation, normalQueue))
        assertNull(policy.softwareLimit(2_000, fullReservation, normalQueue))
        assertNull(policy.softwareLimit(9_000, fullReservation, normalQueue))
    }

    @Test fun sustainedExcessIsTrimmedImmediatelyAndThenCoolsDown() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val queuedFrames = ms(ReceiverPlayout.QUEUE_CAPACITY_MS + 10)
        val queued = queuedFrames.toInt() * 4
        val excess = (queuedFrames - ms(ReceiverPlayout.QUEUE_RETENTION_MS)).toInt() * 4
        // Trimming keeps exactly the retention window and drops only the excess.
        assertEquals(excess, policy.softwareLimit(0, 0L, queued))
        // The cooldown then suppresses the next trim, so a single burst cannot
        // be cut repeatedly while the queue is still refilling.
        assertNull(policy.softwareLimit(99, 0L, queued))
        assertNull(policy.softwareLimit(249, 0L, queued))
        assertEquals(excess, policy.softwareLimit(250, 0L, queued))
    }

    @Test fun trimmingRetainsTheWholeReservationInsteadOfThePumpCeiling() {
        // The old bound subtracted the current native frames from the target,
        // and the drain pump stops accepting at that same ceiling. Once it was
        // filled, the subtraction collapsed the retained queue to zero and the
        // receiver wiped its whole buffer on every trim after each silence.
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val queuedFrames = ms(ReceiverPlayout.QUEUE_CAPACITY_MS + 10)
        val queued = queuedFrames.toInt() * 4
        val excess = queuedFrames - ms(ReceiverPlayout.QUEUE_RETENTION_MS)
        // Only the excess over retention is dropped, and the queue keeps
        // servicing its newest frames instead of collapsing to zero.
        assertEquals(excess.toInt() * 4, policy.softwareLimit(0, 0L, queued))
    }

    @Test fun largeManualTargetsAreNotReplacedByAnAdaptiveBudget() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        assertNull(policy.softwareLimit(0, 400L * 48, 60 * 192))
        assertNull(policy.softwareLimit(5_000, 400L * 48, 60 * 192))
    }

    @Test fun naturallyDrainedBurstCannotArmImmediateTrimmingOfANewBurst() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val queuedFrames = ms(ReceiverPlayout.QUEUE_CAPACITY_MS + 10)
        val queued = queuedFrames.toInt() * 4
        val kept = (queuedFrames - ms(ReceiverPlayout.QUEUE_RETENTION_MS)).toInt() * 4
        assertEquals(kept, policy.softwareLimit(0, 0L, queued))
        assertNull(policy.softwareLimit(50, 0L, 0))
        // A burst that drained naturally still has to wait out the cooldown.
        assertNull(policy.softwareLimit(200, 0L, queued))
        assertEquals(kept, policy.softwareLimit(300, 0L, queued))
    }

    @Test fun trimmingRemovesOnlyOldAlignedFramesAfterAPartialWrite() {
        val queue = PcmQueue(4, 32)
        val pcm = ByteArray(24) { it.toByte() }
        queue.offer(pcm)
        queue.drain { _, _, _ -> 4 }
        assertEquals(12, queue.trimTo(8))
        assertEquals(12L, queue.discardedBytes)
        // A trim drops stale backlog without marking the queue discontinuous;
        // the surviving frames are still contiguous audio.
        assertFalse(queue.takeDiscontinuity())
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

    /** Stereo 16-bit frames in [ms] of the fixture's 48 kHz stream. */
    private fun ms(ms: Int): Long = 48_000L * ms / 1_000
}
