# Android test20 audio continuity and peak protection

Status: implemented locally; final integrated JVM/lint and muted native checks passed.
Issues #45 and #42; Draft PR #40, branch `fix/android-multisource-recovery`.
Debug fixtures run only on the existing emulator, no real-device interaction or installation.

## Evidence and scope

The test19 receiver completes a nominal 5 ms fade within one queue chunk, even
when that chunk is shorter than 5 ms. An internal governor flush also lacks an
explicit fade notification. These are confirmed continuity risks in the code;
they are not proof of the cause of the user's long-duration noise or silence.
Gain above unity previously hard-clipped PCM16 samples. Do not change the user's
latency, packet size, drift-correction limits or saved audio settings.

## Implementation

- `PcmRamp` keeps a frame position for the complete 5 ms duration. Stereo
  channels share the same frame gain. Only bytes accepted by output advance the
  position; short chunks continue the ramp rather than ending it early.
- `ReceiverPlayout` keeps queued PCM unchanged and prepares each device write in
  a reusable output scratch buffer. Zero writes and partial-write tails cannot
  receive duplicate scaling. Queue overflow bounds and accounting are unchanged.
- `PlayoutGovernor` notifies discontinuities through its reset path. A prepare
  callback runs after any local recovery flush and before the actual write, so
  the first resumed samples receive the restarted ramp.
- Java `PcmGainLimiter.applyInPlace` applies gain in the existing 0–2 range. Unity
  leaves every original byte unchanged. Above unity, a continuous soft knee
  compresses peaks toward the PCM16 limits rather than flat-clipping them. Its
  knee width approaches zero at unity and response does not depend on packet
  boundaries. This is nonlinear peak protection, not lossless amplification.
- The helper returns the number of softened samples. `countWouldClip` is an
  optional before-processing count of hard-gain overflow risk. Integration in
  Shizuku and MediaProjection senders is owned by the remote-volume stage.

## Validation and limits

New JVM regressions cover cross-chunk stereo fades, zero/partial writes with
unchanged original PCM, recovery notification before the first resumed write,
sample alignment, every PCM16 value at unity, linear quiet gain, bounded monotonic
peak compression, counters, packet independence and near-unity continuity.
Java helper compiles independently with `javac`; 90 integrated JVM tests (including
these regressions), zero-error lint and debug/helper builds passed. Muted multi-source
native fixtures passed on the existing emulator; real-device listening remains unverified.

No new dependency, extra buffering/lookahead, wire change or timing preset was
introduced. A static soft knee can introduce harmonic coloration at elevated
gain; it avoids block-driven pumping but is not a mastering limiter. Real-device
listening and naturally occurring long-run diagnostics remain acceptance work.
