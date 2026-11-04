# Release 2.0.1 — Android and Windows

Status: diagnostic candidate accepted; final stable build/publication pending.

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

Accepted diagnostic candidate:953c13b3d35d829211ae8a13334925f1047403e3. All six workflows pass. Both independently downloaded native artifacts establish12/12 methods, zero skips, active audio and actual owned playback threads; adaptive resume loss0/1.29ms and35s-silence resume loss0 meet the unchanged limits. Windows checks and actual package identity pass. These results do not establish zero loss or overnight acceptance.

The originally authorized stable request is enabled after this acceptance. Product trees stay identical to953c13b; the final source must rebuild both platforms, pass all gates and verify all nine public assets before stable/latest completion is claimed. Candidate binaries must not be relabeled as final assets.
