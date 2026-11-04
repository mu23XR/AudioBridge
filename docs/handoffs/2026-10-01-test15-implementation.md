# test.15 implementation handoff

## Authorized scope

User requested latest features (test.14 development line) + today's runtime fixes + a new release. Base: 2caa437 on a separate fix/android-test15-stability branch. Preserve #8 cleanup and #9 command semantics; no deleted author UI, donations, website links or copyright headers restored. Permanent package and signer retained; release is a prerelease, no main merge/stable promotion.

## Work items

- #4 claimed: exact zero PCM liveness + nonblocking playback and stalled-track recovery.
- #10 created: SEND inherits local media mute on remote_submix.
- #9: prevent obsolete mode commands and receiver finalizers from disrupting replacements.
- #11 created: release YAML and Desktop validation blockers.
- #3: existing cleanup retained, regression pending; #5 latency regression pending.

## Implementation checkpoint

Exact digital-zero classification preserves quiet nonzero PCM and validates bounds. Sender advances sample positions during zero capture while sending header-only liveness once a second. Receiver filters zero PCM from older senders, never blocks UDP on writes, accounts actual accepted frames and recovers stalled tracks after 500ms when fresh PCM arrives. Transport generations guard cleanup/callbacks and old CLIENT_BYE, and OFF invalidates generations. Runtime role commands own/cancel pending startup jobs and recheck role/generation after settings load.

REMOTE_SUBMIX capture records original local mute/volume, waits for only the remote_submix route before unmuting, leaves speaker indices untouched, and restores original mute after route release only if local volume did not change. LOOP_BACK_RENDER does not change local volume. Unsupported reflection/route setup fails with explicit capture error rather than adjusting an unverified local route.

Desktop RTP test helpers receive unique names; protocol executable checks join Gradle check. Formal release YAML stale duplicate tail removed. Reusable test workflow builds on prerelease tags and publishes Android/Windows/checksums/source SHA only after build, Desktop and governance jobs succeed. Stable publication stays separate.

## Verification checkpoint

Pending CI and same-signer real-device tests. Do not close runtime issues based on compilation. Detailed diagnostic source: 2026-10-01-device-debug.md; broader static review: 2026-10-01-version-review.md. Temporary probes/scripts are ignored under out/review. Existing diagnostic handoffs included unchanged except scoped updates.

## CI blocker and device authorization checkpoint

Android regression tests and governance passed; Windows protocol checks revealed #13: reordered Snapcast volume echoes could restore an earlier command after confirming the newest one. Added a bounded 2.5-second history filter for superseded local volume/mute values, preserving unrelated remote values and accepting old values again after expiry. Tests now force out-of-order confirmation and check external changes/expiry; callback state uses an AtomicReference. Values matching a superseded local command are ambiguous during this short window because notifications carry no request ID.

Fixed reusable/standalone Windows concurrency groups to prevent mutual cancellation. User explicitly authorized overwrite install on the connected tablet and phone and real-device validation once the signed build is ready. Runtime acceptance remains pending; no runtime issue closed.

## Published build and failed real-device acceptance

Published test.15 at 7d58607a357f1f11982f697cb32365183994d6d5 after all CI gates passed. APK SHA256 92FC8E5220FB488CC4C2B4A0312A7FAA6884A3A926C2AC6800B8F6B5A1DD6CDA, permanent signer verified locally with ApkVerifier. Both tablet/phone overwrite upgrades succeeded, code 609301933. Tablet snapshot unexpectedly showed speaker index 150 (original 0), so #10 acceptance FAILED. Stopped both apps; tablet owner death released bridge in 665ms. Confirmed speaker route, index 0, mute true after restoring test volume. No runtime issue closed.

Corrective test.16 removes explicit UNMUTE and set-to-maximum; uses a single ADJUST_RAISE only when muted/zero, matching the previously observed physical volume-key fix. Original volume/mute are captured before AudioPolicy attempts. CI passed for test.16; the real-device results are recorded in the follow-up below. Evidence remains ignored under out/device-debug/test15.

## test.16 live acceptance follow-up

`v1.3.1-test.16` passed Android/Windows/governance CI, was published, and was installed on both devices with permanent signer. Tablet speaker began at index 0 and muted; fresh service startup cleared mute, routed to remote_submix, and preserved speaker index 0.

Clarification: in-place upgrade left a pre-existing phone process alive; its heartbeat timeout at 04:06 is not valid evidence for test.16. Force-stopped that app process and relaunched test.16 as PID 24649. Tablet/phone connected to that fresh receiver. Phone AudioService reports current output `headphone(8)`, with Bluetooth disconnected; user confirmed a wired headset is attached. Media index on the headset was 10, speaker index 21. The tablet speaker is therefore expected to remain silent while the wired headset is selected. The user confirmed the headset has receive audio. No volume index was altered on the phone.

Audibility was confirmed by the user over the headset. A 208-second generated audio fixture was replayed with an 185-second exact-zero section between two tones; the clean test.16 receiver stayed present across the run and received the final audible section. With SEND active, swiping the identified AudioBridge task in Recents caused the Shizuku owner-death path to stop the bridge and finish cleanup in about 0.8 seconds. STREAM_MUSIC returned to speaker, index 0, Muted=true. Do not change the phone headphone volume or force speaker routing while a wired headset is connected. These are one-device/one-run acceptance results; leave formal GitHub issues open for repeated and other-device criteria. Evidence is saved under ignored `out/device-debug/test15` and `out/device-debug/test16`.
