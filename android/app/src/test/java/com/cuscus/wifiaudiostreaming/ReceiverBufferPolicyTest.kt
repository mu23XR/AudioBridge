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

    @Test fun realQueueCanTrimBeforeOverflowAndRetainsTheNewestFortyMilliseconds() {
        val queue = PcmQueue(4, 11_520)
        val original = ByteArray(11_008) { it.toByte() } //57.3ms, within the real60ms ceiling.
        queue.offer(original)
        val pump = PcmDrainPump(queue, 48_000, 4) { 0L }
        assertTrue(pump.drain(20, { 1_680 }) { _, _, _ -> fail("Native buffer is full"); 0 }.blocked)
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val keep = policy.softwareLimit(0, queue.bytes())!!
        assertEquals(7_680, keep)
        assertEquals(3_328, queue.trimTo(keep))
        assertEquals(3_328L, queue.discardedBytes)
        assertTrue(queue.takeDiscontinuity())
        val accepted = ArrayList<Byte>()
        pump.drain(20, { 0 }) { pcm, offset, length ->
            accepted.addAll(pcm.copyOfRange(offset, offset + length).toList()); length
        }
        pump.drain(20, { 0 }) { pcm, offset, length ->
            accepted.addAll(pcm.copyOfRange(offset, offset + length).toList()); length
        }
        assertArrayEquals(original.copyOfRange(original.size - keep, original.size), accepted.toByteArray())
    }

    @Test fun aDrainableBurstIsNotCutBeforeThePumpGetsItsHeadroom() {
        val queue = PcmQueue(4, 11_520)
        queue.offer(ByteArray(11_008))
        PcmDrainPump(queue, 48_000, 4) { 0L }.drain(400, { 0 }) { _, _, length -> length }
        assertNull(ReceiverBacklogPolicy(48_000, 4).softwareLimit(0, queue.bytes()))
        assertEquals(0L, queue.discardedBytes)
    }

    @Test fun preemptedWritableBatchContinuesWithoutDiscardingRealPcm() {
        val queue = PcmQueue(4, 11_520)
        val original = ByteArray(11_008) { (it / 256 + it * 31).toByte() }
        original.asList().chunked(256).forEach { queue.offer(it.toByteArray()) }
        val policy = ReceiverBacklogPolicy(48_000, 4)
        var nowNanos = 0L
        var nativeFrames = 0L
        val pump = PcmDrainPump(queue, 48_000, 4) { nowNanos }
        val accepted = ArrayList<Byte>()
        val first = pump.drain(20, { nativeFrames }) { pcm, offset, length ->
            accepted.addAll(pcm.copyOfRange(offset, offset + length).toList())
            nativeFrames += length / 4
            nowNanos += 42_000_000 //A single device call/preemption exhausts the2ms CPU budget.
            length
        }
        assertEquals(1, first.writes)
        assertEquals(256, first.bytes)
        assertTrue(first.limited)
        assertFalse(first.blocked)
        assertEquals(10_752, queue.bytes()) //56ms queued, with only1.3ms native occupancy.
        assertNull(policy.softwareLimit(42, queue.bytes(), first.limited))
        assertEquals(0L, queue.discardedBytes)

        var batches = 0
        while (queue.bytes() > 0) {
            val result = pump.drain(20, { nativeFrames }) { pcm, offset, length ->
                accepted.addAll(pcm.copyOfRange(offset, offset + length).toList())
                nativeFrames += length / 4
                assertTrue(nativeFrames <= 1_680) //The existing35ms admission ceiling remains exact.
                length
            }
            assertNull(policy.softwareLimit(nowNanos / 1_000_000, queue.bytes(), result.limited))
            if (result.blocked) {
                nowNanos += 20_000_000
                nativeFrames = (nativeFrames - 960).coerceAtLeast(0)
            }
            assertTrue("Continuation did not finish", ++batches < 10)
        }
        assertArrayEquals(original, accepted.toByteArray())
        assertEquals(0L, queue.discardedBytes)
    }

    @Test fun admissionBlockedAfterAPreemptedBatchStillTrimsWithoutAConsumedCooldown() {
        val queue = PcmQueue(4, 11_520)
        repeat(43) { queue.offer(ByteArray(256)) }
        val policy = ReceiverBacklogPolicy(48_000, 4)
        var nowNanos = 0L
        val pump = PcmDrainPump(queue, 48_000, 4) { nowNanos }
        val limited = pump.drain(20, { 0 }) { _, _, length -> nowNanos += 42_000_000; length }
        assertTrue(limited.limited)
        assertNull(policy.softwareLimit(42, queue.bytes(), limited.limited))
        val blocked = pump.drain(20, { 1_680 }) { _, _, _ -> fail("Full native output must not be written"); 0 }
        assertTrue(blocked.blocked)
        assertFalse(blocked.limited)
        assertEquals(0, blocked.writes)
        val retained = policy.softwareLimit(43, queue.bytes(), blocked.limited)!!
        assertEquals(7_680, retained)
        assertEquals(3_072, queue.trimTo(retained))
        assertEquals(3_072L, queue.discardedBytes)
        assertTrue(queue.takeDiscontinuity())
        repeat(50) { queue.offer(ByteArray(256)) }
        assertTrue(queue.bytes() <= 11_520)
        assertTrue(queue.discardedBytes > 3_072)
    }

    @Test fun trimCooldownDoesNotPreventHardCapacityProtection() {
        val queue = PcmQueue(4, 11_520)
        val policy = ReceiverBacklogPolicy(48_000, 4)
        queue.offer(ByteArray(11_008))
        queue.trimTo(policy.softwareLimit(0, queue.bytes())!!)
        repeat(50) { queue.offer(ByteArray(256)) }
        assertTrue(queue.bytes() <= 11_520)
        assertNull(policy.softwareLimit(249, queue.bytes()))
        val discarded = queue.discardedBytes
        queue.trimTo(policy.softwareLimit(250, queue.bytes())!!)
        assertEquals(7_680, queue.bytes())
        assertTrue(queue.discardedBytes > discarded)
    }

    @Test fun softThresholdIsInclusiveAndEmptyInputDoesNotCreateACut() {
        val policy = ReceiverBacklogPolicy(48_000, 4)
        assertNull(policy.softwareLimit(0, 0))
        assertNull(policy.softwareLimit(0, 50 * 192))
        assertEquals(40 * 192, policy.softwareLimit(0, 50 * 192 + 4))
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

    @Test fun continuousSilenceStaysWithinTheTargetAndRespectsPartialAcceptance() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        var native = 0L
        var played = 0L
        repeat(1_000) {
            val consumed = minOf(native, 240) //5ms ticks for five seconds.
            native -= consumed; played += consumed
            val requested = feed.bytesToWrite(20, native)
            assertTrue(requested % 4 == 0 && requested <= 1_920)
            // Simulate a short device write; only accepted frames are counted.
            native += (requested / 4).coerceAtMost(300)
            assertTrue(native <= 960)
        }
        assertTrue(played >= 48_000 * 4)
        assertEquals(0, feed.bytesToWrite(20, 960))
        assertEquals(0, feed.bytesToWrite(20, 20_000))
        assertEquals(4, feed.bytesToWrite(20, 959))
        assertEquals(0, feed.bytesToWrite(45, 2_160))
        assertTrue(feed.pcm.all { it == 0.toByte() })
    }

    @Test fun silentRefillRestoresTheTwentyMillisecondBudgetInTheSameTick() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        var native = 0L
        var calls = 0
        val accepted = feed.refill(20, { native }) { pcm, requested ->
            assertSame(feed.pcm, pcm)
            assertEquals(1_920, requested)
            calls++
            native += requested / 4
            assertTrue(native <= 960)
            requested
        }
        assertEquals(2, calls)
        assertEquals(3_840, accepted)
        assertEquals(960L, native)
    }

    @Test fun silentRefillRechecksRemainingHeadroomAndStopsAtTheExactTarget() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        var native = 100L
        val lengths = ArrayList<Int>()
        val accepted = feed.refill(20, { native }) { _, requested ->
            lengths.add(requested)
            native += requested / 4
            requested
        }
        assertEquals(listOf(1_920, 1_520), lengths)
        assertEquals(3_440, accepted)
        assertEquals(960L, native)
        assertEquals(0, feed.refill(20, { native }) { _, _ -> fail("Full output must not be written"); 0 })
        assertEquals(0, feed.refill(20, { 20_000 }) { _, _ -> fail("Over-budget output must not be written"); 0 })
    }

    @Test fun silentRefillDoesNotRetryAZeroOrShortWriteWithinTheTick() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        for (accepted in listOf(0, 12)) {
            var calls = 0
            var native = 0L
            assertEquals(accepted, feed.refill(20, { native }) { _, requested ->
                assertEquals(1_920, requested)
                calls++
                native += accepted / 4
                accepted
            })
            assertEquals(1, calls)
            assertEquals((accepted / 4).toLong(), native)
        }
    }

    @Test fun silentRefillCannotGrowIntoAnUnboundedBatchWhenTheHeadKeepsAdvancing() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        var calls = 0
        val accepted = feed.refill(400, { 0 }) { _, requested ->
            calls++
            assertTrue(requested <= 1_920)
            requested
        }
        assertEquals(2, calls)
        assertEquals(3_840, accepted)
    }

    @Test fun silentRefillRejectsInvalidNativeWriteResults() {
        val feed = ReceiverSilenceFeed(48_000, 4)
        for (result in listOf(-1, 2, 1_924)) {
            try {
                feed.refill(20, { 0 }) { _, _ -> result }
                fail("Invalid result accepted: $result")
            } catch (_: IllegalStateException) {
                // A device error, incomplete frame or excessive count must be
                // visible instead of silently corrupting the native budget.
            }
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
