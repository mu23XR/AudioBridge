# Testing Policy

## Required CI

Every pull request that changes relevant files should run:

- Android unit/build validation
- Windows Desktop build validation
- governance checks

## Android manual regression checklist

For parallel testing, keep the existing test.16/stable installation. Local debug uses `io.github.mu23xr.audiobridge.debug`; signed CI preview uses `io.github.mu23xr.audiobridge.test`; both are labeled AudioBridge Test. Permissions, settings and Shizuku access must be granted separately. Stop the existing app's active transmission before starting a test sender; do not run two captures on the same device. Confirm the permanent package/version remains installed before and after testing. Verify adaptive/manual choice, exact numeric entry, reconnect-to-apply behavior, and one mode-control/streaming notification during WFAS operation; tap the runtime notification to adjust volume.

For changes touching runtime/services/audio:

1. Start SEND using Shizuku.
2. Confirm local playback behavior is as intended.
3. Lock screen and confirm streaming continues.
4. Return with Home/backgrounding and confirm streaming continues.
5. Swipe AudioBridge from Recents.
6. Confirm sender/receiver stops.
7. Confirm Shizuku UserService/AudioPolicy no longer owns the route.
8. Before SEND, set the phone/tablet speaker media volume to a distinctive value (for example 0%).
9. While SEND is active, set the sender software/transmission volume to a different value (for example 80%).
10. Swipe AudioBridge from Recents without pressing OFF first.
11. Confirm the receiver stops receiving audio.
12. Confirm the phone/tablet returns to its original speaker route and original route volume (0% in the example), not the 80% transmission/virtual-route value.
13. Confirm media volume buttons control the local output again.
14. Reopen and reconnect; ensure no stale state remains.
11. Test RECEIVE background/reconnect.
12. Test SEND <-> RECEIVE role switching.

## Protocol regression

When changing WFAS:
- Android -> Android
- Desktop -> Android
- Android -> Desktop
- mismatch behavior
- reconnect/liveness
- BYE/task teardown
- packet loss/reordering where applicable

Wire-incompatible changes require a protocol-version bump.

## test20 focused fixtures

JVM tests cover optional authenticated volume ordering/replay, legacy capability fallback,
route selection, packet classification, PCM fades/peak protection and display mapping.
Run Android lint plus debug/helper builds before native fixtures. Use an isolated emulator
debug package; do not run the fixtures against an active real-device audio session.

Core classes: MultiSourceDeviceTest, RemoteVolumeDeviceTest, ReceiverRetryDeviceTest,
BackgroundIntentDeviceTest and DiagnosticShellExportDeviceTest. The shell-export fixture
uses a separate test-APK UID probe to prove ordinary callers are denied, beyond pure UID tests.

NotificationPermissionDeviceTest is an opt-in standalone run on an isolated debug emulator:
host revokes POST_NOTIFICATIONS before launching instrumentation with
allowNotificationFixture=true, and restores permission in a host finally block. Never revoke
the permission from the instrumentation process itself. It waits 35 seconds to cover pending
foreground promotion deadlines; no source capture/output is started.

DestinationPathDeviceTest requires fixtureIp and fixturePort for a controlled host sender
through a real managed network (not loopback). The sender replies with legacy HELLO_ACK;v=2
and valid header-only silence; a separate port injects BYE/PING/sequence30000 packets.
Assert continued valid reception, isolated sequence history, unsupported old-peer remote
volume and explicit cleanup. Missing fixture arguments skip; skipped fixtures are not passes.

Run VolumePersistenceDeviceTest#saveConcurrentChangesForSeparateProcessRestore and
#restoreOnNewApplicationProcessThenReturnOriginalValues in separate instrumentation invocations,
in that order. The second verifies Application restoration before any setter and returns
the debug application's prior values. These quiet fixtures do not establish physical sender
capture, OEM key arbitration, encrypted multicast delivery or prolonged subjective quality.


## Silent-source durability

### test21 review corrections (emulator only)

ReceiverBufferPolicyTest must exercise the real PcmQueue→pump→policy→trim path:
bounded bursts drain before trimming, a residual50–60ms queue reaches the soft
trigger and retains40ms, cooldown never removes the60ms hard safety bound, and
trim/overflow boundaries signal a frame-based ramp. Numeric queues above the
hard capacity cannot serve as the sole acceptance test.

