import javax.sound.sampled.SourceDataLine
import java.util.concurrent.CompletableFuture

internal class PlaybackOutputFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Small boundary for real JavaSound devices and deterministic failure fixtures. */
internal interface PlaybackOutput {
    val capacityBytes: Int
    fun available(): Int
    fun framePosition(): Long
    fun start()
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun close()

    /** Restart consumption without discarding PCM already admitted to the device. */
    fun nudge() {}
}

internal class JavaSoundPlaybackOutput(private val line: SourceDataLine) : PlaybackOutput {
    override val capacityBytes: Int get() = line.bufferSize
    override fun available() = line.available()
    override fun framePosition() = line.longFramePosition
    override fun start() = line.start()
    override fun write(bytes: ByteArray, offset: Int, length: Int) = line.write(bytes, offset, length)
    override fun close() {
        // Closing the device also releases a pending native write on normal drivers.
        try { line.stop() } finally { try { line.flush() } finally { line.close() } }
    }

    override fun nudge() {
        // JavaSound has no Android restart-preroll threshold. start() is safe
        // on an already running line; stop/flush here would silently discard
        // accepted real PCM outside the rebuild's loss/progress accounting.
        line.start()
    }
}

/** Only the playback worker writes/rebuilds. Network workers merely observe failures. */
internal class RecoverableAudioOutput(
    initial: PlaybackOutput,
    private val frameBytes: Int,
    private val factory: (() -> PlaybackOutput)? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pause: () -> Unit = { Thread.sleep(3) },
    private val onRecover: () -> Unit = {},
    private val onRecovered: () -> Unit = {},
    private val pulse: () -> Unit = {},
    private val maxBufferedBytes: () -> Int = { Int.MAX_VALUE },
    private val flowing: () -> Boolean = { true },
    private val stallLimitMs: Long = 5000,
    private val softRecoveryMs: Long = 500
) {
    private val ownership = Any()
    private val closeFinished = CompletableFuture<Unit>()
    @Volatile private var output: PlaybackOutput? = initial
    @Volatile private var closed = false
    var failedRecoveries: Int = 0
        private set
    private var lastPosition = initial.framePosition()
    private var lastProgressAt = clockMs()
    private var recoveryUnconfirmed = false
    private var pendingPlayback = false
    @Volatile var rebuilds: Int = 0
        private set
    @Volatile var retiredPlayedFrames: Long = 0
        private set
    @Volatile var discardedNativeBytes: Long = 0
        private set
    var nativeWriteCalls: Long = 0
        private set
    var partialNativeWrites: Long = 0
        private set
    var maxNativeWriteNanos: Long = 0
        private set
    private var epochOrigin = lastPosition
    private var epochPlayedFrames = 0L
    private var lastSoftNudgeAt = 0L

    init { require(frameBytes > 0 && stallLimitMs > 0 && softRecoveryMs > 0) }

    fun start() {
        try { current().start() } catch (e: Exception) { throw PlaybackOutputFailure("Cannot start audio output", e) }
    }
    private fun current(): PlaybackOutput = output ?: throw PlaybackOutputFailure("Audio output is closed")
    fun bufferedBytes(): Int {
        val device = output ?: return 0
        return (device.capacityBytes - device.available()).coerceAtLeast(0)
    }
    fun playedFrames(): Long = retiredPlayedFrames + epochPlayedFrames
    fun stalledMs(): Long = if (pendingPlayback) (clockMs() - lastProgressAt).coerceAtLeast(0) else 0

    /** Transferring ownership is not proof that a driver's close() returned. */
    fun isCleanupComplete(): Boolean = closeFinished.isDone && !closeFinished.isCompletedExceptionally

    /** Check progress before admission: a full, wedged line still needs recovery. */
    fun poll(recoverStall: Boolean = true) {
        if (closed) return
        if (!flowing()) lastProgressAt = clockMs()
        val position = current().framePosition()
        epochPlayedFrames = maxOf(epochPlayedFrames, (position - epochOrigin).coerceAtLeast(0))
        if (position != lastPosition) {
            lastPosition = position
            lastProgressAt = clockMs()
            if (recoveryUnconfirmed) {
                failedRecoveries = 0; recoveryUnconfirmed = false
                onRecovered()
            }
        } else if (!pendingPlayback) lastProgressAt = clockMs()
        if (recoverStall && pendingPlayback && stalledMs() >= stallLimitMs) {
            recover("Playback position did not advance")
        } else if (recoverStall && pendingPlayback && stalledMs() >= softRecoveryMs) {
            // A wedged line is often one nudge away from running again; rebuilding
            // it throws away the queued audio the caller just pushed. Try the cheap
            // restart first and keep the rebuild budget for a line that stays dead
            // for the full stall window.
            val now = clockMs()
            if (now - lastSoftNudgeAt >= softRecoveryMs) {
                lastSoftNudgeAt = now
                try { current().nudge() } catch (e: Exception) { recover("Audio output did not restart", e) }
            }
        }
    }

    /** One available()-bounded call; the FIFO owns every unaccepted sample. */
    fun writeSome(bytes: ByteArray, offset: Int, length: Int, recoverStall: Boolean = true,
                  beforeWrite: (Int) -> Unit = {}): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length &&
            offset % frameBytes == 0 && length % frameBytes == 0)
        if (closed || length == 0) return 0
        pulse()
        try {
            poll(recoverStall)
            val device = current()
            val available = device.available().coerceAtLeast(0).let { it - it % frameBytes }
            val buffered = (device.capacityBytes - available).coerceAtLeast(0)
            val room = (maxBufferedBytes() - buffered).coerceAtLeast(0).let { it - it % frameBytes }
            val count = minOf(available, room, length)
            if (count == 0) return 0
            beforeWrite(count)
            val writeStarted = System.nanoTime()
            val written = device.write(bytes, offset, count)
            nativeWriteCalls++
            maxNativeWriteNanos = maxOf(maxNativeWriteNanos, System.nanoTime() - writeStarted)
            if (written !in 0..count || written % frameBytes != 0) {
                throw PlaybackOutputFailure("Invalid device write: $written / $count")
            }
            if (written < count) partialNativeWrites++
            if (written > 0 && !pendingPlayback) {
                pendingPlayback = true; lastProgressAt = clockMs()
            }
            return written
        } catch (e: InterruptedException) { throw e }
        catch (e: PlaybackOutputFailure) { if (closed) return 0 else throw e }
        catch (e: Exception) {
            if (closed) return 0
            recover("Audio device write failed", e)
            return 0
        }
    }

    fun requestRecovery(reason: String) = recover(reason)

    fun write(bytes: ByteArray, offset: Int, length: Int, active: () -> Boolean) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size && length % frameBytes == 0)
        var cursor = offset
        val end = offset + length
        while (!closed && active() && cursor < end) {
            val written = writeSome(bytes, cursor, end - cursor)
            cursor += written
            if (written == 0) pause()
        }
    }

    private fun recover(reason: String, cause: Exception? = null) {
        if (closed) return
        val create = factory ?: throw PlaybackOutputFailure(reason, cause)
        // A newly opened but still frozen line must not reset the failure budget.
        failedRecoveries++
        if (failedRecoveries >= 3) throw PlaybackOutputFailure("$reason; three recoveries without playback progress", cause)
        onRecover()
        val previous = synchronized(ownership) { output.also { output = null } }
        try {
            if (previous != null) {
                try {
                    val finalPosition = previous.framePosition()
                    epochPlayedFrames = maxOf(epochPlayedFrames, (finalPosition - epochOrigin).coerceAtLeast(0))
                    retiredPlayedFrames += epochPlayedFrames
                    epochPlayedFrames = 0
                    discardedNativeBytes += (previous.capacityBytes - previous.available()).coerceAtLeast(0)
                } finally { release(previous) }
            }
        } catch (e: Exception) { throw PlaybackOutputFailure("Cannot release failed audio output", e) }
        while (!closed) {
            var candidate: PlaybackOutput? = null
            try {
                candidate = create()
                candidate.start()
                lastPosition = candidate.framePosition()
                epochOrigin = lastPosition
                lastProgressAt = clockMs()
                pendingPlayback = false
                recoveryUnconfirmed = true
                val adopted = synchronized(ownership) {
                    if (closed) false else { output = candidate; true }
                }
                if (!adopted) release(candidate) else rebuilds++
                return
            } catch (e: Exception) {
                try { candidate?.let(::release) } catch (closeError: Exception) { e.addSuppressed(closeError) }
                failedRecoveries++
                if (failedRecoveries >= 3) throw PlaybackOutputFailure("Cannot rebuild audio output after three attempts", e)
                pause()
            }
        }
    }

    private fun release(device: PlaybackOutput) {
        try { device.close() }
        catch (failure: Exception) {
            // A lost reference or a second no-op close cannot prove that a
            // failed native release stopped playing. Retain that uncertainty.
            closeFinished.completeExceptionally(failure)
            throw failure
        }
    }

    fun close() {
        val owned = synchronized(ownership) {
            if (closed) return
            closed = true
            output.also { output = null }
        }
        try {
            owned?.let(::release)
            closeFinished.complete(Unit)
        } catch (failure: Exception) {
            closeFinished.completeExceptionally(failure)
            throw failure
        }
    }
}
