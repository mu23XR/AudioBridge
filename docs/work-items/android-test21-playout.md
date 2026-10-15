# Android test21: low-latency playout and connection cues

Status: local implementation complete; 106 JVM tests, debug/helper builds and zero-error lint passed. Emulator execution and CI preview delivery are pending. Branch fix/android-multisource-recovery, Draft PR #40; existing Issues #5/#14/#37/#45 and focused cue Issue #50 remain open.

## Scope and authorization

User authorized the repair proposal, then explicitly restricted validation to emulators. Do not connect, install, operate UI or change settings on either physical device. Preserve stable test16 and the existing permanent-signed independent preview identity. No stable promotion, merge or acceptance closure. Prior diagnostic documentation is retained; unrelated Desktop/source-worktree changes are excluded.

User then identified another active task using MuMu instance1. Before that notice this task restarted instance1 once to recover offline ADB, and may have interrupted it; native validation had not started and no package was installed. Stop all further operations on1, do not restart shared global ADB servers, and coordinate with the identified handwriting task. A separate new MuMu instance2 was created for AudioBridge only; host-side guards and runner must be updated before any test. Do not infer an offline/contended emulator result is an app regression.

## Implemented

- Replace one-chunk-per-2ms consumption with conflated input wakeups and bounded batches: native headroom limits individual writes; batches cap audio duration, calls and CPU work. Empty input suspends, full/short output waits, cancellation and configuration remain observable. Original partial-write tails remain unchanged in the queue.
- Observe total native/software buffering for stale-backlog disposal. Require sustained excess and a cooldown, trim an aligned old software prefix, preserve newest audio and smooth the boundary. FIFO bursts do not directly drive playback-rate correction. Independent review caught and corrected stale excess timing across an empty FIFO.
- Fresh PCM and explicit silence are separate from queued audio. Intentional silence neither raises adaptive target nor rebuilds a residual output; stale unsignalled queued stalls can still recover. A single bounded5ms fade reaches zero at silence, and rate returns to nominal while idle. No indefinite zero-PCM feeding or enlarged target/buffer defaults.
- Explicit connection/restart/retry gets fresh cue eligibility. Automatic retries deduplicate. Only validated fresh source frames, including silent frames, consume eligibility; ACK/control/foreign/malformed/late packets do not. Optional player creation is off the receive loop and bounded failure diagnostics replace silent errors.
- Preserve adaptive/manual choices, saved user values, independent per-source clocks, remote volume controls, routes/authentication and WFAS v2.

## Validation and limits

106 JVM methods pass with zero failures/errors. Lint reports zero errors; debug and instrumentation APKs build successfully. Tests cover750pps/8ms scheduler batches, native admission, CPU yielding, partial/zero writes, frame-aligned trim, transient/sustained/cooldown/empty-reset budgets, intentional silence versus queued-stall recovery, fade-out and idle rate, plus cue generation/preference rules. Independent source review reports no remaining blocking findings.

New quiet emulator fixtures cover sustained750pps single/two-source progress, independent manual targets, pause/resume/adaptive stability and actual UDP cue transitions; existing multi-source/remote-volume/retry/lifecycle/export fixtures are retained. Host verifies the local MuMu Android15 x86_64 instance and explicitly opts in; no real-device validation. The first attempt stopped at an offline emulator ABI check before package installation or tests. Recover that local emulator channel, then run the applicable eight native methods. Never count a skipped/failed/offline fixture as a pass.

Native buffering/progress counters do not measure acoustic end-to-end latency or establish subjective/overnight quality. The user's exact test20 incident root cause remains open without current real-device journals. Keep this limitation in release notes and trackers.

## Delivery

After local/native checks, request CI-generated1.3.1-test.21 preview with existing permanent signer/package io.github.mu23xr.audiobridge.test. Android, Windows and governance gates must pass before prerelease publication/latest=false. Verify assets, source/checksums/package/version/signer; disarm publish after successful creation. Do not install the published preview on physical devices. Keep scoped verified commits and this work item/handoff current through CI.
