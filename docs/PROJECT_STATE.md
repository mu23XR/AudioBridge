# Project State

Last governance refresh: 2026-09-29

## Android multi-source receiver and runtime recovery

2026-10-18 test21 review corrections: Draft PR40/implementation9ecbbc4 fixes observed
playback accounting across flush/replacement, native tail-loss reporting,
normal-stop false errors, actual clock-rate control, same-tick bounded silence
refill and first-PCM fade after silence. Existing queue/cue/provenance corrections
remain included.20ms adaptive target/admission and all acceptance bounds remain
unchanged; normal rate-adjustable output can differ from FAST in mixer latency.
Local157 JVM (147 committed-source +10 pending VPN),22 Python checks and zero-error
lint/debug/helper builds pass after two further confirmed corrections: let a
writable time/batch-limited pump continue before soft trim, and count in-flight
queue eviction only after actual accepted bytes are known. Twelve new scheduling/
concurrency regressions pass; all audio bounds remain unchanged. Exact9ecbbc4
Android/Windows/governance/permanent-signed artifact-only and strict standard
API35 native CI all pass. Native39119476807 executes11/11,0skips on tested merge
7e60dbbdd47ea27d446868a0d84a187b3b871c3a. Fresh signed artifact12133678144/source
9ecbbc4/package.test/version1.3.1-test.21/code610181030 has downloaded checksum and
permanent signer verified under ignored out/test21-ci-9ecbbc4. Publish is skipped;
latest Release remains test20. Earlier ce353f6 failures, a4dd84611/11 and MuMu9/12
remain separate evidence, with no post-fix MuMu rerun. Acoustic latency, API<31/
OEM/overnight and separate RTP/Snapcast output remain unverified/outside scope.
No Release/publication/installation; old mismatched bundle stays unverified.
Own2 is closed/restored2CPU; no1/global-ADB/physical-device action. Keep runtime
Issues5/14/45/51/53/54 open and preserve separate pending VPN changes. See
docs/work-items/android-test21-review-repair.md and the2026-10-18 adaptive handoff.

2026-10-15 repair execution: user authorized the focused test20 playback/cue repair and requires emulator-only validation, with no physical-device connections or changes. Bounded batch scheduling, total-backlog disposal separated from clock correction, explicit-silence continuity/health and generation-bound connection cues are implemented locally.106 JVM tests, debug/helper builds and zero-error lint pass; independent review is clear after correcting the empty-FIFO debounce edge. MuMu native fixtures/CI delivery are pending; an offline emulator stopped the first native attempt before installs/tests. See docs/work-items/android-test21-playout.md. No stable promotion or runtime issue closure.

2026-10-15 acceptance follow-up: user confirms test20 and reports higher latency, more stutter/pops and absent connection sound. Treat playback acceptance as failed, not completed by CI/native fixtures. Read-only comparison finds a single-chunk-per-2ms consumer bottleneck and a separate software FIFO omitted from governor feedback, both introduced in test19 and retained in test20; these are confirmed implementation risks, not a proven explanation of this incident. All sources use the unified receiver, but the mixer adds no common buffer/wait barrier. A connected source's announcement flag is not reset on explicit reconnect, which can suppress its cue. No physical devices were reachable through available cached/discovered endpoints this turn, so active source count, settings and real buffer/underrun evidence remain unknown. User confirmation supersedes the earlier pre-installation checkpoint below; installation was not independently queried this turn. No product/device/external-state changes. See docs/handoffs/2026-10-15-test20-playout-regression.md and the active test20 work item; Draft PR #40 and runtime issues remain open.

