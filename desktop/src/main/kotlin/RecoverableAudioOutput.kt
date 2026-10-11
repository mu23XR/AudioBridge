import javax.sound.sampled.SourceDataLine

internal class PlaybackOutputFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Small boundary for real JavaSound devices and deterministic failure fixtures. */
internal interface PlaybackOutput {
    val capacityBytes: Int
    fun available(): Int
    fun framePosition(): Long
    fun start()
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
    fun close()
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
    private val stallLimitMs: Long = 5000
) {
    @Volatile private var output = initial
    @Volatile private var closed = false
    var failedRecoveries: Int = 0
        private set
    private var lastPosition = initial.framePosition()
    private var lastProgressAt = clockMs()
    private var recoveryUnconfirmed = false
    private var pendingPlayback = false

    fun start() {
        try { output.start() } catch (e: Exception) { throw PlaybackOutputFailure("Cannot start audio output", e) }
    }
    fun bufferedBytes(): Int = (output.capacityBytes - output.available()).coerceAtLeast(0)
    fun stalledMs(): Long = if (pendingPlayback) (clockMs() - lastProgressAt).coerceAtLeast(0) else 0

    fun write(bytes: ByteArray, offset: Int, length: Int, active: () -> Boolean) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size && length % frameBytes == 0)
        var cursor = offset
        val end = offset + length
        if (length > 0 && !pendingPlayback) { lastProgressAt = clockMs(); pendingPlayback = true }
        while (!closed && active() && cursor < end) {
            pulse()
            try {
                if (!flowing()) lastProgressAt = clockMs()
                val position = output.framePosition()
                if (position != lastPosition) {
                    lastPosition = position
                    lastProgressAt = clockMs()
                    if (recoveryUnconfirmed) {
                        failedRecoveries = 0; recoveryUnconfirmed = false
                        onRecovered()
                    }
                } else if (!pendingPlayback) lastProgressAt = clockMs()
                val available = output.available().coerceAtLeast(0).let { it - it % frameBytes }
                if (pendingPlayback && stalledMs() >= stallLimitMs) {
                    recover("Playback position did not advance")
                    continue
                }
                val buffered = (output.capacityBytes - available).coerceAtLeast(0)
                val room = (maxBufferedBytes() - buffered).coerceAtLeast(0).let { it - it % frameBytes }
                val count = minOf(available, room, end - cursor)
                if (count == 0) { pause(); continue }
                // SourceDataLine guarantees non-blocking writes up to available().
                val written = output.write(bytes, cursor, count)
                if (written !in 0..count || written % frameBytes != 0) throw PlaybackOutputFailure("Invalid device write: $written / $count")
                cursor += written
                pendingPlayback = pendingPlayback || written > 0
                if (written == 0) {
                    if (clockMs() - lastProgressAt >= stallLimitMs) recover("Audio device accepted no samples") else pause()
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: PlaybackOutputFailure) {
                throw e
            } catch (e: Exception) {
                recover("Audio device write failed", e)
            }
        }
    }

    private fun recover(reason: String, cause: Exception? = null) {
        if (closed) return
        val create = factory ?: throw PlaybackOutputFailure(reason, cause)
        // A newly opened but still frozen line must not reset the failure budget.
        failedRecoveries++
        if (failedRecoveries >= 3) throw PlaybackOutputFailure("$reason; three recoveries without playback progress", cause)
        onRecover()
        try { output.close() } catch (e: Exception) { throw PlaybackOutputFailure("Cannot release failed audio output", e) }
        while (!closed) {
            try {
                output = create()
                output.start()
                lastPosition = output.framePosition()
                lastProgressAt = clockMs()
                pendingPlayback = false
                recoveryUnconfirmed = true
                return
            } catch (e: Exception) {
                try { output.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
                failedRecoveries++
                if (failedRecoveries >= 3) throw PlaybackOutputFailure("Cannot rebuild audio output after three attempts", e)
                pause()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        output.close()
    }
}
