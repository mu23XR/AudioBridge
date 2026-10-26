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
