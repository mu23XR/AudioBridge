/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 */

package com.cuscus.wifiaudiostreaming

import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

class PlayoutGovernor internal constructor(
    private val track: Output,
    private val sampleRate: Int,
    private val frameSize: Int,
    targetLatencyMs: Int,
    private val tag: String,
    private val nowMs: () -> Long,
    private val warn: (String) -> Unit,
    val adaptive: Boolean = false
) {
    internal interface Output {
        val playbackHeadPosition: Int
        fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int): Int
        fun pause()
        fun flush()
        fun play()
        fun setPlaybackRate(rate: Int): Int
    }

    constructor(track: AudioTrack, sampleRate: Int, frameSize: Int,
                targetLatencyMs: Int, tag: String = "PLAYOUT",
                adaptive: Boolean = false) : this(
        object : Output {
            override val playbackHeadPosition get() = track.playbackHeadPosition
            override fun write(pcm: ByteArray, offset: Int, length: Int, mode: Int) =
                track.write(pcm, offset, length, mode)
            override fun pause() = track.pause()
            override fun flush() = track.flush()
            override fun play() = track.play()
            override fun setPlaybackRate(rate: Int) = track.setPlaybackRate(rate)
        }, sampleRate, frameSize, targetLatencyMs, tag,
        { SystemClock.elapsedRealtime() }, { Log.w(tag, it) }, adaptive
    )
    private val framesPerMs = (sampleRate / 1000.0).coerceAtLeast(1.0)

    // Adaptive mode owns a floor-to-ceiling target band that the receiver
    // watchdog drives from underrun telemetry; fixed mode pins one target.
    private val targetFloorMs: Int = if (adaptive) 20 else maxOf(15, targetLatencyMs)
    private val targetCeilingMs: Int = if (adaptive) 80 else maxOf(15, targetLatencyMs)
    private var currentTargetMs: Int = targetLatencyMs.coerceIn(targetFloorMs, targetCeilingMs)
    private var targetFrames: Long = (currentTargetMs * framesPerMs).toLong()
    private var highFrames: Long = targetFrames + (targetFrames / 2).coerceAtLeast((30 * framesPerMs).toLong())
    private var panicFrames: Long = targetFrames + (180 * framesPerMs).toLong()

    @Synchronized fun targetMs(): Int = currentTargetMs

    /**
     * Shift the playout target by [deltaMs] inside the allowed band. Returns
     * true when the target actually moved (the caller logs and timestamps
     * it). Fixed-mode governors have floor == ceiling and never move.
     */
    @Synchronized fun retargetMs(deltaMs: Int): Boolean {
        val next = (currentTargetMs + deltaMs).coerceIn(targetFloorMs, targetCeilingMs)
        if (next == currentTargetMs) return false
        currentTargetMs = next
        targetFrames = (currentTargetMs * framesPerMs).toLong()
        highFrames = targetFrames + (targetFrames / 2).coerceAtLeast((30 * framesPerMs).toLong())
        panicFrames = targetFrames + (180 * framesPerMs).toLong()
        return true
    }

    // Uno scarto ogni tanto e' impercettibile, una raffica no: la correzione fine la
    // fa il playback rate, il drop interviene solo se l'arretrato resta alto.
    private val minDropIntervalMs = 80L

    private var framesWritten = 0L
    private var lastHeadRaw = 0
    private var headWraps = 0L
    private var baseHead = -1L

    private var currentRate = sampleRate
    private var avgBufferedFrames = targetFrames.toDouble()
    private var lastResyncAt = 0L
    private var lastDropAt = 0L
    private var lastLogAt = 0L
    private var lastProgressHead = -1L
    private var lastProgressAt = nowMs()
    private var lastWriteAt = nowMs()
    private var failedLocalRecoveries = 0

    @Synchronized fun recoveryFailures(): Int = failedLocalRecoveries

    /** Never let a paused/full AudioTrack block the UDP receive and PONG loop. */
    @Synchronized fun writePcm(pcm: ByteArray, offset: Int, length: Int): Int {
        val head = playedFrames()
        val now = nowMs()
        if (now - lastWriteAt > 3000) failedLocalRecoveries = 0
        lastWriteAt = now
        if (lastProgressHead >= 0 && head > lastProgressHead) failedLocalRecoveries = 0
        if (head != lastProgressHead || bufferedFrames() == 0L) {
            lastProgressHead = head
            lastProgressAt = now
        } else if (now - lastProgressAt >= 500L) {
            // OEM power management may pause a media track without changing its
            // public playState. Recover only when new PCM needs to be played.
            track.pause()
            track.flush()
            noteReset()
            failedLocalRecoveries++
            track.play()
            lastProgressAt = now
            warn("[PLAYOUT] stalled track recovered; stale PCM flushed")
        }
        val written = track.write(pcm, offset, length, AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0) { "AudioTrack.write failed: $written" }
        noteWritten(written)
        // A short write drops the unaccepted tail instead of moving stale audio
        // into an unbounded queue. Only accepted frames enter latency accounting.
        if (written < length && now - lastLogAt > 1000L) {
            lastLogAt = now
            warn("[PLAYOUT] buffer full: accepted=$written dropped=${length - written}")
        }
        return written
    }

    private fun playedFrames(): Long {
        val raw = track.playbackHeadPosition
        if (raw < lastHeadRaw && lastHeadRaw - raw > Int.MAX_VALUE / 2) headWraps++
        lastHeadRaw = raw
        val abs = (raw.toLong() and 0xFFFFFFFFL) + (headWraps shl 32)
        if (baseHead < 0L) baseHead = abs
        return abs - baseHead
    }

    @Synchronized fun bufferedFrames(): Long = (framesWritten - playedFrames()).coerceAtLeast(0L)

    @Synchronized fun bufferedMs(): Int = (bufferedFrames() / framesPerMs).toInt()

    /**
     * Milliseconds since the playback head last advanced while PCM is still
     * queued. Returns 0 whenever the queue is empty (a silent sender draining
     * the buffer is healthy, not stalled), so only a wedged OEM track that
     * refuses to consume queued PCM keeps this growing.
     */
    @Synchronized fun stalledForMs(): Long {
        val head = playedFrames()
        if (lastProgressHead >= 0 && head > lastProgressHead) {
            failedLocalRecoveries = 0
            lastProgressHead = head
            lastProgressAt = nowMs()
        }
        return if (bufferedFrames() > 0) nowMs() - lastProgressAt else 0L
    }

    @Synchronized fun noteWritten(bytes: Int) {
        if (bytes > 0 && frameSize > 0) framesWritten += bytes / frameSize
    }

    @Synchronized fun noteReset() {
        // flush() resets the playback head; establish a fresh accounting origin.
        lastHeadRaw = 0
        headWraps = 0L
        baseHead = -1L
        framesWritten = 0L
        lastProgressHead = -1L
        lastProgressAt = nowMs()
        avgBufferedFrames = targetFrames.toDouble()
    }

    @Synchronized fun shouldDrop(incomingBytes: Int): Boolean {
        if (frameSize <= 0 || incomingBytes <= 0) return false
        val incoming = incomingBytes / frameSize
        if (avgBufferedFrames + incoming <= highFrames) return false
        if (bufferedFrames() + incoming <= highFrames) return false
        val now = System.currentTimeMillis()
        if (now - lastDropAt < minDropIntervalMs) return false
        lastDropAt = now
        // L'arretrato scartato va tolto subito dalla media, altrimenti la media
        // resta alta e comanda altri scarti che non servono piu'.
        avgBufferedFrames = (avgBufferedFrames - incoming).coerceAtLeast(0.0)
        return true
    }

    @Synchronized fun hardResyncIfNeeded(): Boolean {
        val buffered = bufferedFrames()
        if (buffered <= panicFrames || avgBufferedFrames <= panicFrames) return false
        val now = System.currentTimeMillis()
        if (now - lastResyncAt < 5000L) return false
        lastResyncAt = now
        track.pause()
        track.flush()
        noteReset()
        track.play()
        Log.w(tag, "[PLAYOUT] resync: buffered ${(buffered / framesPerMs).toInt()}ms over budget, buffer flushed")
        return true
    }

    @Synchronized fun retune() {
        val buffered = bufferedFrames().toDouble()
        avgBufferedFrames = avgBufferedFrames * 0.9 + buffered * 0.1
        val errFrames = avgBufferedFrames - targetFrames
        val errRatio = errFrames / targetFrames.toDouble()
        val factor = (1.0 + errRatio * 0.08).coerceIn(0.992, 1.008)
        val newRate = (sampleRate * factor).toInt().coerceAtLeast(1)
        if (kotlin.math.abs(newRate - currentRate) >= 4) {
            runCatching { track.setPlaybackRate(newRate) }
            currentRate = newRate
        }
        val now = System.currentTimeMillis()
        if (now - lastLogAt > 10_000L) {
            lastLogAt = now
            Log.d(tag, "[PLAYOUT] buffered=${(buffered / framesPerMs).toInt()}ms target=${(targetFrames / framesPerMs).toInt()}ms rate=$newRate")
        }
    }
}
