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
        val queuedBytes: Int, val discardedBytes: Long, val playedFrames: Long, val rebuilds: Int,
        val nativeDiscardedFrames: Long = 0, val localRecoveries: Long = 0,
        val outputEpoch: Int = 0, val rawHead: Long = 0, val underruns: Int = 0,
        val startThresholdFrames: Int = 0, val maxLoopGapMs: Long = 0,
        val rateHz: Int = 0, val actualRateHz: Int = 0, val performanceMode: Int = -1)
    private val frame = format.channels * 2
    private val ramp = PcmRamp(format.channels, maxOf(1, format.sampleRate / 200))
    private val silenceTail = PcmSilenceTail(format.channels, maxOf(1, format.sampleRate / 200))
    private val silenceFeed = ReceiverSilenceFeed(format.sampleRate, frame)
    private val input = ReceiverInputActivity()
    private val initialTarget = if (adaptive) 20 else latencyMs.coerceIn(20, 400)
    private val fifo = PcmQueue(frame, format.sampleRate * frame * QUEUE_CAPACITY_MS / 1000 / frame * frame)
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
    private lateinit var governor: PlayoutGovernor
    private var rebuilds = 0
    private var retiredPlayedFrames = 0L
    private var retiredNativeDiscardedFrames = 0L
    private var retiredRecoveries = 0L
    private var outputEpoch = 0
    private var lastSlowOperationAt = -1_000L
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
        val start = SystemClock.elapsedRealtime()
        phase = label; busyAt.set(start)
        return try { block() } finally {
            val end = SystemClock.elapsedRealtime()
            if (end - start >= 40 && end - lastSlowOperationAt >= 1_000) {
                lastSlowOperationAt = end
                RuntimeDiagnostics.event("SOURCE_OUTPUT_OPERATION_SLOW",
                    "source=$id operation=$label durationMs=${end - start}")
            }
            busyAt.set(-1); phase = "WAIT_PCM"
        }
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
        // FAST tracks reject setPlaybackRate, disabling the clock-drift
        // correction this live stream needs. Keep a normal rate-adjustable
        // track; admission and startup threshold still bound queued latency.
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
        if (preroll > 0) check(governor.writePcm(ByteArray(preroll), 0, preroll) == preroll) {
            "AudioTrack startup priming was incomplete"
        }
        result.play()
        outputEpoch++
        RuntimeDiagnostics.event("SOURCE_OUTPUT_READY", "source=$id ${format.describe()} targetMs=$target startFrames=$startFrames capacityFrames=${result.bufferCapacityInFrames} minBytes=$min rateHz=${result.playbackRate} performanceMode=${if (Build.VERSION.SDK_INT >= 26) result.performanceMode else -1}")
        result to governor
    }

    private suspend fun runPlayer() {
        var health = PlaybackHealth(adaptive)
        var lastSample = SystemClock.elapsedRealtime()
        var lastTune = lastSample
        var lastLog = lastSample
        var outputCopy = ByteArray(0)
        var feedingSilence = false
        var previousLoopAt = SystemClock.elapsedRealtime()
        var maxLoopGapMs = 0L
        var lastGapLogAt = -1_000L
        try {
            val (_, initial) = buildOutput(targetMs)
            governor = initial
            while (currentCoroutineContext().isActive && !stopped) {
                pendingConfiguration.getAndSet(null)?.let { (automatic, manual) ->
                    adaptive = automatic
                    targetMs = if (automatic) 20 else manual
                    retireProgress()
                    releaseOutput()
                    governor = buildOutput(targetMs).second
                    feedingSilence = false
                    health = PlaybackHealth(adaptive)
                    backlog.reset()
                    ramp.restart()
                    RuntimeDiagnostics.event("SOURCE_BUFFER_CONFIG", "source=$id adaptive=$automatic manualMs=$manual")
                }
                val now = SystemClock.elapsedRealtime()
                val loopGap = now - previousLoopAt
                previousLoopAt = now
                maxLoopGapMs = maxOf(maxLoopGapMs, loopGap)
                if (loopGap >= 40 && fifo.bytes() > 0 && now - lastGapLogAt >= 1_000) {
                    lastGapLogAt = now
                    RuntimeDiagnostics.event("SOURCE_OUTPUT_LOOP_GAP",
                        "source=$id gapMs=$loopGap queuedBytes=${fifo.bytes()} thread=${Thread.currentThread().name}")
                }
                var drained: PcmDrainPump.Result? = null
                if (fifo.bytes() > 0) {
                    // Admission can be full while the output is stalled. Check
                    // progress independently, before the pump's headroom gate.
                    operation("RECOVER") { governor.recoverStalledOutput() }
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
                backlog.softwareLimit(now, fifo.bytes())?.let { limit ->
                    val discarded = fifo.trimTo(limit)
                    if (discarded > 0) RuntimeDiagnostics.event("SOURCE_BACKLOG_TRIM",
                        "source=$id bytes=$discarded targetMs=${governor.targetMs()} nativeMs=${governor.bufferedMs()}")
                }
                // Preserve an active sink through intentional silence. Refill
                // at most10ms per tick, only up to the current target. The same
                // accounting includes zeros, so returning PCM finds no hidden
                //50ms block outside its configured latency budget.
                val idle = input.isSilent() && fifo.bytes() == 0
                if (idle) {
                    val count = silenceFeed.bytesToWrite(governor.targetMs(), governor.bufferedFrames())
                    if (count > 0) operation("IDLE_SILENCE") {
                        governor.writePcm(silenceFeed.pcm, 0, count, recoverStall = false)
                    }
                    if (!feedingSilence) RuntimeDiagnostics.event("SOURCE_OUTPUT_IDLE", "source=$id")
                    feedingSilence = true
                } else if (feedingSilence && !input.isSilent()) {
                    feedingSilence = false
                    RuntimeDiagnostics.event("SOURCE_OUTPUT_RESUME", "source=$id")
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
                        retireProgress()
                        releaseOutput()
                        val (_, replacement) = buildOutput(targetMs)
                        governor = replacement; health = PlaybackHealth(adaptive)
                        feedingSilence = false
                        backlog.reset()
                        ramp.restart()
                        RuntimeDiagnostics.event("SOURCE_OUTPUT_REBUILD", "source=$id attempts=$rebuilds", true)
                    }
                    lastSample = now
                }
                targetMs = governor.targetMs()
                bufferedMs = governor.bufferedMs() + fifo.bytes() * 1000 / (format.sampleRate * frame)
                playedFrames = operation("HEAD") { retiredPlayedFrames + governor.totalPlayedFrames() }
                val t = checkNotNull(track)
                latestSnapshot = Snapshot(targetMs, bufferedMs, governor.bufferedMs(), fifo.bytes(),
                    fifo.discardedBytes, playedFrames, rebuilds,
                    retiredNativeDiscardedFrames + governor.nativeDiscardedFrames(),
                    retiredRecoveries + governor.localRecoveryCount(), outputEpoch,
                    t.playbackHeadPosition.toLong() and 0xffffffffL, t.underrunCount,
                    if (Build.VERSION.SDK_INT >= 31) t.startThresholdInFrames else t.bufferSizeInFrames,
                    maxLoopGapMs, governor.playbackRateHz(), t.playbackRate,
                    if (Build.VERSION.SDK_INT >= 26) t.performanceMode else -1)
                if (now - lastLog >= 10_000) {
                    RuntimeDiagnostics.event("SOURCE_PLAYOUT_STATS", "source=$id targetMs=$targetMs totalBufferedMs=$bufferedMs queueDiscardBytes=${fifo.discardedBytes} nativeDiscardedFrames=${latestSnapshot.nativeDiscardedFrames} totalPlayedFrames=$playedFrames localRecoveries=${latestSnapshot.localRecoveries} epoch=$outputEpoch rate=${latestSnapshot.rateHz} actualRate=${latestSnapshot.actualRateHz} maxLoopGapMs=$maxLoopGapMs")
                    lastLog = now
                }
                when {
                    // A bounded batch yielded while there is still writable
                    // work. No per-packet sleep limits small-packet throughput.
                    drained?.limited == true -> yield()
                    fifo.bytes() > 0 -> delay(2)
                    idle -> withTimeoutOrNull(minOf(10, maxOf(2, targetMs / 4)).toLong()) { wake.receive() }
                    else -> withTimeoutOrNull(200) { wake.receive() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // stop() deliberately releases on another worker to interrupt a
            // stuck native call. Its resulting exception is cancellation.
            if (!stopped && currentCoroutineContext().isActive) {
                RuntimeDiagnostics.event("SOURCE_OUTPUT_FAILED", "source=$id phase=$phase ${failure.message}", true)
                onFailure("PLAYBACK_FAILURE ${failure.message}")
            }
        } finally { releaseOutput() }
    }

    private fun retireProgress() {
        // Freeze the old epoch before its final head sample. Its unplayed
        // tail is loss on replacement, never successful playback progress.
        operation("RETIRE") { checkNotNull(track).pause() }
        retiredPlayedFrames += governor.totalPlayedFrames()
        retiredNativeDiscardedFrames += governor.nativeDiscardedFrames() + governor.bufferedFrames()
        retiredRecoveries += governor.localRecoveryCount()
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

    internal companion object {
        /**
         * Hard software-queue ceiling. The backlog policy derives its total
         * latency bound from this, so the two share one number.
         */
        const val QUEUE_CAPACITY_MS = 60

        /**
         * Backlog trimming must leave headroom below [QUEUE_CAPACITY_MS]: the
         * producer keeps queueing while the pump drains, so a queue trimmed
         * exactly to the hard capacity would land on the bound during the next
         * burst instead of staying under it.
         */
        const val QUEUE_TRIM_TRIGGER_MS = 50
        const val QUEUE_RETENTION_MS = 40
    }
}
