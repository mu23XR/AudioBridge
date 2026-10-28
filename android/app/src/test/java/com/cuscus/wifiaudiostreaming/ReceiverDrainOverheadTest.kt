package com.cuscus.wifiaudiostreaming

import org.junit.Assert.*
import org.junit.Test

class ReceiverDrainOverheadTest {
    @Test fun aRecoveryDecisionUsesOneNativeHeadObservation() {
        var queries = 0
        val output = object : PlayoutGovernor.Output {
            override val playbackHeadPosition: Int get() { queries++; return 0 }
            override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int) = length
            override fun pause() = Unit
            override fun flush() = Unit
            override fun play() = Unit
            override fun setPlaybackRate(rate: Int) = 0
        }
        val governor = PlayoutGovernor(output, 48_000, 4, 20, "test", { 0L }, {})
        assertFalse(governor.recoverStalledOutput())
        governor.noteWritten(256)
        queries = 0
        assertFalse(governor.recoverStalledOutput())
        assertEquals("An unchanged head must not trigger another native query", 1, queries)
    }

    @Test fun smallPacketsKeepUpWithoutRepeatingNativeHealthQueriesForEveryWrite() {
        val fixed = pacedInput(recoverEveryWrite = false)
        assertEquals("A bounded-cost native getter must not create sustained PCM loss", 0L, fixed.discarded)
        assertEquals(fixed.producedBytes, fixed.acceptedBytes)
        assertEquals(0, fixed.flushes)

        // The former caller combined a batch-level recovery check with another
        // recovery before each small write. The actual queue/pump/governor chain
        // must expose that overhead even though neither the producer nor output
        // clock is jittery in this controlled model.
        val redundant = pacedInput(recoverEveryWrite = true)
        assertTrue("The control must reproduce a loss beyond the existing 100ms budget",
            redundant.discarded > 19_200)
        assertTrue(fixed.headQueries < redundant.headQueries)
    }

    private data class Result(val producedBytes: Long, val acceptedBytes: Long,
                              val discarded: Long, val headQueries: Int, val flushes: Int)

    private fun pacedInput(recoverEveryWrite: Boolean): Result {
        val queue = PcmQueue(4, 11_520) // Existing 60ms hard bound.
        val policy = ReceiverBacklogPolicy(48_000, 4)
        val durationNs = 3_000_000_000L
        var nowNs = 0L
        var produced = 0
        var head = 0
        var nativeFrames = 960 // Existing 20ms target after intentional silence.
        var acceptedBytes = 0L
        var headQueries = 0
        var flushes = 0
        var querying = false

        fun advance(toNs: Long) {
            val ticks = (toNs * 48_000 / 1_000_000_000 - nowNs * 48_000 / 1_000_000_000).toInt()
            val played = minOf(nativeFrames, ticks)
            nativeFrames -= played
            head += played
            nowNs = toNs
            val due = (minOf(nowNs, durationNs) * 750 / 1_000_000_000).toInt()
            while (produced < due) {
                queue.offer(ByteArray(256) { (produced + it).toByte() })
                produced++
            }
        }
        val output = object : PlayoutGovernor.Output {
            override val playbackHeadPosition: Int get() {
                headQueries++
                // Virtual bounded device-call cost, not an assertion that a
                // real getter caused the observed cloud scheduling pause.
                if (querying) advance(nowNs + 850_000)
                return head
            }
            override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int {
                val accepted = minOf(length / 4, 7_680 - nativeFrames).coerceAtLeast(0) * 4
                nativeFrames += accepted / 4
                acceptedBytes += accepted
                return accepted
            }
            override fun pause() = Unit
            override fun flush() { nativeFrames = 0; head = 0; flushes++ }
            override fun play() = Unit
            override fun setPlaybackRate(rate: Int) = 0
        }
        val governor = PlayoutGovernor(output, 48_000, 4, 20, "test", { nowNs / 1_000_000 }, {})
        governor.bufferedFrames() // Establish the native origin before priming.
        governor.noteWritten(nativeFrames * 4)
        val pump = PcmDrainPump(queue, 48_000, 4) { nowNs }
        querying = true
        var iterations = 0
        while (nowNs < durationNs || queue.bytes() > 0) {
            assertTrue("Drain stopped making progress", ++iterations < 20_000)
            if (queue.bytes() == 0) {
                if (nowNs >= durationNs) break
                advance(maxOf(nowNs + 1, (produced + 1L) * 1_000_000_000 / 750 + 1))
            }
            governor.recoverStalledOutput()
            val drained = pump.drain(20, governor::bufferedFrames) { pcm, offset, length ->
                governor.writePcm(pcm, offset, length, recoverStall = recoverEveryWrite)
            }
            policy.softwareLimit(nowNs / 1_000_000, queue.bytes(), drained.limited)?.let(queue::trimTo)
            assertTrue(queue.bytes() <= 11_520)
            if (drained.blocked) advance(nowNs + 2_000_000)
        }
        assertEquals(produced * 256L, acceptedBytes + queue.discardedBytes)
        return Result(produced * 256L, acceptedBytes, queue.discardedBytes, headQueries, flushes)
    }
}
