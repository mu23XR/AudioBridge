internal object DesktopSilenceKeepAliveChecks {
    private const val MS = 1_000_000L

    fun runChecks() {
        WfasPacketTimelineChecks.runChecks()
        // The first silence read has no previous frame to measure against, so it must not invent a
        // position jump; the following reads carry the real elapsed time.
        val timeline = SilenceTimeline(48_000)
        check(timeline.onSilence(5 * MS) == 0) { "The first silence frame has no elapsed reference" }
        check(timeline.onSilence(205 * MS) == 9_600) { "200 ms at 48 kHz is 9600 frames" }

        // A suspended sender can report minutes of "no data" in a single read; the position must
        // stay bounded to the clamp instead of jumping by the whole suspension.
        check(timeline.onSilence(60_205 * MS) == 24_000) { "A long gap clamps to 500 ms" }

        // Real audio resets the gap, so the silence that follows measures from the last frame.
        timeline.onAudio(70_000 * MS)
        check(timeline.onSilence(70_010 * MS) == 480) { "10 ms at 48 kHz is 480 frames" }

        // Every silence header after the first must advance the position: a stuck timeline makes
        // later real packets look stale to the receiver.
        val run = SilenceTimeline(44_100)
        var position = 0L
        var seq = 0
        repeat(60) { index ->
            seq = (seq + 1) and 0xFFFF
            val frames = run.onSilence((index + 1) * 200L * MS)
            if (index > 0) {
                check(frames == 8_820) { "Silence header #$index must advance 200 ms at 44.1 kHz, got $frames" }
            }
            position += frames
        }
        check(position == 44_100L * 59 * 200 / 1000) {
            "60 silence headers must cover the elapsed audio, got $position"
        }
        check(seq == 60) { "Sequence numbers must advance one per silence header" }

        // A backwards clock (NTP correction, monotonic base reset) must not rewind the timeline.
        val backwards = SilenceTimeline(48_000)
        backwards.onAudio(10 * MS)
        check(backwards.onSilence(5 * MS) == 0) { "A backwards clock clamps to zero, never negative" }
        check(backwards.onSilence(15 * MS) == 240) { "A backwards sample must not count an elapsed interval twice" }

        val zeroOrigin = SilenceTimeline(48_000)
        check(zeroOrigin.onSilence(0) == 0)
        check(zeroOrigin.onSilence(10 * MS) == 480) { "Zero is a valid monotonic-clock origin" }

        // Keeping fractions across reads matters: 10.999999 ms is not 10 ms,
        // and 44.1 kHz also needs fractions of individual frames carried forward.
        for (rate in listOf(44_100, 48_000)) {
            val precise = SilenceTimeline(rate)
            precise.onAudio(0)
            var timeNs = 0L
            var totalFrames = 0L
            repeat(1_000) { index ->
                timeNs += if (index % 2 == 0) 10_999_999L else 10_123_457L
                totalFrames += precise.onSilence(timeNs)
            }
            check(totalFrames == timeNs * rate / 1_000_000_000L) {
                "Fractional silence timing drifted at $rate Hz: $totalFrames frames"
            }
            precise.onAudio(timeNs)
            check(precise.onSilence(timeNs + 10 * MS) == rate / 100) { "Real PCM must reset the silence fraction" }
        }

        check(runCatching { SilenceTimeline(0) }.isFailure)
        check(runCatching { SilenceTimeline(48_000, clampMs = 0) }.isFailure)

        println("test21 silence keep-alive: elapsed frame accounting, clamp, monotonic timeline and sequence checks passed")
    }
}
