package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class PcmQueueInFlightTest {
    @Test fun overflowCountsOnlyTheUnacceptedPartOfAnInFlightChunk() {
        for (accepted in listOf(0, 4, 8)) {
            val queue = PcmQueue(4, 16)
            val original = ByteArray(8) { it.toByte() }
            val replacement = ByteArray(16) { (it + 20).toByte() }
            queue.offer(original)
            assertEquals(accepted, queue.drain { bytes, offset, length ->
                assertSame(original, bytes)
                assertEquals(0, offset)
                assertEquals(8, length)
                queue.offer(replacement)
                assertEquals(16, queue.bytes())
                assertEquals(0L, queue.discardedBytes)
                accepted
            })
            assertEquals((8 - accepted).toLong(), queue.discardedBytes)
            assertEquals(16, queue.bytes())
            queue.drain { bytes, offset, length ->
                assertSame(replacement, bytes)
                assertEquals(0, offset)
                assertEquals(16, length)
                length
            }
            assertEquals(0, queue.bytes())
            assertEquals((8 - accepted).toLong(), queue.discardedBytes)
        }
    }

    @Test fun bytesOutsideTheRequestedWriteAreCountedImmediatelyAndNeverRefunded() {
        for (accepted in listOf(0, 4, 8)) {
            val queue = PcmQueue(4, 16)
            queue.offer(ByteArray(16))
            queue.drain(8) { _, _, requested ->
                assertEquals(8, requested)
                queue.offer(ByteArray(16))
                assertEquals(8L, queue.discardedBytes)
                accepted
            }
            assertEquals((16 - accepted).toLong(), queue.discardedBytes)
        }
    }

    @Test fun partialTrimDoesNotConsumeAnAcceptedPrefixTwice() {
        for (accepted in listOf(0, 4, 8, 12)) {
            val queue = PcmQueue(4, 16)
            val original = ByteArray(16) { it.toByte() }
            queue.offer(original)
            queue.drain(12) { _, _, _ ->
                assertEquals(8, queue.trimTo(8))
                assertEquals(8, queue.bytes())
                assertEquals(0L, queue.discardedBytes)
                accepted
            }
            val consumedOrTrimmed = maxOf(8, accepted)
            assertEquals(16 - consumedOrTrimmed, queue.bytes())
            assertEquals((8 - accepted).coerceAtLeast(0).toLong(), queue.discardedBytes)
            queue.drain { bytes, offset, length ->
                assertArrayEquals(original.copyOfRange(consumedOrTrimmed, 16),
                    bytes.copyOfRange(offset, offset + length))
                length
            }
            assertEquals(0, queue.bytes())
        }
    }

    @Test fun repeatedTrimThenOverflowDefersOnlyTheRequestedPrefix() {
        val queue = PcmQueue(4, 20)
        queue.offer(ByteArray(16))
        queue.drain(12) { _, _, _ ->
            queue.trimTo(12)
            queue.trimTo(8)
            assertEquals(0L, queue.discardedBytes)
            queue.offer(ByteArray(20))
            assertEquals(4L, queue.discardedBytes)
            assertEquals(20, queue.bytes())
            8
        }
        assertEquals(8L, queue.discardedBytes)
        assertEquals(20, queue.bytes())
    }

    @Test fun pendingPrefixAccountingStartsAtThePreviouslyConsumedOffset() {
        val queue = PcmQueue(4, 16)
        val original = ByteArray(16) { it.toByte() }
        queue.offer(original)
        queue.drain { _, _, _ -> 4 }
        queue.drain(8) { _, offset, length ->
            assertEquals(4, offset)
            assertEquals(8, length)
            queue.trimTo(4)
            assertEquals(0L, queue.discardedBytes)
            4
        }
        assertEquals(4L, queue.discardedBytes)
        assertEquals(4, queue.bytes())
        queue.drain { bytes, offset, length ->
            assertArrayEquals(original.copyOfRange(12, 16), bytes.copyOfRange(offset, offset + length))
            length
        }
    }

    @Test fun countedClearAndOversizedOfferSettlePendingLossSeparately() {
        for (accepted in listOf(0, 4, 8)) {
            val queue = PcmQueue(4, 16)
            queue.offer(ByteArray(8))
            queue.offer(ByteArray(8))
            queue.drain { _, _, _ ->
                queue.clear(true)
                assertEquals(8L, queue.discardedBytes)
                queue.offer(ByteArray(20))
                assertEquals(12L, queue.discardedBytes)
                accepted
            }
            assertEquals((20 - accepted).toLong(), queue.discardedBytes)
            assertEquals(16, queue.bytes())
        }
    }

    @Test fun uncountedClearDoesNotInventLossOrRefundAnEarlierCountedTrim() {
        for (accepted in listOf(0, 4, 8)) {
            val queue = PcmQueue(4, 16)
            queue.offer(ByteArray(8))
            queue.drain { _, _, _ ->
                queue.clear(false)
                queue.offer(ByteArray(12))
                accepted
            }
            assertEquals(0L, queue.discardedBytes)
            assertEquals(12, queue.bytes())
        }
        val queue = PcmQueue(4, 16)
        queue.offer(ByteArray(16))
        queue.drain(8) { _, _, _ ->
            queue.trimTo(12)
            queue.clear(false)
            0
        }
        assertEquals(4L, queue.discardedBytes)
        assertEquals(0, queue.bytes())
    }

    @Test fun invalidAndThrowingWritesSettleEvictionAndReleaseWriterOwnership() {
        for (invalid in listOf(-1, 2, 12)) {
            val queue = PcmQueue(4, 16)
            queue.offer(ByteArray(8))
            try {
                queue.drain { _, _, _ -> queue.offer(ByteArray(16)); invalid }
                fail("Invalid write accepted: $invalid")
            } catch (_: IllegalStateException) { }
            assertEquals(8L, queue.discardedBytes)
            assertEquals(16, queue.drain { _, _, length -> length })
        }
        val queue = PcmQueue(4, 16)
        queue.offer(ByteArray(8))
        val failure = IllegalArgumentException("Native output failed")
        try {
            queue.drain { _, _, _ -> queue.clear(true); throw failure }
            fail("Throwing output was swallowed")
        } catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
        assertEquals(8L, queue.discardedBytes)
        queue.offer(ByteArray(4))
        assertEquals(4, queue.drain { _, _, length -> length })
    }

    @Test fun rejectedSecondWriterDoesNotDamageTheFirstWriterOrItsTail() {
        val queue = PcmQueue(4, 16)
        queue.offer(ByteArray(8))
        assertEquals(4, queue.drain { _, _, _ ->
            try {
                queue.drain { _, _, length -> length }
                fail("A second writer entered the same chunk")
            } catch (_: IllegalStateException) { }
            4
        })
        assertEquals(4, queue.bytes())
        assertEquals(4, queue.drain { _, _, length -> length })
        assertEquals(0L, queue.discardedBytes)
    }

    @Test fun failedWriteWithoutEvictionKeepsAllSamplesForTheNextWriter() {
        for (throws in listOf(false, true)) {
            val queue = PcmQueue(4, 16)
            val original = ByteArray(8) { it.toByte() }
            queue.offer(original)
            try {
                queue.drain { _, _, _ ->
                    if (throws) throw IllegalStateException("Output failure")
                    -1
                }
                fail("Output failure was swallowed")
            } catch (_: IllegalStateException) { }
            assertEquals(8, queue.bytes())
            assertEquals(0L, queue.discardedBytes)
            queue.drain { bytes, offset, length ->
                assertArrayEquals(original, bytes.copyOfRange(offset, offset + length))
                length
            }
            assertEquals(0, queue.bytes())
        }
    }
}
