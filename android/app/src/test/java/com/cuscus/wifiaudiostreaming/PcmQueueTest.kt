package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PcmQueueTest {
    @Test fun partialAndZeroWritesRetainAllSamplesInOrder() {
        val q = PcmQueue(2, 16)
        val result = ArrayList<Byte>()
        q.offer(ByteArray(8) { it.toByte() }); q.offer(byteArrayOf(8, 9))
        assertEquals(0, q.drain { _, _, _ -> 0 })
        while (q.bytes() > 0) q.drain { bytes, offset, length ->
            val n = minOf(2, length); result.addAll(bytes.copyOfRange(offset, offset + n).toList()); n
        }
        assertEquals((0..9).map { it.toByte() }, result)
        assertEquals(0L, q.discardedBytes)
    }
    @Test fun burstsDropOldAudioAtAnExplicitBound() {
        val q = PcmQueue(2, 8)
        q.offer(byteArrayOf(1, 1, 2, 2)); q.offer(ByteArray(8) { 3 })
        assertEquals(8, q.bytes()); assertEquals(4L, q.discardedBytes)
        assertTrue(q.takeDiscontinuity()); assertFalse(q.takeDiscontinuity())
    }
    @Test(expected = IllegalStateException::class) fun incompleteFrameWriteIsRejected() {
        val q = PcmQueue(4, 16); q.offer(ByteArray(8)); q.drain { _, _, _ -> 1 }
    }
    @Test fun stalledOutputDoesNotHoldTheReceiveQueueLock() {
        val q = PcmQueue(2, 8); q.offer(ByteArray(4))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = Thread { q.drain { _, _, _ -> entered.countDown(); release.await(2, TimeUnit.SECONDS); 0 } }
        thread.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val begin = System.nanoTime()
            q.offer(ByteArray(8)) // Would deadlock behind an output-held lock.
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin) < 500)
            assertEquals(8, q.bytes())
        } finally { release.countDown(); thread.join(2000) }
        assertFalse(thread.isAlive)
    }
}
