package com.cuscus.wifiaudiostreaming

import java.util.ArrayDeque

/** A duration-bounded PCM FIFO. A partial device write consumes only its prefix. */
internal class PcmQueue(private val frameSize: Int, private val capacityBytes: Int) {
    data class Chunk(val bytes: ByteArray, var offset: Int = 0) { val remaining get() = bytes.size - offset }
    private val chunks = ArrayDeque<Chunk>()
    private var size = 0
    @Volatile var discardedBytes = 0L
        private set
    var discontinuity = false
        private set
    init { require(frameSize > 0 && capacityBytes >= frameSize && capacityBytes % frameSize == 0) }

    @Synchronized fun offer(pcm: ByteArray) {
        require(pcm.isNotEmpty() && pcm.size % frameSize == 0)
        if (pcm.size > capacityBytes) {
            clear(true)
            discardedBytes += pcm.size - capacityBytes
            chunks.add(Chunk(pcm.copyOfRange(pcm.size - capacityBytes, pcm.size)))
            size = capacityBytes; return
        }
        while (size + pcm.size > capacityBytes) {
            val old = chunks.removeFirst()
            size -= old.remaining; discardedBytes += old.remaining
            discontinuity = true
        }
        chunks.add(Chunk(pcm)); size += pcm.size
    }

    /** Never hold a queue lock during a device call: OEM output can still stall. */
    fun drain(maxBytes: Int = Int.MAX_VALUE, write: (ByteArray, Int, Int) -> Int): Int {
        require(maxBytes >= frameSize)
        val first = synchronized(this) { chunks.peekFirst() } ?: return 0
        val length = minOf(first.remaining, maxBytes - maxBytes % frameSize)
        val written = write(first.bytes, first.offset, length)
        check(written in 0..length && written % frameSize == 0) { "Invalid PCM write result: $written/$length" }
        synchronized(this) {
            // An overflow/stop may have evicted this chunk while the output call
            // was in progress; its removal already accounted for queue bytes.
            if (chunks.peekFirst() === first) {
                first.offset += written; size -= written
                if (first.remaining == 0) chunks.removeFirst()
            }
        }
        return written
    }

    /**
     * Player-only stale-backlog trimming; preserve the newest frame-aligned tail.
     * Removing a prefix changes the boundary from already submitted audio even
     * though the surviving tail is internally contiguous. The player smooths
     * that boundary; ramping changes amplitude, never write admission.
     */
    @Synchronized fun trimTo(maxBytes: Int): Int {
        require(maxBytes >= 0 && maxBytes % frameSize == 0)
        val remove = (size - maxBytes).coerceAtLeast(0)
        var remaining = remove
        while (remaining > 0) {
            val first = checkNotNull(chunks.peekFirst()) { "PCM byte accounting exceeds queued chunks" }
            val count = minOf(remaining, first.remaining)
            first.offset += count
            remaining -= count
            if (first.remaining == 0) chunks.removeFirst()
        }
        if (remove > 0) {
            size -= remove; discardedBytes += remove
            discontinuity = true
        }
        return remove
    }

    @Synchronized fun bytes(): Int = size
    @Synchronized fun takeDiscontinuity(): Boolean = discontinuity.also { discontinuity = false }
    @Synchronized fun clear(discard: Boolean = false) {
        if (discard && size > 0) { discardedBytes += size; discontinuity = true }
        chunks.clear(); size = 0
    }
}
