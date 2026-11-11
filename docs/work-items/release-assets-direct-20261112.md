# Direct Release delivery without Actions package copies

Issue: [#78](https://github.com/mu23XR/AudioBridge/issues/78). Status: implementation in progress.

## Scope and baseline

Owner authorizes replacing package artifact storage with direct preview/stable Release delivery while preserving existing tests and signing verification. The new local worktree is `release-flow`, branch `fix/release-assets-direct`, based on published source `4cbea4a97b73554ca8f9260bc211736916b12154`. Remote `release/2.0.1` remains at that source; main is `ea2dfe8`, PR75 remains Draft/unmerged, and existing v2.0.1/Latest is immutable. Other worktrees and installations stay unchanged.

## Design

- Ordinary validation still builds and verifies, but does not retain installer copies.
- Cross-job transfer uses a draft Release bound to repository, exact source and workflow run. It never becomes Latest or a user-facing product version.
- Test delivery remains a prerelease; stable delivery remains a stable Release. Original gates, permanent key/APK checks, package/version/source/checksum/provenance and download verification stay enforced before public promotion.
- Replace package `upload-artifact`/`download-artifact` calls, including reusable Windows test delivery, rather than just shortening retention.
- Successful validation/publication removes only this run's transient staging packages. Failed runs retain exact job outputs for retries and become eligible for cleanup after one day on the next staging operation. Diagnostic drafts have a 14-day cleanup eligibility window. Both require completed owning runs. Reject mismatched ownership, unsafe names, changed source, forks, published Release mutation and ignored transport failures.
- Existing version requests are disarmed for validation. No new public product version, existing Release overwrite, device installation, signing-key change, runner installation or billing change is part of this task.

## Validation plan

Run existing release/provenance/Windows publisher tests unchanged, then transport regression cases for source/run binding, corruption, retry/idempotence and cleanup. Validate complete workflow wiring and permission boundaries. Run CI as a nonpublishing candidate, confirming required checks remain and package artifact uploads are absent. Update this work item, the Release documentation and handoff at implementation/test milestones.

## Implementation checkpoint

The four publishing/native workflows now use `release_stage.py` for source/run-bound
draft Release transfer. No `upload-artifact` or `download-artifact` remains. Ordinary
Windows validation retains no package; signed test dispatch defaults to a real
prerelease, with an explicit nonpublishing option. Preview metadata is resolved
once for both platforms and `preview_release.py` verifies actual APK identity,
platform source records, package bytes and final download before promotion.
Stable verification/publisher code and all application/native test code are
unchanged. Temporary signing-key cleanup is unconditional.

Local checks pass: the existing 40 script tests, 21 added transfer/preview
tests, the existing nine Windows publisher tests, all workflow YAML and official
actionlint1.7.12 (verified archive digest). Tests cover signing-material rejection, corrupt downloads,
wrong run/source, changed or public drafts, exact retry manifests, tag cleanup,
failure retention and diagnostic retention based on run time rather than commit
time. All product publication requests are false for cloud validation.
