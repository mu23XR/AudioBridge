package com.cuscus.wifiaudiostreaming

import java.util.ArrayDeque

/** A duration-bounded PCM FIFO. A partial device write consumes only its prefix. */
internal class PcmQueue(private val frameSize: Int, private val capacityBytes: Int) {
    data class Chunk(val bytes: ByteArray, var offset: Int = 0) { val remaining get() = bytes.size - offset }
    private val chunks = ArrayDeque<Chunk>()
    private var size = 0
    var discardedBytes = 0L
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
    fun drain(write: (ByteArray, Int, Int) -> Int): Int {
        val first = synchronized(this) { chunks.peekFirst() } ?: return 0
        val written = write(first.bytes, first.offset, first.remaining)
        check(written in 0..first.remaining && written % frameSize == 0) { "Invalid PCM write result: $written/${first.remaining}" }
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

    @Synchronized fun bytes(): Int = size
    @Synchronized fun takeDiscontinuity(): Boolean = discontinuity.also { discontinuity = false }
    @Synchronized fun clear(discard: Boolean = false) {
        if (discard && size > 0) { discardedBytes += size; discontinuity = true }
        chunks.clear(); size = 0
    }
}