2026-10-14 test20 checkpoint: user reports test19 reception waiting for ACK where test16 works; the historical return-path cause remains open. Test20 implements corrected route selection/retry/cancellation, preserved background intent, remembered selectable sender playback, unified 0–100 gain with real optional remote synchronization, PCM continuity/peak protection and authorized private shell export. Emulator integration exposed and corrected notification-denied foreground startup (#47) and a textual multicast beacon/audio-magic collision (#48). Final 90 JVM tests, zero-error lint/debug builds and nine applicable silent native regression methods passed, including a managed-network legacy ACK/foreign-port fixture and gain persistence across separate processes. All four CI workflows for065cb01 succeeded after the Windows upload correction #49; run38588977969 published v1.3.1-test.20 as prerelease/latest=false. Downloaded APK/Windows/source checksums and permanent APK signer/package/version verified (preview code610141524). Physical devices retain test16 and test19; no test20 is installed there. The completed release request is disarmed for subsequent documentation-only commits. Physical communication-sharing, foreground/other-app/lock-screen keys, active capture, encrypted multicast and overnight quality acceptance remain open in docs/work-items/android-test20-reception-volume.md. No stable promotion, merge or runtime issue closure; real-device work stays background/read-only until a separate agreed window.

Implementation authorized on `fix/android-multisource-recovery`, based on the published test18 diagnostics branch. Correct direction is multiple simultaneous senders into one Android receiver with mixed playback; the prior one-sender/multiple-receiver suggestion is withdrawn. Work covers independent receive sessions, bounded partial-write continuity, silence/health telemetry, capture Binder identity/route safety and destination-specific network/offline recovery. Issues #36/#37/#38/#39 and existing #5/#14/#4/#6 remain open. One foreground host, existing WFAS v2 framing, permanent-signed independent preview, original test16 and unrelated Desktop work are preserved. See docs/work-items/android-multisource-recovery.md. Private device evidence is not published.

Independent signed test19 is installed on both devices. Final 58 unit tests and zero-error Android lint/build checks passed; muted multi-source native regression passed on emulator and physical receiver. Explicit recorder attribution corrected the capture startup failure, with both quiet physical capture/start-stop/volume restoration tests passing. Local Desktop checks remain incomplete (existing MinGW native link failure and Maven dependency/TLS limits); Windows CI subsequently passed for implementation f1662f9; prolonged real-audio/OEM/VPN acceptance remains open before merge/release. No stable publication or issue closure.

Implementation commit `f1662f9` is pushed in Draft PR #40, stacked on diagnostics PR #35. All four implementation CI workflows succeeded: Android, Windows checks/build, governance and signed preview run38414923086. Release publication was correctly skipped; the following checkpoint only updates documentation. Remaining acceptance is tracked in the work item rather than closed by short regression fixtures.

## Android runtime diagnostics and recovery (2026-10-12)

User authorized #32 persistent redacted diagnostics/export and #33 dataSync timeout/quota crash repair. Branch fix/android-runtime-diagnostics / Draft PR #35 is isolated from Desktop changes and based on test17 @68779ab. App-private 8MiB journals, bounded queues, Shizuku diagnostic IPC, exit history and periodic audio telemetry are implemented, including capture-start failure evidence before binder release. Auto-connect timeout/rejected startup no longer intentionally restarts sticky; redundant discovery stops once media owns recovery, and silence liveness is separated from real PCM health. Final 43 unit tests, zero-error lint and signed builds passed. Test18/code610120941 is installed on both devices with permanent signer; original test16 remains. Initial four CI checks passed. Device export/long-run acceptance is pending on device unlock; no continuous audio monitoring, stable release or runtime issue closure. See docs/handoffs/2026-10-12-android-runtime-implementation.md and docs/ANDROID_DIAGNOSTICS.md.

After device unlock, both device tests passed twice: credential redaction/export and history across process restarts verified. All four CI checks for code4654aae passed. User clarified the purpose is evidence for the next naturally occurring audio fault; active reproduction/desktop monitoring was stopped, with device sessions left alone. Long-run/audio telemetry/SAF UI and OS-triggered timeout acceptance remain open; wait for normal-use incident exports rather than requesting deliberate playback/silence or role changes.

## Active implementation (2026-10-04)

`fix/test17-adaptive-notifications` retains the local test.17 work and implements Issues #14/#15/#16: playback health/recovery corrections, selectable adaptive versus continuous manual latency, consolidated runtime/mode controls, and independent test identities. Debug is `.debug`; signed preview is `.test` with the existing permanent signer; stable release keeps the permanent package. Both devices retain test.16 alongside signed test.17. 39 unit tests and lintDebug pass (0 errors); signed preview CI run37181977448 passed Android build/tests/lint/signing, Windows and governance. Full real-device longevity and notification acceptance remain pending. See `docs/handoffs/2026-10-04-test17-implementation.md`.

After plan review the user authorized implementation. Playback/latency/notification corrections are committed as b3d64d0; isolated same-signer preview/channel selection and lint corrections as df460af. 39 unit tests and lintDebug pass (0 errors, 230 warnings, 20 hints); assemblePreview and lintVitalPreview also passed. APK verification and real-device acceptance continue. The earlier local debug attempt was canceled, and neither installed test.16 has been replaced.

Issue #17 tracks test.16 stable promotion and the independent same-signer preview channel. `release/test16-stable` is isolated at test.16 source @60137ef with release-flow-only commit 18ea088; v1.3.1 was published by release.yml run37181009171 after Android/Windows/governance/publish jobs all succeeded. The baseline retains reported long-run/OEM/VPN limitations; open runtime Issues remain open. Historical test.13 is now archived in Releases after user authorization and verified download; exact old local duplicates were deleted. Independent preview update selection excludes older permanent-package test assets and stable releases; private-key backup in the workspace root is verified, never tracked.

Later execution: PR #18 is open as a Draft against fix/android-test15-stability; HEAD 460d7fa explicitly requests signed preview CI run37181977448. Both devices installed permanent-signed independent test.17 (`.test`, versionCode610040557); phone streamed installs were rejected by MIUI, but the requested non-streaming resend succeeded. Both original test.16 packages remain at 609301952. User is asked to stop old sessions and grant/test the new package separately; playback/notification/overnight acceptance remains pending. Historical test.13 is published at archive-test.13 as prerelease, re-downloaded SHA-256 matches F98457EE6D27D58652A7C8C3D9AD90DD3F1F57ED828CCFB026239B2F1F3DA18C, and the two original old APKs plus temporary upload/download copies were deleted. Actions also has 18 test.13 runs; latest run36725036328 artifact11102329804 is unexpired until October7. That temporary artifact was not inspected before the original Release-only lookup; the archive preserves the exact old device binary, not a claim that all 18 builds are identical.

At 14:18 CST both independent preview foreground services are running: phone SEND, tablet RECEIVE (user switched from the original tablet-to-phone direction). Phone has runtime notification101; tablet runtime201 plus an OEM-generated silent-section summary, with no app mode701 or auto-connect foreground service. Original stable-package services are absent. Earlier phone RECEIVE logged valid PCM and adaptive target increases through 70 ms, then user stopped that session at 14:17:30 to switch roles. This is short-session implementation evidence, not long-run or subjective playback acceptance.

## Earlier test.16 device diagnosis (2026-10-04, before preview install)

Both real devices still run test.16 (609301952). The receiver phone was force-stopped by HyperOS/MIUI `AutoPowerKill` at 03:41:03 CST; system logs and ApplicationExitInfo agree, and its process/service are absent with the package stopped. The sender tablet remains alive after its 02:26 SEND restart. Its separate low-importance mode-control notification being folded into more notifications does not mean its foreground capture service stopped. The user places the earlier both-roles-OFF event between 01:00 and 02:30, followed by manual SEND/RECEIVE recovery; its cause remains unconfirmed because relevant app logs have rotated. It must be distinguished from the later 03:41 receiver force-stop.

Local `feat/test17-latency-stability` @ 46490fd is seven commits ahead of fetched `origin/fix/android-test15-stability` @ 54a416f, remains unpublished, and is not installed on these devices. Its playback watchdog/adaptive buffer changes do not address an OEM force-stop. See `docs/handoffs/2026-10-04-runtime-exit-diagnosis.md` for evidence, comparison and follow-up boundaries.

User clarification: the 02:26 SEND/RECEIVE actions followed the original disconnection very shortly. Investigate the minutes immediately preceding those recovery actions; service restarts caused by the actions are not evidence of the original failure.

Local test.17 static review (2026-10-04) recommends retaining the branch, but not releasing it as-is: playback rebuild failures are ignored, the 500 ms recovery resets can mask the new 5 s stall escalation, existing users are implicitly switched to adaptive mode, adaptive decay does not measure a clean interval, multicast has no adaptive driver, and concurrent playback state needs coherent ownership. Notification consolidation can be an independent change on this branch. Current compile/test rerun was blocked before execution by a Gradle loopback-connection error. Details: `docs/handoffs/2026-10-04-test17-code-review.md`.

## Repository

- Repository: `mu23XR/AudioBridge`
- Default branch: `main`
- Monorepo: Android + Desktop
- Shared protocol: WFAS v2
- Protocol source of truth: `/WFAS_PROTOCOL.md`

## Android identity

The next permanent application identity is:

`io.github.mu23xr.audiobridge`

The historical lab identity `com.cuscus.wifiaudiostreaming.lab` is retired for future releases.

Because the signing identity is also being standardized back to the permanent key, users of the historical Stable #153 / test.11 line must uninstall that historical package once before installing the new permanent line. Future releases should then upgrade in place.

## Signing

Permanent Android signer SHA-256:

`4D:DF:26:7D:25:A7:3F:0A:28:44:6C:9E:77:29:32:04:E3:2F:67:77:C6:34:17:FE:B7:BD:92:50:40:FE:FB:63`

Stable #153 / test.11 used a different historical signer and are not the future signing baseline.

See `docs/SIGNING.md`.

## Current product direction

Android:
- Shizuku no-root system-audio bridge
- Android-to-Android low-latency WFAS
- Home/lock should keep active streaming alive
- clearing the app task/Recents is treated as explicit OFF
- stale Shizuku AudioPolicy/UserService must be removed
- sender and receiver volume control
- local playback retained when supported
- Chinese/English UI

Desktop:
- Windows 10/11 x64 is the primary validation target
- PC -> Android and Android -> PC WFAS
- local playback retained
- discovery/reconnect/tray/volume
- unified GitHub Releases with Android

## Latest test.16 release and device evidence

The Android/Desktop/prerelease gate passed and `v1.3.1-test.16` is published. Both authorized wireless devices installed it in place with the permanent signer (`4D:DF:26:7D:25:A7:3F:0A:28:44:6C:9E:77:29:32:04:E3:2F:67:77:C6:34:17:FE:B7:BD:92:50:40:FE:FB:63`). On the tablet, SEND started from speaker volume 0 and media-muted state; test.16 cleared mute, selected `remote_submix`, and kept speaker index 0. This verifies startup volume preservation for that tablet session.

The phone's active media output was wired headphones (`headphone(8)`), Bluetooth was disconnected, and its media index on that route was 10; the phone speaker index was 21. The user confirmed a wired headset is attached. This explains why the phone speaker stays silent during receive. The user confirmed receive audio is audible through the connected headset. No phone volume index or routing was changed. The test.16 process was force-stopped/restarted after in-place upgrade to ensure fresh code. An earlier heartbeat timeout came from a pre-existing receiver process and does not count as test.16 silence acceptance.

On a fresh test.16 process, the generated 208-second audio fixture included 185 seconds of exact digital silence followed by tone; the sender continued sending and the user later confirmed audio from the phone headset. The tablet Recents card was swiped away while SEND was active; the owner-death bridge cleanup completed in about 0.8 seconds, the route returned to speaker, and speaker volume 0/mute true were restored. These pass the observed-session checks for #3, #4 and #10; issues stay open for repeated/manual criteria. Test.15 remains published but was superseded after a real-device volume regression; do not describe it as validated.

New field report (2026-10-01): user reports receiver audio becomes silent or very faint after roughly 30 minutes of a long-running connection. A fresh tablet-side probe found test.16's Shizuku service and remote-submix capture active after about seven hours; STREAM_MUSIC was unmuted at 120/150 and the remote-submix input had a recent read and non-silent signal. On the phone, the user was right that wireless ADB was connected; the mDNS endpoint appeared after refresh. The phone log contains a real HyperOS `AutoPowerKill` of AudioBridge at 07:17, but this is only the state-changing event observed during inspection; it has not been tied to the first loss of audio from the previous night. The user also reports that a notification remained visible when they stopped the app yesterday; notification presence alone cannot prove the process was alive, because notification records can outlive a service, and the log does not establish the original failure time. At inspection the package was stopped with no process/service, `RUN_ANY_IN_BACKGROUND=ignore`, and media was routed to wired headphones at volume 30/150 (not muted). The long-running silent-audio issue therefore remains open and should be investigated as a receiver-track/network failure separately from the OEM force-stop event. See `docs/handoffs/2026-10-01-device-debug.md` for the event log and next validation.

Live tablet-to-phone quality probe (2026-10-01): the active WFAS stream uses raw 48 kHz stereo PCM16 on the wire, with no AAC/Opus codec; it is therefore lossless at the transport format, but not strict end-to-end bit-perfect because capture uses Android REMOTE_SUBMIX and the receiver AudioTrack reports `BitPerfect=false`. Phone/tablet ping averaged 4/5 ms, packet interval was 7.02 ms, the configured playout target was 20 ms with 10 ms startup prewarm, and the phone output mixer reported 21 ms; no direct acoustic loopback was measured, so end-to-end latency remains a tens-of-milliseconds estimate. The receiver metrics reached loss 0.82%, jitter about 9–10 ms, and peak 29.33 ms. AudioTrack underruns rose by about 36,875 in 25 seconds and `restartIfDisabled(...): disabled due to previous underrun, restarting` warnings repeated. This is direct evidence for the reported crackle/short dropouts; issues #4/#5 remain open.
The same live session remained active in the 13:05–13:06 sample: Wi-Fi stayed at 866 Mbps with RSSI about -39 dBm (phone) and -28 dBm (tablet), ping averaged 5.3/6.0 ms in the two directions, metrics stayed at loss 0.87–0.88%, jitter 8.5–9.5 ms, peak 29.33 ms, and the receiver track added 1,248 underruns in 12 seconds. This reinforces the receiver-side underrun diagnosis while leaving the exact contribution of UDP gaps versus scheduler/AudioTrack timing to be separated in a repair build.

New live failure capture (2026-10-01 13:38–13:46): the phone process and foreground ClientService (PID 18459) remained alive, STREAM_MUSIC was unmuted and routed to wired headphones at index 30/150, and AudioPolicy still reported the app player as started. AudioFlinger contradicted that state: WFAS track 9014/F1 5786 was inactive with 2,770,426 cumulative underruns and no active playback tracks. The phone's app-owned UDP6 socket on port 48045 had a 0x2A300 (172,800-byte) receive queue that stayed unchanged for 17 seconds. The tablet's Shizuku capture track (PID 29046) remained active at 48 kHz stereo PCM16 with a recent read and `Sil=n`. This is a reproduced long-run receiver-side stall, not a volume or sender-capture failure; #4/#5 remain open.
Working causal model (diagnostic, not fix acceptance): recurring UDP/application scheduling gaps (loss around 0.87–0.88%, jitter around 9 ms, peak 29.33 ms) repeatedly drain the receiver's small logical playout margin. Over a long session the OEM AudioTrack accumulates underruns and becomes disabled; AudioPolicy can still report `started` while AudioFlinger has no active track. The current recovery path only pauses/flushes/plays the existing track when the receive path runs; it does not prove that a vendor-disabled track is recreated, and it has no independent alarm for a socket whose receive queue is no longer being consumed. That explains why the tablet capture remains alive while the phone eventually becomes silent. The elapsed time is probabilistic, not a fixed timer.

## Current task-removal cleanup fix

The Shizuku AudioPolicy cleanup now prefers Android's synchronous
`unregisterAudioPolicy()` before the UserService is removed. The previous
async-only cleanup could race with task/process teardown and leave an OEM audio
route/volume context behind even after network sending had stopped.

## Immediate known validation target

Before declaring the next stable release:
- verify sender Recents swipe immediately releases the Shizuku audio route
- verify local speaker/media-volume control returns after stop
- verify receiver background behavior
- verify permanent package + permanent signer install path
- verify Android/Desktop update links point to `mu23XR/AudioBridge`


## Task-removal ownership rule

Runtime foreground services must keep `android:stopWithTask="false"` so Android delivers
`Service.onTaskRemoved()`. The app then performs one explicit OFF transition before
stopping services. Setting `stopWithTask="true"` bypasses that callback and can leave
the Shizuku UserService alive.

The Shizuku bridge also receives an app-process Binder token. If the normal app process
is killed unexpectedly, Binder death forces the privileged UserService to release
AudioPolicy and exit.

## Silence and latency policy

- Shizuku silence is not a disconnect condition.
- During capture silence, the sender emits a header-only liveness packet once per second.
- Receiver liveness is independent of PCM availability.
- New Android installs default to adaptive latency: the playout target starts at 20 ms and rises on underrun evidence inside a 20–80 ms band, decaying after continuous healthy audio. Current test.17 offers adaptive/manual modes; manual is a continuous 20–400 ms slider with numeric entry. The previous fixed 20/40/60 ms presets have been removed on the implementation branch.
- Receiver startup preroll is half of the playout target; excessive AudioTrack backlog is corrected aggressively.
- UI languages are Chinese and English only; the legacy Italian locale from the upstream project was removed (2026-10-02).

## test.17 latency/stability changes (2026-10-02, branch feat/test17-latency-stability)

Implemented on-device fixes for the double connect chime and the underrun-driven playback death; all pending real-device acceptance:

- Latency setting relabeled as the receiver-side playout buffer (WFAS `latencyMs` is per-device, never synced to the peer; the tablet→phone path uses the phone's value). Slider is now continuous (the previous fixed grid mixed a 40 ms floor with a 20 ms range and produced 21.1 ms notches), and the value text opens a numeric entry dialog clamped to 20–400 ms. Presets: adaptive (new-install default) plus fixed 20/40/60 ms; a new `adaptiveLatency` preference drives an underrun-fed target band, and in adaptive mode the track buffer is planned for the 80 ms ceiling.
- Startup preroll is now half of the playout target (20 ms keeps the historical 10 ms) on both unicast and multicast receivers, so higher buffers start protected instead of burning underruns at 10 ms.
- Receiver playback watchdog (unicast): every second it samples `AudioTrack.underrunCount` and `PlayoutGovernor.stalledForMs()`. An underrun storm (>200/s for 3 s while audio flows) or a 5 s playback-head stall with queued PCM requests an in-place track rebuild (release + rebuild `AudioTrack` and a fresh governor, re-prerolled); if the receive loop cannot consume the request within 5 s, the session is torn down through the normal disconnect path so reconnect logic takes over.
- Reconnects of an already-announced logical session no longer replay the full connection chime (`announceConnectSound=false` for `reconnectAttempt > 0`); rapid drop-reconnect cycles therefore cannot ring twice. The disconnect chime gating is unchanged.

## Next validation build: test.15

The latest development cleanup and notification commands are retained. Runtime
fixes in test.15 address inherited SEND mute (#10), actual zero PCM silence (#4),
nonblocking receiver writes/stalled playback and obsolete transport/mode-command
cleanup (#9). These are implementations awaiting real-device acceptance, not
closed bugs. Existing Recents/owner-death cleanup remains intact.

Test prerelease tags now build signed Android and Windows assets through the
reusable test workflow; Android, Desktop and governance jobs gate publication.
See docs/releases/1.3.1-test.15.md and docs/handoffs/2026-10-01-test15-implementation.md.


## Active issue tracker

GitHub Issues are the authoritative task list for unresolved work.

Current stabilization tracker:
- #6 Android stabilization before next stable release

Blocking bugs:
- #3 Recents removal can leave Shizuku bridge/audio route alive
- #4 Shizuku SEND can disconnect after prolonged source silence
- #5 WFAS playback latency can become unexpectedly large

Do not treat a code change or green CI as proof that these are fixed. Follow each Issue's real-device acceptance criteria before closing it.


## Repository governance tracker

Open Issue #7 tracks remaining repository-governance work, including main-branch protection, stale branch cleanup, release hardening and removal of temporary test workflows. Do not describe repository governance as complete until that tracker is closed.

两端显示的“延迟”不是同一个测量点：平板侧主要反映采集/发送路径，手机侧还叠加网络传输、接收抖动缓冲、10 ms 启动预热和 AudioTrack/HAL 输出队列；两台设备的时钟也未同步。后续应分别显示采集、网络、接收缓冲和播放输出指标，并用带时间戳的包或声学回环测量真正的端到端延迟。增大接收缓冲会提高手机端延迟，但能换取更大的抖动余量，这是稳定性与延迟的可调权衡。

WFAS 的 `latencyMs` 是每台设备本地保存的播放目标，不会在两端自动同步，也不是发送端到接收端的端到端测量。`NetworkManager` 只在本机作为 WFAS 客户端/接收端创建 `AudioTrack` 时读取它；当前平板→手机方向实际起作用的是手机的值，平板发送端的同名设置不会改变这条发送链路。

代理/VPN 现场结论（2026-10-02）：WFAS 是原始 UDP，不会经 HTTP/SOCKS 代理转发；如果“代理”启用了 TUN/VPN，全局 UDP 和多播可能被隧道路由或拦截。当前 Android 单播接收路径创建普通 UDP socket，没有把选定的 `networkInterface` 绑定到 Wi-Fi `Network`；因此“网络接口”设置不能单独保证代理开启时仍走本地 Wi-Fi。临时验证应开启代理的“允许局域网/绕过私有网段/UDP”并使用单播手动 IP；若仍失败，应把 AudioBridge 加入 VPN 分流直连。该现场尚未在真机上复测，当前 ADB 只有离线模拟器。
