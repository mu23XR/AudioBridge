# Release 2.0.1 — Android and Windows

Status: Android repair under validation; stable publication pending.

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74), Draft PR #75; Android #51/#77, Windows #76.

## Scope

Integrate the latest Android test21 behavior with the accepted PC vpn.9 feature set. Preserve WFAS v2, permanent signing, distinct preview identities and existing audio targets. Repair failed Desktop initialization ownership and Android playback scheduling/cleanup without changing native queue, progress, producer or loss limits.

## Current implementation

- Desktop failed initialization releases ownership before publishing failure; successful handoff and timeout/late-close barriers remain intact.
- Android playback and timers use one owned thread per source at normal priority. Concurrent first start and repeated stop retain a single owner and the first independent cleanup job.
- Late output registration is removed by identity. Unconfirmed cleanup remains an explicit failure even after its tasks finish; stopped workers and executors must actually terminate.

## Validation

Independent source review is complete. Focused67 and full173 Android JVM methods pass with zero failures/skips. Strict lint, debug build and instrumentation compilation pass. Earlier platform checks and native outcomes remain evidence in the linked issue discussions; passing local tests alone is not native acceptance.

## Remaining acceptance

1. Run the exact nonpublishing candidate through Android, Windows, governance and strict twelve-method native checks, with zero skipped methods and a verified active audio path.
2. Inspect actual playback-thread, queue, progress and resume-loss evidence without relaxing the existing limits.
3. Enable stable publication only after acceptance. Rebuild both final platforms from one exact source; verify actual package/version/signer, unified provenance and checksums, and all nine public downloads before marking stable/latest.

Existing installed applications and historical Releases are preserved. This public work item omits internal workspace history; detailed records remain locally available.
