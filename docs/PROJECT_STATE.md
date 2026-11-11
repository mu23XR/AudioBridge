# Project State

## PR75 acceptance repair and main integration

PR75 merged at36a3343454b8cf00872f51fbd7d554a7ab31c933 after all six exact0979
workflows passed, both native suites12 tests/zero skipped, stable/preview actual
permanent-signer/package/source/hash verification and cleanup intact. Issue80
is closed. The debug-name race, transient soft trim and owned playback scheduling
repairs retain all original targets, hard capacity and native acceptance bounds.
[PR75 investigation](work-items/pr75-acceptance-20261112.md) retains the history.

Postmerge native43090517310 rejected paced input88.164ms over the unchanged80ms
contract. Its incomplete phase is not a new native pass. The verified archive
shows66 pending packets, nearby same-process46.294ms GC and system delays;
the exact scheduling cause is not proven. Subsequent startup attempts created
zero jobs during the official GitHub Actions incident dvy0t3mvh1lg, now resolved.
Actual native revalidation remains pending under
[main native follow-up](work-items/main-native-input-20261112.md).

Main ee9c0af removed inherited Dependabot configuration; all14 unsolicited bot
PRs81–94 were closed without merging. Automatic dependency upgrades stay off.
This documentation-only update also retires an obsolete recovery script's
exact-ee9 main lease, preventing it from starting an additional native attempt.
Only the existing unchanged native workflow will be freshly dispatched after
the fence. No product/fixture/test-limit/signing change or new Release.
The older release-flow and publication snapshots below retain their dates.

## Release delivery without Actions package copies

[Issue78](https://github.com/mu23XR/AudioBridge/issues/78) replaces installer artifact
uploads with run/source-bound draft Release transfer. Test packages remain
prereleases and stable packages remain stable/Latest, after their existing gates.
Ordinary Windows validation no longer retains packages. Native diagnostics use
their own retention-scoped drafts. Signing, package/source/provenance checks,
download verification and all native test limits remain intact. Local Python
regressions, workflow lint and full nonpublishing CI validation passed on0979.
The stable request is disarmed so this maintenance change cannot republish2.0.1.
Work: [direct Release delivery](work-items/release-assets-direct-20261112.md).

## Stable 2.0.1 published

The stable/latest Release is [v2.0.1](https://github.com/mu23XR/AudioBridge/releases/tag/v2.0.1),
immutable source4cbea4a97b73554ca8f9260bc211736916b12154/run41857316658,
with nine verified assets. Existing installations and historical Releases remain.
PR75 is merged as described above; the published nine assets remain unchanged.
The following preparation details are historical and do not override this status.

## Historical 2.0.1 release preparation

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74).

The authorized release starts from Android source 6a41f957a72ab2615f4a0be3574f8b436372e4a9 and Windows product source 8448887200e2363236156ff6ce1590c8aff01fc3. The existing permanent signing identity is preserved. Windows includes source preference, output selection, live gain, continuous playout and connection recovery repairs. The owner also authorized two narrow follow-up repairs identified by release checks: failed Desktop output initialization must release ownership before publishing failure (#76), and Android small-packet draining must avoid redundant native recovery/head queries (#51). These candidates require fresh validation; installed applications have not been replaced.

Both platforms use version 2.0.1 and WFAS v2. Android release package is io.github.mu23xr.audiobridge; the independent preview package remains .test. Windows stable and preview configuration remain separate. Migration and known limitations are documented in [2.0.1 notes](releases/2.0.1.md).

The November4 Android candidate isolates each source's playback and timers on an owned single thread, with one start/stop owner and first-cleanup retention (#51/#77). Independent IO release, late registration cleanup, actual executor termination and failure barriers are preserved. Normal priority is used, with no buffer/target/native-threshold changes. Focused67 and full173 JVM methods, lint/debug/instrumentation compilation and independent review pass. Candidate953c13b passes all six workflows; both native artifacts establish12/12 methods, zero skips and actual owned playback threads. Adaptive resume loss is0/1.29ms and35s-silence resume loss is0 in both runs, within all original limits. This does not establish zero loss in every phase or overnight acceptance. The final stable source must rebuild both assets and pass all gates again before publication is complete.

The root release workflow requires Android unit tests, lint, strict native audio acceptance, Windows checks and governance on one exact integration commit. Actual package identities, source manifests and checksums must match, and all nine uploaded assets must be downloaded and verified before stable/latest publication. The original stable request is enabled after the accepted owned-thread candidate; final build/publication remains pending. Earlier failed candidates are retained in the issue records and are not substituted for current acceptance. No2.0.1 publication is claimed until the final workflow and asset verification complete.

Open runtime and security issues remain in the [issue tracker](https://github.com/mu23XR/AudioBridge/issues). Compilation and synthetic tests do not close real-device, overnight or network-configuration issues. Existing published tags/assets remain immutable.

Release work item: [release-2.0.1](work-items/release-2.0.1.md). The public project state summarizes product and release facts; private device diagnostics and personal session history are not publication material.
