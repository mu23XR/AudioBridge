import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.sound.sampled.SourceDataLine

/** Windows output boundary for the test21 receiver policy; no device call owns the PCM queue lock. */
internal open class DesktopJitterAudioPlayer(
    line: SourceDataLine,
    private val sampleRate: Int,
    private val channels: Int,
    private val prebufferMs: Int = 120,
    maxBufferMs: Int = 400,
    private val health: PlaybackHealth? = null,
    lineFactory: (() -> SourceDataLine)? = null,
    onRecover: () -> Unit = {},
    onRecovered: () -> Unit = {},
    private val watchdogTimeoutMs: Long = 5000,
    private val currentVolume: () -> Float = { 1f },
    private val onTargetChanged: (Int?) -> Unit = {}
) {
    internal data class Snapshot(
        val targetMs: Int, val softwareBytes: Int, val nativeBytes: Int,
        val discardedBytes: Long, val playedFrames: Long, val rebuilds: Int,
        val driftRatio: Double, val nativeDiscardedBytes: Long
    )

    init {
        require(sampleRate in 4000..192000 && channels in 1..2)
        require(prebufferMs > 0 && maxBufferMs > 0 && watchdogTimeoutMs > 0)
    }

    private val frameBytes = channels * 2
    private val bytesPerSecond = sampleRate * frameBytes
    // Capacity must absorb fresh receive bursts before the worker runs. The
    // combined native/software budget, not this allocation, controls latency.
    private val capacityMs = if (health != null) DesktopPlayoutLimits.QUEUE_CAPACITY_MS
        else maxOf(prebufferMs, maxBufferMs).coerceIn(20, 4000)
    private val capacityBytes = bytesFor(capacityMs)
    private val queue = PcmQueue(frameBytes, capacityBytes)
    private val input = ReceiverInputActivity()
    private val silenceTail = PcmSilenceTail(channels, maxOf(1, sampleRate / 200))
    private val silenceFeed = ReceiverSilenceFeed(sampleRate, frameBytes)
    private val ramp = PcmRamp(channels, maxOf(1, sampleRate / 200))
    private val gain = PcmOutputGain(channels, maxOf(1, sampleRate / 200))
    private val drift = DesktopPlayoutDrift(sampleRate)
    private val level = DesktopPlayoutLevel()
    private val resampler = DesktopPcmDriftResampler(channels)
    private val producer = Any()
    private val rendered = PcmRenderStage(queue, sampleRate, channels, resampler, producer)
    private val pump = PcmDrainPump(rendered.output, sampleRate, frameBytes,
        refill = rendered::renderNext, hasInput = rendered::hasInput)
    private var hasSubmittedPcm = false
    private val wake = Semaphore(0)
    private val noiseReducer = dsp.NoiseReducer().apply { init(sampleRate, channels) }
    private var noiseWasEnabled = false
    private var noiseScratch = ShortArray(0)

    @Volatile private var running = false
    @Volatile private var stopped = false
    @Volatile private var failure: PlaybackOutputFailure? = null
    @Volatile private var operationAt = -1L
    @Volatile private var operationName = "WAIT_PCM"
    private var thread: Thread? = null
    private var watchdog: Thread? = null
    private var primed = false
    private var feedingSilence = false
    private var underruns = 0L
    private var lastHealthAt = 0L
    private var lastTuneAt = 0L
    private var lastLogAt = 0L
    @Volatile private var latest = Snapshot(targetMs(), 0, 0, 0, 0, 0, 1.0, 0)

    private val output = RecoverableAudioOutput(JavaSoundPlaybackOutput(line), frameBytes,
        factory = lineFactory?.let { create -> { JavaSoundPlaybackOutput(create()) } },
        onRecover = {
            ramp.restart()
            synchronized(producer) { resampler.reset() }
            drift.reset()
            level.reset()
            feedingSilence = false
            onRecover()
        }, onRecovered = onRecovered,
        maxBufferedBytes = { bytesFor(targetMs() + DesktopPlayoutLimits.NATIVE_HEADROOM_MS) },
        flowing = { input.isFlowing(nowMs()) || realAudioPending() },
        stallLimitMs = watchdogTimeoutMs)

    private fun realAudioPending(): Boolean = !input.isSilent() &&
        (rendered.bytes() > 0 || output.bufferedBytes() > 0)

    private fun targetMs(): Int = health?.targetMs ?: prebufferMs
    private fun bytesFor(ms: Int): Int = (sampleRate.toLong() * frameBytes * ms / 1000L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt().let { it - it % frameBytes }.coerceAtLeast(frameBytes)
    private fun nowMs() = System.nanoTime() / 1_000_000
    private inline fun <T> operation(name: String, work: () -> T): T {
        operationName = name; operationAt = nowMs()
        return try { work() } finally { operationAt = -1; operationName = "WAIT_PCM" }
    }

    fun start() {
        check(!stopped) { "A stopped audio player cannot be restarted" }
        if (running) return
        running = true
        onTargetChanged(health?.targetMs)
        watchdog = Thread({
            try {
                while (running) {
                    Thread.sleep(minOf(100, maxOf(10, watchdogTimeoutMs / 4)))
                    val started = operationAt
                    if (started >= 0 && nowMs() - started >= watchdogTimeoutMs) {
                        failure = PlaybackOutputFailure("Playback operation timed out: $operationName")
                        running = false
                        thread?.interrupt()
                    }
                }
            } catch (_: InterruptedException) { /* Explicit stop cancels the observer. */ }
        }, "wfas-playback-watchdog").apply { isDaemon = true; start() }
        thread = Thread({ runPlayer() }, "wfas-jitter-player").apply { isDaemon = true; start() }
    }

    private fun runPlayer() {
        var copy = ByteArray(0)
        try {
            operation("START") { output.start() }
            while (running) {
                val now = nowMs()
                if (!primed) {
                    val prerollMs = if (health != null) DesktopPlayoutLimits.startupPrerollMs(targetMs())
                        else maxOf(1, targetMs() / 2)
                    val preroll = minOf(bytesFor(prerollMs), capacityBytes)
                    primed = rendered.bytes() >= preroll || (input.isSilent() && rendered.bytes() > 0)
                    if (!primed) { awaitWake(3); continue }
                }

                val pendingReal = realAudioPending()
                operation("PROGRESS") { output.poll(recoverStall = pendingReal || input.isFlowing(now)) }
                val hadAudio = rendered.bytes() > 0
                val drained = if (hadAudio) operation("WRITE") {
                    pump.drain(targetMs(), { output.bufferedBytes().toLong() / frameBytes }) { pcm, offset, length ->
                        if (rendered.output.takeDiscontinuity() || feedingSilence) {
                            ramp.restart(); feedingSilence = false
                        }
                        if (copy.size < length) copy = ByteArray(length)
                        pcm.copyInto(copy, 0, offset, offset + length)
                        val targetGain = currentVolume()
                        check(targetGain.isFinite() && targetGain in 0f..2f) { "Invalid receiver gain: $targetGain" }
                        // Already admitted native PCM is immutable portably.
                        // Keep both software queues unscaled until this write.
                        // A recovery may restart the ramp. Scale only after that
                        // recovery, and advance it by accepted frames, never retries.
                        val accepted = output.writeSome(copy, 0, length) { count ->
                            gain.applyInPlace(copy, 0, count, targetGain)
                            ramp.applyInPlace(copy, 0, count)
                        }
                        gain.acceptedBytes(accepted)
                        ramp.acceptedBytes(accepted)
                        accepted
                    }
                } else null

                val controlNow = nowMs()
                val nativeFrames = operation("BUFFER") { output.bufferedBytes() }.toLong() / frameBytes
                // Received audio is not deleted to reach a preferred latency.
                // Clock correction handles catch-up; FIFO capacity still bounds
                // memory and explicitly accounts for unavoidable overflow.

                val idle = input.isSilent() && rendered.bytes() == 0
                if (idle) {
                    if (!feedingSilence) {
                        // An ingress silence marker can precede a long queued
                        // real tail. Reset only when playback reaches idle, so
                        // markers cannot repeatedly prevent latency catch-up.
                        synchronized(producer) { resampler.reset() }
                        drift.reset()
                        level.reset()
                    }
                    operation("IDLE_SILENCE") {
                        silenceFeed.refill(targetMs(), { output.bufferedBytes().toLong() / frameBytes }) { pcm, count ->
                            output.writeSome(pcm, 0, count, recoverStall = false)
                        }
                    }
                    feedingSilence = true
                } else if (!hadAudio && input.isFlowing(now) && operation("BUFFER") { output.bufferedBytes() } == 0) {
                    underruns++
                }

                val clockActive = rendered.bytes() > 0 || (!input.isSilent() && (realAudioPending() ||
                    input.isFlowing(controlNow, DesktopPlayoutLimits.LEVEL_WINDOW_MS)))
                if (clockActive) level.observe(controlNow, nativeFrames + if (health != null) rendered.bytes() / frameBytes else 0)
                else level.reset()
                if (controlNow - lastTuneAt >= 200) {
                    val floor = level.clockFrames(sampleRate.toLong() * targetMs() / 1000)
                    val ratio = when {
                        !clockActive -> drift.sample(false, 0, sampleRate.toLong() * targetMs() / 1000)
                        floor != null -> drift.sample(true, floor, sampleRate.toLong() * targetMs() / 1000,
                            warmingUp = level.floorFrames() == null)
                        else -> drift.ratio // Do not steer from a startup burst's peak.
                    }
                    synchronized(producer) { resampler.ratio = ratio }
                    lastTuneAt = controlNow
                }
                if (health != null && now - lastHealthAt >= 1000) {
                    val change = health.sample(now, underruns, input.isFlowing(now), output.stalledMs(),
                        output.failedRecoveries, hasQueuedAudio = realAudioPending())
                    if (change != 0) onTargetChanged(health.targetMs)
                    if (health.rebuildRequested) operation("REBUILD") {
                        output.requestRecovery("Playback health requested a new output")
                    }
                    lastHealthAt = now
                }
                val native = operation("SNAPSHOT") { output.bufferedBytes() }
                latest = Snapshot(targetMs(), rendered.bytes(), native, rendered.discardedBytes(),
                    output.playedFrames(), output.rebuilds, drift.ratio, output.discardedNativeBytes)
                if (now - lastLogAt >= 10_000) {
                    val detail = "targetMs=${latest.targetMs} softwareBytes=${latest.softwareBytes} nativeBytes=${latest.nativeBytes} " +
                        "totalBufferedMs=${bufferedMs()} underruns=$underruns " +
                        "queueDiscardBytes=${latest.discardedBytes} nativeDiscardBytes=${latest.nativeDiscardedBytes} " +
                        "queueTrimBytes=0 queueCapacityMs=$capacityMs " +
                        "nativeWriteCalls=${output.nativeWriteCalls} partialNativeWrites=${output.partialNativeWrites} " +
                        "maxNativeWriteUs=${output.maxNativeWriteNanos / 1000} clockFloorFrames=${level.floorFrames() ?: -1} " +
                        "playedFrames=${latest.playedFrames} rebuilds=${latest.rebuilds} ratio=${latest.driftRatio}"
                    AppDebug.log("[PLAYOUT] $detail")
                    DesktopDiagnostics.event("PLAYOUT", detail)
                    lastLogAt = now
                }
                when {
                    drained?.limited == true -> Thread.yield()
                    rendered.bytes() > 0 -> awaitWake(2)
                    idle -> awaitWake(minOf(10, maxOf(2, targetMs() / 4)).toLong())
                    else -> awaitWake(20)
                }
            }
        } catch (interrupted: InterruptedException) {
            if (running && !stopped) failure = PlaybackOutputFailure("Playback worker interrupted", interrupted)
        } catch (problem: Exception) {
            if (!stopped) {
                failure = problem as? PlaybackOutputFailure ?: PlaybackOutputFailure("Playback worker failed", problem)
                AppDebug.log("[PLAYOUT] ${failure?.message}")
            }
        } finally {
            running = false
            watchdog?.interrupt()
            releaseOutput()
        }
    }

    private fun awaitWake(milliseconds: Long) {
        wake.tryAcquire(milliseconds, TimeUnit.MILLISECONDS)
        wake.drainPermits()
    }

    private fun signal() { if (wake.availablePermits() == 0) wake.release() }

    fun noteAudioFlow(active: Boolean) {
        if (active || stopped) return // Only an admitted PCM frame establishes fresh input.
        synchronized(producer) {
            input.silence()
            silenceTail.begin()?.let { queue.offer(it) }
        }
        signal()
    }

    fun submit(pcm: ByteArray, offset: Int, len: Int, discontinuity: Boolean = false) =
        submitPcm(pcm, offset, len, real = true, discontinuity = discontinuity)

    /** Packet-loss concealment occupies timeline but is not evidence of new source PCM. */
    fun submitConcealment(pcm: ByteArray) = submitPcm(pcm, 0, pcm.size, real = false)

    private fun submitPcm(pcm: ByteArray, offset: Int, len: Int, real: Boolean, discontinuity: Boolean = false) {
        require(offset >= 0 && len >= 0 && offset <= pcm.size - len &&
            offset % frameBytes == 0 && len % frameBytes == 0) { "Incomplete or out-of-bounds PCM frame" }
        throwIfFailed()
        if (!running || stopped || len == 0) return
        synchronized(producer) {
            val boundary = discontinuity || (real && input.isSilent() && hasSubmittedPcm)
            if (real) { input.pcm(nowMs()); hasSubmittedPcm = true }
            val chunk = pcm.copyOfRange(offset, offset + len)
            denoise(chunk)
            if (chunk.isNotEmpty()) {
                silenceTail.remember(chunk)
                queue.offer(chunk, boundary = boundary)
            }
        }
        signal()
    }

    /** Concealment PCM is not a fresh network frame or an intentional-silence signal. */
    fun submitSilence(bytes: Int) {
        require(bytes >= 0)
        val size = minOf(bytes - bytes % frameBytes, capacityBytes)
        if (size > 0) submitPcm(ByteArray(size), 0, size, real = false)
    }

    private fun denoise(chunk: ByteArray) {
        val enabled = NoiseReductionControl.enabled
        if (enabled && !noiseWasEnabled) noiseReducer.reset()
        noiseWasEnabled = enabled
        if (!enabled) return
        noiseReducer.setStrength(NoiseReductionControl.strength / 100f)
        val count = chunk.size / 2
        if (noiseScratch.size < count) noiseScratch = ShortArray(count)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(noiseScratch, 0, count)
        noiseReducer.process(noiseScratch, 0, count)
        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(noiseScratch, 0, count)
    }

    fun throwIfFailed() { failure?.let { throw it } }
    fun bufferedBytes(): Int = latest.softwareBytes + latest.nativeBytes
    fun bufferedMs(): Int = (bufferedBytes().toLong() * 1000 / bytesPerSecond).toInt()
    fun playedFrames(): Long = latest.playedFrames
    internal fun snapshot(): Snapshot = latest

    /** A stopped coroutine must not release its source slot while native close is still blocked. */
    fun isCleanupComplete(): Boolean = stopped && output.isCleanupComplete() &&
        thread?.isAlive != true && watchdog?.isAlive != true

    /** Bounded source-ownership gate; never assume an expired stop() join released the device. */
    fun awaitStopped(timeoutMs: Long): Boolean {
        require(timeoutMs >= 0)
        val started = nowMs()
        while (!isCleanupComplete()) {
            val remaining = timeoutMs - (nowMs() - started)
            if (remaining <= 0) return false
            try { Thread.sleep(minOf(10L, remaining)) }
            catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    private fun releaseOutput() {
        try { output.close() }
        catch (problem: Exception) {
            if (failure == null) failure = PlaybackOutputFailure("Audio output cleanup failed", problem)
            AppDebug.log("[PLAYOUT] audio output cleanup failed: ${problem.message}")
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true; running = false
        signal(); watchdog?.interrupt(); thread?.interrupt()
        // Native release can unblock write(); never wait on a queue lock first.
        val cleanup = Thread({ releaseOutput() }, "wfas-output-release").apply { isDaemon = true; start() }
        try { cleanup.join(300); thread?.join(300); watchdog?.join(300) }
        catch (interrupted: InterruptedException) { Thread.currentThread().interrupt() }
        if (cleanup.isAlive) {
            failure = PlaybackOutputFailure("Audio output cleanup exceeded 300 ms")
            AppDebug.log("[PLAYOUT] ${failure?.message}")
        }
        rendered.clear()
        onTargetChanged(null)
    }
}

/** Correct slow clock error from averaged backlog; instantaneous packet bursts are not pitch commands. */
internal class DesktopPlayoutDrift(private val sampleRate: Int) {
    @Volatile var ratio = 1.0
        private set
    private var averageFrames: Double? = null
    @Synchronized fun reset() { ratio = 1.0; averageFrames = null }
    @Synchronized fun sample(active: Boolean, bufferedFrames: Long, targetFrames: Long, warmingUp: Boolean = false): Double {
        if (!active) { reset(); return ratio }
        require(targetFrames > 0)
        val average = (averageFrames ?: targetFrames.toDouble()) * .9 + bufferedFrames * .1
        averageFrames = average
        // Correct clock error over seconds, independent of the selected latency.
        // Dividing by a20ms target made this loop react20x harder than a400ms
        // target, overshooting into underruns with JavaSound's quantized head.
        val controlFrames = if (warmingUp) minOf(bufferedFrames.toDouble(), targetFrames.toDouble()) else average
        val desired = (1.0 + (controlFrames - targetFrames) / (sampleRate * 4.0)).coerceIn(.992, 1.008)
        // A cold burst may lack one packet's timing margin. Build that reserve
        // promptly without ever accelerating during warmup; steady-state clock
        // correction retains its original smaller slew and absolute bounds.
        val step = if (warmingUp && desired < ratio) .004 else 12.0 / sampleRate
        ratio += (desired - ratio).coerceIn(-step, step)
        return ratio
    }
}

/** Streaming linear resampler substitutes for Android's native playback-rate control. */
internal class DesktopPcmDriftResampler(private val channels: Int) {
    var ratio = 1.0
    private var carry = ShortArray(0)
    private var position = 0.0
    fun reset() { ratio = 1.0; carry = ShortArray(0); position = 0.0 }
    fun process(pcm: ByteArray): ByteArray {
        val frame = channels * 2
        require(channels in 1..2 && pcm.size % frame == 0)
        require(ratio.isFinite() && ratio in .99..1.01)
        val frames = pcm.size / frame
        if (frames == 0 || (ratio == 1.0 && carry.isEmpty())) return pcm
        val total = frames + if (carry.isNotEmpty()) 1 else 0
        val samples = ShortArray(total * channels)
        if (carry.isNotEmpty()) carry.copyInto(samples)
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            .get(samples, total * channels - frames * channels, frames * channels)
        val out = ShortArray((((total - 1).coerceAtLeast(0) / ratio).toInt() + 2) * channels)
        var count = 0
        while (position.toInt() + 1 < total) {
            val index = position.toInt()
            val fraction = position - index
            for (channel in 0 until channels) {
                val first = samples[index * channels + channel].toInt()
                val second = samples[(index + 1) * channels + channel].toInt()
                out[count * channels + channel] = (first + (second - first) * fraction).toInt().toShort()
            }
            count++; position += ratio
        }
        position = (position - (total - 1)).coerceAtLeast(0.0)
        carry = ShortArray(channels) { samples[(total - 1) * channels + it] }
        return ByteArray(count * frame).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(out, 0, count * channels)
        }
    }
}
