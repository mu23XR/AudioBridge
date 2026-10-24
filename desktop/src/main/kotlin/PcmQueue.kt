
import java.util.ArrayDeque

/** A duration-bounded PCM FIFO. A partial device write consumes only its prefix. */
internal class PcmQueue(private val frameSize: Int, private val capacityBytes: Int) {
    data class Chunk(val bytes: ByteArray, var offset: Int = 0, var boundary: Boolean = false) { val remaining get() = bytes.size - offset }
    private class InFlight(val chunk: Chunk, val offset: Int, val length: Int) {
        var deferredDiscard = 0
    }
    private val chunks = ArrayDeque<Chunk>()
    private var inFlight: InFlight? = null
    private var size = 0
    @Volatile var discardedBytes = 0L
        private set
    var discontinuity = false
        private set
    init { require(frameSize > 0 && capacityBytes >= frameSize && capacityBytes % frameSize == 0) }

    @Synchronized fun offer(pcm: ByteArray, boundary: Boolean = false) {
        require(pcm.isNotEmpty() && pcm.size % frameSize == 0)
        if (pcm.size > capacityBytes) {
            clear(true)
            discardedBytes += pcm.size - capacityBytes
            chunks.add(Chunk(pcm.copyOfRange(pcm.size - capacityBytes, pcm.size), boundary = boundary))
            size = capacityBytes; return
        }
        while (size + pcm.size > capacityBytes) {
            val old = chunks.first
            // Preserve the exact newest capacity even when packet durations
            // do not divide it; whole-packet eviction can make a bounded
            // startup preroll permanently unreachable.
            val remove = minOf(size + pcm.size - capacityBytes, old.remaining)
            accountDiscard(old, remove)
            old.offset += remove
            size -= remove
            if (old.remaining == 0) chunks.removeFirst()
            discontinuity = true
        }
        chunks.add(Chunk(pcm, boundary = boundary)); size += pcm.size
    }

    /** Never hold a queue lock during a device call: OEM output can still stall. */
    fun drain(maxBytes: Int = Int.MAX_VALUE, write: (ByteArray, Int, Int) -> Int): Int {
        require(maxBytes >= frameSize)
        val pending = synchronized(this) {
            check(inFlight == null) { "PCM queue already has an active writer" }
            val first = chunks.peekFirst() ?: return 0
            InFlight(first, first.offset, minOf(first.remaining, maxBytes - maxBytes % frameSize))
                .also { inFlight = it }
        }
        var accepted = 0
        try {
            val written = write(pending.chunk.bytes, pending.offset, pending.length)
            check(written in 0..pending.length && written % frameSize == 0) {
                "Invalid PCM write result: $written/${pending.length}"
            }
            accepted = written
            return written
        } finally {
            synchronized(this) {
                // Prefix eviction can race a native write. Defer that prefix's
                // loss until acceptance is known; a cumulative counter never
                // needs a refund for frames the device actually accepted.
                discardedBytes += (pending.deferredDiscard - accepted).coerceAtLeast(0)
                if (chunks.peekFirst() === pending.chunk) {
                    val consumed = (pending.offset + accepted - pending.chunk.offset).coerceAtLeast(0)
                    pending.chunk.offset += consumed; size -= consumed
                    if (pending.chunk.remaining == 0) chunks.removeFirst()
                }
                inFlight = null
            }
        }
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
            accountDiscard(first, count)
            first.offset += count
            remaining -= count
            if (first.remaining == 0) chunks.removeFirst()
        }
        if (remove > 0) {
            size -= remove
            discontinuity = true
        }
        return remove
    }

    @Synchronized fun bytes(): Int = size
    @Synchronized fun takeDiscontinuity(): Boolean {
        val first = chunks.peekFirst()
        val result = discontinuity || first?.boundary == true
        discontinuity = false
        first?.boundary = false
        return result
    }
    @Synchronized fun clear(discard: Boolean = false) {
        if (discard && size > 0) {
            chunks.forEach { accountDiscard(it, it.remaining) }
            discontinuity = true
        }
        chunks.clear(); size = 0
    }

    /** Caller holds the queue lock; every eviction removes a chunk prefix. */
    private fun accountDiscard(chunk: Chunk, count: Int) {
        val pending = inFlight?.takeIf { it.chunk === chunk }
        val deferred = if (pending == null) 0 else
            (minOf(chunk.offset + count, pending.offset + pending.length) -
                maxOf(chunk.offset, pending.offset)).coerceAtLeast(0)
        if (pending != null) pending.deferredDiscard += deferred
        discardedBytes += count - deferred
    }
}
