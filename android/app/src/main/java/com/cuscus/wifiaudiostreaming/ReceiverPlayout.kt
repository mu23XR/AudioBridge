package com.cuscus.wifiaudiostreaming

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import com.cuscus.wifiaudiostreaming.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong

internal class ReceiverPlayout(
    private val id: String, private val format: StreamAudioFormat,
    private var adaptive: Boolean, latencyMs: Int,
    private val onFailure: (String) -> Unit
) {
    internal data class Snapshot(val targetMs: Int, val totalBufferedMs: Int, val nativeBufferedMs: Int,
        val queuedBytes: Int, val discardedBytes: Long, val playedFrames: Long, val rebuilds: Int)
    private val frame = format.channels * 2
    private val ramp = PcmRamp(format.channels, maxOf(1, format.sampleRate / 200))
    private val silenceTail = PcmSilenceTail(format.channels, maxOf(1, format.sampleRate / 200))
    private val input = ReceiverInputActivity()
    private val initialTarget = if (adaptive) 20 else latencyMs.coerceIn(20, 400)
    private val fifo = PcmQueue(frame, format.sampleRate * frame * 60 / 1000 / frame * frame)
    private val pump = PcmDrainPump(fifo, format.sampleRate, frame)
    private val backlog = ReceiverBacklogPolicy(format.sampleRate, frame)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val pendingConfiguration = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, Int>?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var releaseJob: Job? = null
    private val busyAt = AtomicLong(-1)
    @Volatile private var phase = "WAIT_PCM"
    @Volatile private var stopped = false
    @Volatile var targetMs: Int = initialTarget
        private set
    @Volatile var bufferedMs = 0
        private set
    @Volatile var playedFrames = 0L
        private set
    @Volatile private var track: AudioTrack? = null
    private var rebuilds = 0
    @Volatile private var latestSnapshot = Snapshot(initialTarget, 0, 0, 0, 0, 0, 0)

    internal fun snapshot(): Snapshot = latestSnapshot

    fun offer(pcm: ByteArray) {
        if (stopped || pcm.isEmpty()) return
        input.pcm(SystemClock.elapsedRealtime())
        silenceTail.remember(pcm)
        fifo.offer(pcm)
        if (worker == null) worker = scope.launch { runPlayer() }
        wake.trySend(Unit)
    }
    fun noteSilence() {
        if (stopped) return
        input.silence()
        silenceTail.begin()?.let { fifo.offer(it) }
        wake.trySend(Unit)
    }
    fun configure(adaptive: Boolean, manualMs: Int) {
        pendingConfiguration.set(adaptive to manualMs.coerceIn(20, 400))
        wake.trySend(Unit)
    }

    fun blockedReason(now: Long = SystemClock.elapsedRealtime()): String? {
        val start = busyAt.get()
        return if (start >= 0 && now - start >= 5000L) "PLAYBACK_OPERATION_TIMEOUT phase=$phase" else null
    }

    private inline fun <T> operation(label: String, block: () -> T): T {
        phase = label; busyAt.set(SystemClock.elapsedRealtime())
        return try { block() } finally { busyAt.set(-1); phase = "WAIT_PCM" }
    }

    private fun buildOutput(target: Int): Pair<AudioTrack, PlayoutGovernor> = operation("BUILD") {
        val mask = if (format.channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "Unsupported output format: ${format.describe()}" }
        val bytes = maxOf(min, format.sampleRate * frame * (if (adaptive) 160 else target + 60) / 1000)
            .let { it + (frame - it % frame) % frame }
        val builder = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(format.sampleRate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes)
        if (Build.VERSION.SDK_INT >= 26) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val result = builder.build()
        track = result // Cleanup can find a candidate even if later setup fails.
        ReceiverMixer.register(id, result)
        val governor = PlayoutGovernor(result, format.sampleRate, frame, target,
            "WFAS_SOURCE_$id", adaptive, onDiscontinuity = ramp::restart)
        // Normal/resampled tracks may wait for their entire capacity before
        // starting. Our bounded writer never fills that much, so choose a
        // start threshold explicitly; older APIs need a full initial preroll.
        val startFrames = if (Build.VERSION.SDK_INT >= 31) {
            result.setStartThresholdInFrames(maxOf(1, format.sampleRate * (target / 2) / 1000))
        } else result.bufferSizeInFrames
        val preroll = startFrames * frame
        if (preroll > 0) governor.writePcm(ByteArray(preroll), 0, preroll)
        result.play()
        RuntimeDiagnostics.event("SOURCE_OUTPUT_READY", "source=$id ${format.describe()} targetMs=$target startFrames=$startFrames capacityFrames=${result.bufferCapacityInFrames}")
        result to governor
    }

    private suspend fun runPlayer() {
        var health = PlaybackHealth(adaptive)
        var lastSample = SystemClock.elapsedRealtime()
        var lastTune = lastSample
        var lastLog = lastSample
        var outputCopy = ByteArray(0)
        try {
            val (_, initial) = buildOutput(targetMs)
            var governor = initial
            while (currentCoroutineContext().isActive && !stopped) {
                pendingConfiguration.getAndSet(null)?.let { (automatic, manual) ->
                    adaptive = automatic
                    targetMs = if (automatic) 20 else manual
                    releaseOutput()
                    governor = buildOutput(targetMs).second
                    health = PlaybackHealth(adaptive)
                    backlog.reset()
                    ramp.restart()
                    RuntimeDiagnostics.event("SOURCE_BUFFER_CONFIG", "source=$id adaptive=$automatic manualMs=$manual")
                }
                val now = SystemClock.elapsedRealtime()
                var drained: PcmDrainPump.Result? = null
                backlog.softwareLimit(now, governor.targetMs(), governor.bufferedFrames(), fifo.bytes())?.let { limit ->
                    val discarded = fifo.trimTo(limit)
                    if (discarded > 0) RuntimeDiagnostics.event("SOURCE_BACKLOG_TRIM",
                        "source=$id bytes=$discarded targetMs=${governor.targetMs()} nativeMs=${governor.bufferedMs()}")
                }
                if (fifo.bytes() > 0) {
                    if (fifo.takeDiscontinuity()) ramp.restart()
                    drained = operation("WRITE") {
                        pump.drain(governor.targetMs(), governor::bufferedFrames) { bytes, offset, length ->
                                // Retain original queue samples across zero or
                                // partial writes. Only accepted frames advance
                                // the fade; retrying a tail never scales it twice.
                                if (outputCopy.size < length) outputCopy = ByteArray(length)
                                bytes.copyInto(outputCopy, 0, offset, offset + length)
                                val accepted = governor.writePcm(outputCopy, 0, length) {
                                    ramp.applyInPlace(outputCopy, 0, length)
                                }
                                ramp.acceptedBytes(accepted)
                                accepted
                        }
                    }
                }
                if (now - lastTune >= 200) {
                    operation("RATE") { governor.retune(input.isFlowing(now)) }; lastTune = now
                }
                if (now - lastSample >= 1000) {
                    val t = track ?: break
                    val decision = operation("HEALTH") {
                        health.sample(now, t.underrunCount, input.isFlowing(now),
                            governor.stalledForMs(), governor.recoveryFailures(),
                            hasQueuedAudio = !input.isSilent() && (fifo.bytes() > 0 || governor.bufferedFrames() > 0))
                    }
                    if (decision.targetDeltaMs != 0) governor.retargetMs(decision.targetDeltaMs)
                    if (decision.rebuild) {
                        check(++rebuilds <= 3) { "PLAYBACK_RECOVERY_LIMIT" }
                        targetMs = governor.targetMs()
                        releaseOutput()
                        val (_, replacement) = buildOutput(targetMs)
                        governor = replacement; health = PlaybackHealth(adaptive)
                        backlog.reset()
                        ramp.restart()
                        RuntimeDiagnostics.event("SOURCE_OUTPUT_REBUILD", "source=$id attempts=$rebuilds", true)
                    }
                    lastSample = now
                }
                targetMs = governor.targetMs()
                bufferedMs = governor.bufferedMs() + fifo.bytes() * 1000 / (format.sampleRate * frame)
                playedFrames = operation("HEAD") { (track?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL }
                latestSnapshot = Snapshot(targetMs, bufferedMs, governor.bufferedMs(), fifo.bytes(),
                    fifo.discardedBytes, playedFrames, rebuilds)
                if (now - lastLog >= 10_000) {
                    RuntimeDiagnostics.event("SOURCE_PLAYOUT_STATS", "source=$id targetMs=$targetMs totalBufferedMs=$bufferedMs queueDiscardBytes=${fifo.discardedBytes} rate=${governor.playbackRateHz()}")
                    lastLog = now
                }
                when {
                    // A bounded batch yielded while there is still writable
                    // work. No per-packet sleep limits small-packet throughput.
                    drained?.limited == true -> yield()
                    fifo.bytes() > 0 -> delay(2)
                    else -> withTimeoutOrNull(200) { wake.receive() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            RuntimeDiagnostics.event("SOURCE_OUTPUT_FAILED", "source=$id phase=$phase ${failure.message}", true)
            onFailure("PLAYBACK_FAILURE ${failure.message}")
        } finally { releaseOutput() }
    }

    private fun releaseOutput() {
        val old = synchronized(this) { track.also { track = null } } ?: return
        operation("RELEASE") {
            try { old.pause(); old.flush() } catch (failure: Exception) {
                RuntimeDiagnostics.event("SOURCE_OUTPUT_CLEANUP", "source=$id ${failure.message}")
            } finally {
                try { old.release() } finally { ReceiverMixer.unregister(id, old) }
            }
        }
    }

    fun stop() {
        stopped = true; fifo.clear(); scope.cancel()
        // pause/release from a different worker can interrupt a blocked write.
        // Track ownership is transferred atomically so it is released once.
        releaseJob = CoroutineScope(Dispatchers.IO).launch { releaseOutput() }
    }
    suspend fun awaitStopped(): Boolean = withTimeoutOrNull(1500) {
        worker?.join(); releaseJob?.join(); true
    } ?: false
}
