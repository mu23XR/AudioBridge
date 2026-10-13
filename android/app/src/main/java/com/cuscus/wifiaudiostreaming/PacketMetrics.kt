package com.cuscus.wifiaudiostreaming

import kotlin.math.abs

/** One sender's sequence history; silence is transport, not a PCM clock tick. */
internal class PacketMetrics(private val sampleRate: Int, private val clockNs: () -> Long = System::nanoTime) {
    private val seen = LongArray(4096) { Long.MIN_VALUE }
    private var highest = -1L
    private var first = -1L
    private var packets = 0L
    private var lost = 0L
    private var reordered = 0L
    private var duplicates = 0L
    private var lastArrival = -1L
    private var lastPosition = -1L
    private var jitter = 0.0
    private var peakJitter = 0.0
    private var intervalSum = 0.0
    private var intervals = 0L

    @Synchronized fun observe(seq: Int, samplePos: Long, silence: Boolean = false): Boolean {
        require(seq in 0..65535)
        val delta = if (highest < 0) 0 else (seq - (highest.toInt() and 65535)) and 65535
        val absolute = when {
            highest < 0 -> seq.toLong()
            delta <= 32768 -> highest + delta
            else -> highest - (65536 - delta)
        }
        val slot = Math.floorMod(absolute, seen.size.toLong()).toInt()
        if (seen[slot] == absolute) { duplicates++; return false }
        if (highest >= 0 && highest - absolute >= seen.size) { reordered++; return false }
        seen[slot] = absolute
        packets++
        val fresh = highest < 0 || absolute > highest
        if (highest < 0) { first = absolute; highest = absolute }
        else if (absolute > highest) { lost += absolute - highest - 1; highest = absolute }
        else {
            reordered++
            if (absolute > first && lost > 0) lost--
        }
        if (!fresh && absolute != first) return false // Late PCM must not rewind playback.
        if (silence || samplePos < 0) { lastArrival = -1; lastPosition = -1; return true }
        val now = clockNs()
        if (lastArrival >= 0) {
            val arrivalDelta = (now - lastArrival) / 1_000_000.0
            val mediaDelta = ((samplePos - lastPosition) and 0xffffffffL) * 1000.0 / sampleRate
            if (arrivalDelta in 0.0..1000.0) {
                intervalSum += arrivalDelta; intervals++
                val deviation = abs(arrivalDelta - mediaDelta)
                jitter += (deviation - jitter) / 16.0
                peakJitter = maxOf(peakJitter, jitter)
            }
        }
        lastArrival = now; lastPosition = samplePos
        return true
    }

    @Synchronized fun snapshot(label: String = "WIFI") = LinkMetrics.Snapshot(
        transport = label, packets = packets, lost = lost, reordered = reordered,
        duplicates = duplicates, jitterMs = jitter, peakJitterMs = peakJitter,
        meanIntervalMs = if (intervals == 0L) 0.0 else intervalSum / intervals
    )
}
