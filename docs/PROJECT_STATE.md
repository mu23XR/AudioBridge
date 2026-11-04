# Project State

## Stable 2.0.1 release — Android repair resumed

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74).

The authorized release starts from Android source 6a41f957a72ab2615f4a0be3574f8b436372e4a9 and Windows product source 8448887200e2363236156ff6ce1590c8aff01fc3. The existing permanent signing identity is preserved. Windows includes source preference, output selection, live gain, continuous playout and connection recovery repairs. The owner also authorized two narrow follow-up repairs identified by release checks: failed Desktop output initialization must release ownership before publishing failure (#76), and Android small-packet draining must avoid redundant native recovery/head queries (#51). These candidates require fresh validation; installed applications have not been replaced.

Both platforms use version 2.0.1 and WFAS v2. Android release package is io.github.mu23xr.audiobridge; the independent preview package remains .test. Windows stable and preview configuration remain separate. Migration and known limitations are documented in [2.0.1 notes](releases/2.0.1.md).

The November4 Android candidate isolates each source's playback and timers on an owned single thread, with one start/stop owner and first-cleanup retention (#51/#77). Independent IO release, late registration cleanup, actual executor termination and failure barriers are preserved. Normal priority is used, with no buffer/target/native-threshold changes. Focused67 and full173 JVM methods pass with zero failures/skips, plus strict lint/debug/instrumentation compilation. Independent review is clear. Fresh native acceptance is still required before stable publication.

The root release workflow requires Android unit tests, lint, strict native audio acceptance, Windows checks and governance on the same integration commit. Actual package identities, source manifests and checksums must match, and uploaded assets must be downloaded and verified before stable/latest publication. Nonpublishing repair candidate fbeff3b11ff41a06d70e4485229b3c00310b4439 passed all six workflows, including two12/12 native artifacts with active audio and unchanged bounds. However, the final publishing commit5916b75bc127de3193d30a239e5b93af2297d23e, with identical product trees, failed both native runs. Android signed builds, Windows checks/package and governance passed; release was skipped. No2.0.1 publication is claimed. Disable publication for the next diagnostic candidate and inspect the retained failures before further repair or retry; the earlier passing samples do not establish stable resolution of the Android scheduling/loss problem.

Open runtime and security issues remain in the [issue tracker](https://github.com/mu23XR/AudioBridge/issues). Compilation and synthetic tests do not close real-device, overnight or network-configuration issues. Existing published tags/assets remain immutable.

Release work item: [release-2.0.1](work-items/release-2.0.1.md). The public project state summarizes product and release facts; private device diagnostics and personal session history are not publication material.
