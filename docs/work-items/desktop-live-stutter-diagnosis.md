# PC live receive stutter — 2026-10-24

Status: implementation, source upload, Draft PR#73, CI publication and local deployment complete on fix/desktop-burst-playout. Physical listening/overnight acceptance remains pending in#72/#55; test17 stays usable, running and unchanged. The history below preserves the diagnostic and implementation checkpoints; do not treat automated verification as listening acceptance.

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

Source checkpoint: uploaded commit5f0cad4fb75c0656de0e21d6cac21b8ff24fffe5 and [Draft PR#73](https://github.com/mu23XR/AudioBridge/pull/73), stacked on PC import PR#71. The remote full tree5e0dfc7a569a09d7729c1a707e302e007d0fe651 exactly equals local validated checkpoint687850a; diagnostic local history is preserved on fix/desktop-burst-playout-local-checkpoint. The active fix/desktop-burst-playout now tracks the remote source. [Windows run40093640207](https://github.com/mu23XR/AudioBridge/actions/runs/40093640207) is in progress. No physical playback switch or installed-file change was made. A command-local Git OpenSSL backend avoided this sandbox's Schannel credentials error during public fetch; TLS verification and global Git settings were not weakened.

Remote verification checkpoint: run40093640207 completed both Windows and publication jobs successfully. Exact5f0cad4 source was checked, built, packaged, hashed and uploaded/download-verified by CI. Public [vpn.4 prerelease](https://github.com/mu23XR/AudioBridge/releases/tag/v1.3.1-test.21-vpn.4) is Release422718292, draft=false/prerelease=true, six assets. The immutable tag points exactly to5f0cad4; stable/latest remainsv1.3.1. Local publication request is disarmed for the delivery-record commit. Independent public download verification is running; no audible playback/overnight acceptance is claimed.

## Delivery checkpoint

Independent public download verification passed for all six assets: API digest, manifest, source SHA and ZIP/TAR version/runtime identity. ZIP146508266B SHA256=6bcaacfc0972d3b88786024c998fbc635d3e6c8cfb61070cdbb87924163642ab; TAR146267730B SHA256=7ffd4d45e8d9c74d40d34fd0c18b37e088fd3cdb55e8c425acd712d76f46dd18. Packages are in workspace artifacts/windows/1.3.1-test.21-vpn.4-5f0cad4-ci, with sibling.verification.json; original metadata is not rewritten.253 extracted files in PC/1.3.1-test.21-vpn.4/AudioBridge-Test match the ZIP, and wfas.cmd --version prints1.3.1-test.21-vpn.4/exit0. Desktop AudioBridge PC 测试版 target/working directory/empty arguments were verified; the test17 shortcut and its two existing launcher/JVM processes remain unchanged. No new GUI or audio stream was launched.

The owner's two-version retention policy was applied only after the new installation and old public backup were verified. Old vpn.3's253 image files and six package files matched their original ZIP/API hashes; no old process was using them. The two exact old directories were removed using native PowerShell LiteralPath after absolute-path, Git/reparse and occupancy checks. Its immutable public release and verification metadata remain recoverable; test17/vpn.4 are the only full local PC versions. Plans, execution, deployment and shortcut receipts stay ignored in out/burst-playout and are copied to workspace archive/_inventory/pc-burst-playout-20261024. User settings, source's45 existing changes, Android and signing were not edited. The request flag is false to prevent reusing the tag.

Next product acceptance: when the owner is ready, exit test17 and open the normal desktop test shortcut. Compare the same phone/tablet source and output at the same target; inspect queueDiscardBytes/queueTrimBytes/nativeDiscardBytes and listen for clicks/gaps, then a long wireless run. Actual device packet-arrival timing was not captured, and this delivery does not close all audio faults or existing cold-start cropping.
