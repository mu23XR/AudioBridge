# PC live receive stutter — 2026-10-24

Status: implementation authorized by the owner's follow-up to continue fixing test21. Work continues on fix/desktop-burst-playout from diagnostic checkpoint2264179; test17 remains usable and untouched. Focused defect https://github.com/mu23XR/AudioBridge/issues/72; acceptance tracker #55 stays open. Do not treat prior CI as listening acceptance.

## Implementation checkpoint

Increase the WFAS FIFO allocation from60 to400ms without changing its40ms maximum startup preroll or native admission target. Replace immediate50→40ms software-only trimming with a software+native total budget: target+60ms tolerates fresh batching; only continuous excess for250ms permits trimming, keeping total at target+15ms. Writable pump continuations drain first; the hard FIFO ceiling still applies. PLAYOUT records now separate stale-trim bytes and allocated queue capacity from overall queue loss.

Clock feedback observes time-weighted software+native backlog, not a single native-only sample every200ms. Periodic arrivals can otherwise always coincide with the sample, and a larger FIFO can hide audio from clock control. Existing bounded ratio/slew and four-second normalization remain. Tests cover the real concurrent player, grace/reset/continuation and combined-budget trimming, phase-locked arrivals and finite output. Verification is in progress; no candidate is delivered yet.

## Reproduction checkpoint

The new DesktopBurstPlayoutChecks exercises the real DesktopJitterAudioPlayer with a memory SourceDataLine. A latch pauses the first native write while the receive thread admits14 ordinary345-frame packets (100.625ms), then releases the writer. On unchanged product source2264179, checkReceiveBurst fails: expected21240B, discarded7800B (40.625ms of real PCM). The failure is preserved in ignored out/burst-playout/red.log; the accepted-byte snapshot is taken as soon as a positive loss counter appears, not as a final playback total. No network or real audio device is involved. The compiler daemon could not connect in this sandbox and used its explicit fallback; test compilation completed and the failure is the PCM assertion, not the environment warning. Subsequent runs use in-process Kotlin compilation.

Also extend finite-device continuity coverage to100ms arrival batches at manual63/191/400ms; verify coordinated backlog limits and clock feedback as implementation proceeds. This proves a queue-capacity defect, while the exact live batching/scheduling trigger remains unmeasured.

Baseline: clean desktop-publish /publish/desktop-receive-preview@0c7394e, published PC1.3.1-test.21-vpn.3 from3fc718a. Diagnostic bookkeeping is isolated on diagnose/desktop-stutter-20261024. Android, the known-good test17 copy, the preview installation and user settings remain unchanged.

## Live evidence

- Process31172 is the JVM child of launcher35724; both paths identify the deployed vpn.3. The parent/child pair is not evidence of two audio receivers. One active source/player appears in thread snapshots.
- Existing app diagnostic journal records48kHz/16bit/stereo output and current stream transport/queue summaries. Do not commit raw journals, addresses, credentials or PCM. Bounded local thread snapshots are in ignored out/live-stutter-20261024-31172.
- At manual target63ms, a179.975s window accumulated9657136 discarded queue bytes (50.2976s at192000B/s) and105 underrun observations; source loss starts at0%, remains below0.3% for most of the window, rises to1.54% near its end. Native rebuilds/discards remain0. Native played-frame advancement is about71% of wall-clock48kHz.
- A later manual191ms output still accumulates queue loss: about37.0763s discarded over280.043s,42 underrun observations, transport loss0.31%, no native recovery. These counters demonstrate real application-side PCM loss, not merely reported network loss or latency display.
- Three live thread snapshots show the receiver waiting on UDP and playback worker on its normal semaphore; none proves a permanently blocked SourceDataLine.write. Packet-arrival batching, scheduling and output progress still need separation.

## Relevant code and next check

DesktopJitterAudioPlayer fixes software capacity at60ms for WFAS even when manual native target is191ms. PcmQueue drops oldest real PCM on overflow; ReceiverBacklogPolicy trims from above50ms down to40ms. Existing finite-device continuity tests use1ms ticks,7.2ms packets and0–2ms delivery jitter, and explicitly drain all continuation batches before next tick. They do not model the observed larger delivery/scheduling variations. This is a candidate mechanism, not yet a proven origin of every burst.

The owner asked to finish reading promptly so they can use test17. Evidence was saved at2026-10-24 19:40:42+08:00; the live session no longer needs to be held for this investigation. The final191ms segment spans720.435s with24650248 queue bytes discarded (128.3867s),118 underrun observations and0 rebuilds. The original journal was copied only to the ignored local diagnostic directory after credential/address redaction; summary.json keeps numeric segment evidence, and three local thread snapshots remain available. No PCM was captured.

PktMon's read-only status/filter queries failed with driver access denied even outside the restricted sandbox; no filter or capture was started. Therefore actual packet-arrival burst length has not been measured and must not be presented as observed. Current frame-loss counters prove application-side removal; they do not alone distinguish network batching, OS scheduling, audio-output timing or transitions into silence as its trigger.

## Concrete repair direction and acceptance

1. Rework software-queue capacity and stale trimming together with the native target and arrival timing. A real burst waiting for writable native space must not be dropped merely because the software FIFO passed50–60ms. Keep a bounded end-to-end backlog rather than simply replacing60 with an arbitrary larger constant.
2. Reproduce loss-free but batched arrivals, output progress quantization and40–100ms scheduling stalls in finite-device regression cases. Check continuity and separate real dropped PCM from silence; current0–2ms-jitter fixture is insufficient for this acceptance failure.
3. Validate the actual Windows receive path using the same source, output and audio segment, with queue-drop/underrun counters and a test17 comparison before claiming a fix or publishing a new candidate. Preserve test17 as the usable fallback.

The initial investigation changed only bookkeeping and ignored local evidence. The subsequent owner-authorized implementation above changes PC playout and regressions. No user settings, installed application, running process, Android source or device has yet been changed. The exact live trigger remains unresolved; the controlled reproduction identifies a real bug but does not declare all listening issues fixed.

## Local verification checkpoint

Full Desktop check and focused actual-player check pass in out/burst-playout/green-second.log: expected21240B, accepted21240B, discarded0;16 finite-clock scenarios each run90 virtual seconds, including100ms arrivals at63/191/400ms,±250ppm,40ms player pauses at63ms and100ms pauses at191ms. Every scenario reports0 queue discards,0 waveform discontinuities and0 underrun frames. Original protocol, source ownership, silence/resume, soft recovery and WASAPI native lifecycle checks also pass. The first combined run was blocked by Windows PowerShell script policy; rerun inherited process-only PSExecutionPolicyPreference=Bypass without changing system policy. Kotlin compilation uses the existing JDK17/toolchain and in-process strategy; no new runtime was downloaded. Java Preferences registry warnings are environment warnings, not a passing listening test.

Candidate version is1.3.1-test.21-vpn.4, with a new explicit CI publication request. Source upload, Draft PR, clean CI packaging/download verification and daily deployment are pending. test17 remains the owner's fallback. Published vpn.3/tag and Android remain immutable.