A simulated42ms device call must exhaust the pump budget while leaving native
headroom; writable continuation retains and drains the exact PCM before soft
trimming. A later admission-blocked batch still trims, without losing cooldown
eligibility. PcmQueueInFlightTest covers overflow/trim during an unlocked write:
count only unaccepted evicted bytes, keep loss monotonic, preserve partial tails
and queue bounds, distinguish counted/stop clears, and release writer ownership
after invalid/throwing output. Neither fix relaxes native progress/loss limits.

AudioSilenceRegressionTest covers a frozen full output independently of write
admission, one-time startup-threshold priming after a flush, and explicit failure
of incomplete priming. CuePlaybackLifetimeTest covers owned player lifetime,
completion/error/timeout races and late creation using fake players/timers.
The governor regressions also cover cumulative progress through repeated flushes,
32-bit wrap/stale samples, native-tail loss, and bounded two-way clock correction.

ReceiverPlayoutDeviceTest requires a guarded debug emulator and explicit
allowPlayoutFixture=true. Preserve the existing immediate-resume90% progress and
100ms discard limits. Silence phases must now also prove advancing native heads;
the additional35s sole-source silence case checks native occupancy stays at the
configured target, without rebuild/adaptive inflation, then measures returning
PCM immediately. Zero-filled output frames count in head/output-written counters;
they are not evidence that source PCM arrived. Actual sounds/capture/overnight
OEM behavior are separate acceptance work.

Fixed manual20ms has matching sole-output silence/resume coverage. Native clock
correction must be read back from AudioTrack.playbackRate, not inferred solely
from the governor's requested value. Apply the unchanged100ms discard allowance
to software drops plus flushed native frames. Playback head totals remain
monotonic across output epochs, without counting dropped/submitted audio as
played. Normal stop must release outputs without reporting a playback failure.
Record whether each emulator run begins from a cold audio sink; a hot retry
cannot silently replace a failing cold-start report. A normal rate-adjustable
track may change system mixer latency compared with FAST, so application queue
bounds do not establish equal acoustic latency.

The reusable `android-native.yml` check builds clean committed debug sources and
runs the 12-method contract, including the receiver's rewritten-reply-port fixture,
on its own API35 x86_64 SDK emulator. It retains
a clocked PulseAudio null sink (audio enabled), rejects pre-existing devices,
validates emulator/package/source identity, counts starts/completions/skips and
uploads failure diagnostics as well as successful ones. It uses no signing
secrets, publishes no APK, and never substitutes a different emulator's pass for
a recorded MuMu failure. `scripts/run_android_native.py` refuses local/non-CI
execution so it cannot accidentally select a user's wireless-debugging device.

Do not target physical devices or MuMu instance1. This task owns only the named
MuMu instance2, AudioBridge-test21; never restart shared ADB servers. Missing
opt-in arguments or skipped methods are not successful regression evidence.

The artifact tool uses only Python's standard library. Run its mismatch/clean-tree
tests with `python -m unittest discover -s scripts/tests -v`. These tests do not
sign/build APKs or establish acoustic quality.

### 2.0.1 follow-up regressions

DesktopAudioInitializationChecks holds the initialization-accounting callback
with a latch: an open failure must not escape while that owner remains active.
Successful delivery retains its original ordering; timeout/late-close, callback
failure and the independent three-attempt lifecycle budget remain covered.

ReceiverDrainOverheadTest exercises the real PCM queue, drain pump and governor
against a bounded-cost virtual playback-head getter. It checks single-observation
recovery decisions and the throughput effect of redundant per-packet health
queries. This deterministic model does not identify the cause of a real host
scheduling pause. The unchanged twelve-method native suite must still meet its
60ms FIFO, target-plus90ms total queue, immediate resume/progress and100ms loss
limits using a verified active native audio path. Do not widen these bounds or
replace a retained failed run with a later passing result.

1. Start a Shizuku SEND/RECEIVE session.
2. Stop all media playback on the sender without pressing OFF.
3. Leave the source silent long enough to cross the old 30-second timeout.
4. Confirm the session remains connected.
5. Resume media playback and confirm audio returns without reconnecting.

## Recents removal

With SEND active, swipe the app from Recents without pressing OFF first.
Confirm the receiver stops promptly and the sender's local audio route/volume is restored.
This test is invalid if the service is configured with `stopWithTask=true`; the runtime
services are intentionally configured to receive `onTaskRemoved()`.
