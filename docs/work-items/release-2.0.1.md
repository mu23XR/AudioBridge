# Release 2.0.1 — Android + Windows

Status: in progress; owner authorized stable GitHub publication, 2026-10-26.

## Sources and scope

- Integration baseline: public Android 6a41f957a72ab2615f4a0be3574f8b436372e4a9; android tree 006c2ed75d505032f31fc579eda975ce14b6b640, preserved without behavior changes.
- Windows product import: 8448887200e2363236156ff6ce1590c8aff01fc3, desktop tree 9e6a0da8cde34c7712cbca996d451b5dbb6a468d. Includes continuity, asynchronous route checks, source preferences, live gain, output selection and recovery fixes.
- Release from one integration SHA through root release.yml, explicit stable version 2.0.1, permanent Android signing identity and release package. Preview identities remain separate.
- Import product files and necessary public build/check scripts only. Local diagnostic history, private backups, credentials, audio and existing generated packages are excluded.
- Existing worktrees, running applications, installed devices and historical releases remain intact. No main-branch push or Draft-PR merge is required for publication.

## Acceptance

1. Inspect repository state and sources, integrate exact product trees, document release identity and migration.
2. Android unit tests and lint; strict native 12-method API35 fixture; Windows full checks and separate manual generation; governance, packaging and stable identity tests.
3. CI-generated APK and Windows ZIP/TAR from the same SHA; Android package/version/code/certificate and Windows JAR/channel/manual/native checked.
4. Publish immutable v2.0.1 assets with source, provenance and checksums; download and verify before marking stable/latest.
5. Confirm GitHub readback and record exact source/run/assets. Short real listening accepted on the preceding PC candidate; overnight/OEM/screen-off/acoustic latency are not established by CI.

## Checkpoints

- Diagnosis complete: current PC branch contains old Android code; release must integrate Android 6a41f95 and PC 8448887 instead of tagging PC branch alone.
- Managed worktree creation unavailable because chat root is a workspace container; explicit registered Git worktree release-2.0.1 created on branch release/2.0.1 from Android baseline. Other worktrees unchanged.
- Implementation underway. CI and publication not yet complete.

Tracking: https://github.com/mu23XR/AudioBridge/issues/74. Root stable workflow integrates exact-SHA PR request after all gates; current main is ea2dfe8 and remains unchanged.

- Implementation complete: root stable pipeline, reusable strict native gate and stable bundle verifier added; public preview guards retained and reusable Windows test inputs preserved.
- Local validation complete: stable generateMan 115.558s and full Windows check 148.095s passed, including stable namespace/version/manual assertions, 29 clock scenarios and native lifecycle. Python 34+9 publication checks pass. Independent release integration review reports no blocker. Android product tree unchanged. Fresh CI/publication pending.
- Integration check found native Windows lifecycle outputs under root logs/; added that known generated directory to ignore rules so exact-source checks reject source edits without mistaking fixture binaries for source. Android-test reusable Windows permission ceiling is explicitly aligned with its existing guarded publisher.
- Publication privacy check rejected uploading the old mixed PROJECT_STATE history. Preserved it locally under ignored out/release-preparation and replaced the public state with product/release facts only; device diagnostics and personal session history are excluded from the new publication payload.

## Owner-requested pause — 2026-10-26

Paused locally at the owner's request to close Codex and play games. No local build/test/monitor is left running by this release task; local checks used --no-daemon and completed. No automation was created. Existing AudioBridge installation/session was not changed. Temporary release-inspection browser tab closed.

Published source branch release/2.0.1 and Draft PR75 point to a8a534fda2563bc9aadacc881b7b232e8fef2598, tree8f3cd22574cbf6725bfac28c22d34371792b7824. Main remains ea2dfe8. Root release run40319490062 was still running at last verification: meta, Windows and governance passed; Android and strict native were still in progress. No2.0.1 release existed at the last release lookup.

IMPORTANT: GitHub cloud CI is independent of Codex and consumes no local build resources. It was not cancelled; its already-authorized publish:true request may automatically complete stable/latest publication if all gates pass. Do not describe cloud publication as stopped. The connector has no cancel-run action and the temporary IAB session was logged out; no login or new publication change was attempted during pause.

Resume: first read run40319490062 and Release v2.0.1 status. If published, verify all nine public asset hashes/downloads, APK identity/permanent signer and Windows stable contents; collect under artifacts, then disarm publication request and update release records. If failed, inspect the specific failed job before any retry; preserve existing tag/assets. Do not rebuild or retag blindly. Current PCvpn9/test17 and Android installations remain untouched. Local checkpoint documentation may advance local HEAD beyond the cloud build SHA; product release source is the exact SHA above.

## Resume diagnosis — 2026-10-27

Read GitHub run40319490062 before any rebuild/republish. Android signed release, Windows, meta and governance passed. Strict native job121560522989 failed prolongedSilenceKeepsAdvancingWithinTheNativeTargetBudget; release job skipped. v2.0.1 does not exist; latest remainsv1.3.1. Independent native run40319490057 failed the same method. Investigating saved CI evidence before retry or source change; product/app installations and private material remain untouched.
- Native failure narrowed from retained instrumentation: all12 methods executed,11passed/0skips. The35s intentional-silence native-buffer/played-progress assertions completed; failure is at post-silence observe line164 because fixture PacedPcm producer exceeded its80ms timing contract (163470us lag/pending122), not a failed output budget/continuity assertion. Other silence-resume methods passed. One bounded exact-source rerun of failed jobs is warranted to distinguish scheduling invalidation; no product/test/threshold changes and no prior failure erased.
- Runner audio environment additionally invalid: emulator37.2.12 could not initialize PulseAudio before boot/tests, despite pactl finding the null sink. Paused remote publication explicitly with publish:false and newer-head cancellation while repairing only CI wiring; APK/desktop product behavior and all strict acceptance thresholds remain unchanged.
- CI-only implementation complete: explicit owned Unix PA/QEMU server/runtime, init-error rejection, and live instrumentation sampling tied to owned emulator/children and exact uncorked target sink. One-second host sampling minimizes measurement disturbance. Monitor stop/join/error handling and all original native contracts remain strict. Independent review, Python39+9, AST/YAML/diff checks pass; native fixture and Android product tree unchanged. Re-enabled the existing2.0.1 stable request solely for this newly verified-gate candidate; fresh CI evidence still pending.
