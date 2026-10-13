# Android multi-source receiving and runtime recovery

Status: implemented; independent signed test19 is installed on both devices. Branch `fix/android-multisource-recovery`, based on the published test18 diagnostics branch. User confirmed **multiple simultaneous senders to one receiver with mixed playback**. The earlier one-sender/multiple-receiver suggestion is withdrawn. Implementation CI passed; Draft review and prolonged real-audio acceptance remain open.

Issues: #37 multi-source receiving, #36 privileged capture identity/route startup, #38 silence packet telemetry, #39 per-source network/offline recovery; existing #5 buffer growth, #14 playback health, #4 silence liveness and #6 acceptance remain open. Private device journals and incident timelines stay in ignored local storage and are not published.

## Implementation and acceptance

1. Separate transport sequence/loss from PCM timing, and waiting for packets from stalled playback. Deterministic silence, duplicate/reorder/wrap and health tests.
2. Bound queued PCM by time, keep partial-write tails and frame order, and validate actual playback-rate adjustment results. Receive/control traffic must continue while output is full. Test partial/zero/error writes, burst bounds, stop and silence recovery.
3. Validate Shizuku owner/caller before capture setup; restore Binder identity on all paths and retain route/volume safeguards. Device-specific capture/stop/restoration remains an acceptance boundary.
4. Independent receiver sessions with one foreground host: per-source socket/auth/metrics/AudioTrack/health/reconnect, source gains/mute, gain budget, selectable adaptive or continuous manual latency. Persist source intent/configuration without plaintext credentials, migrate the old single target, and cancel obsolete callbacks on explicit stop/source removal.
5. Destination-based network selection and per-socket binding, offline wait and address refresh, respecting explicit interfaces and local networks without Internet. OEM process restoration respects user stop and does not promise immunity from system force-stop.
6. Preserve the WFAS v2 wire format and independent `.test` preview identity/signing; Android unit/lint/build and Desktop/protocol checks before review. Keep test16 installed and keep source/Desktop worktree changes isolated.

No stable publication or issue closure until applicable device acceptance. No unsolicited active longevity reproduction or changes to audio direction/settings. Use natural failure evidence for unresolved long-run behavior.

## Progress

- Repository, active Issues, linked diagnostics PR and protocol/release/signing policies read; scoped branch and focused Issues created.
- Public work items contain code-level findings only; private diagnostic evidence stays local.
- Core implementation complete: independent source sessions/players; 60ms duration-bounded FIFO with partial-tail retention; verified/smoothed rate adjustment and unsigned head wrap; authenticated per-source metrics; route-aware socket selection; local buffer reconfiguration without sender teardown; bounded privileged startup retry and ownership checks. Per-source authenticated credentials use a dedicated encrypted, backup-excluded store and no automatic wipe on failure.
- Final Android verification passed: 58 JVM tests (0 failures/errors), lintDebug 0 errors, signed preview/AndroidTest and lintVitalPreview builds. The independent test19 uses the permanent signer; both original test16 installations remain. Existing audio settings are retained.
- Muted loopback device fixture passed on the emulator and physical receiver: two formats and an encrypted third source, incorrect-key isolation, silence beyond the old watchdog threshold, local buffer update without new handshake, per-source stop/reconnect and output cleanup. It restores previous test preferences. This is native runtime regression coverage, not prolonged subjective listening acceptance.
- Initial emulator run exposed a mono-source output with valid packets but no playback progress. Explicit API31+ start thresholds and capacity-based preroll on older APIs corrected it; the rerun passed.
- The initial quiet capture fixture failed: a registered AudioPolicy returned an uninitialized sink, followed by a compatibility-route timeout. [AOSP's sink constructor](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/media/java/android/media/audiopolicy/AudioPolicy.java) does not pass the policy Context to AudioRecord. Explicit attribution now reaches the actual recorder builder and the mix uses output channel masks. Both quiet physical capture fixtures passed, including start/stop and unchanged media volume/mute. The fixture also correctly skips active media. Failure toasts explain the failed sending operation, while detailed errors stay in state/journals.
- Local full Desktop check is incomplete: available MinGW toolchain fails to link the pre-existing native PKEY_Device_FriendlyName reference; JVM-only attempts are blocked by missing cached dependencies and Maven TLS failure. Desktop product files were not changed. The subsequent Windows CI passed for implementation f1662f9; the local toolchain limitations remain distinct from that successful CI result.

## Next acceptance

Review the scoped Draft PR and require Android, Windows and governance CI. Keep runtime/multi-source issues open pending prolonged real-audio listening, network/offline/VPN combinations, source-list UI and OS lifecycle acceptance. Do not force user media/settings or start an unsolicited longevity monitor. Private device/build logs stay in ignored `out/`.

Review checkpoint: [Draft PR #40](https://github.com/mu23XR/AudioBridge/pull/40), implementation commit `f1662f9`, stacked on diagnostics PR #35. All four implementation CI workflows succeeded: Android validation, Windows build/checks, governance and [signed test APK](https://github.com/mu23XR/AudioBridge/actions/runs/38414923086). Release publication was correctly skipped (`publish:false`). The subsequent checkpoint only updates documentation; product code is unchanged. Issues #36–#39 link the implementation and retain their remaining acceptance criteria.
