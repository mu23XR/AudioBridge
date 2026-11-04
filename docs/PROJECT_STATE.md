# Project State

## Stable 2.0.1 release — Android repair resumed

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74).

The authorized release starts from Android source 6a41f957a72ab2615f4a0be3574f8b436372e4a9 and Windows product source 8448887200e2363236156ff6ce1590c8aff01fc3. The existing permanent signing identity is preserved. Windows includes source preference, output selection, live gain, continuous playout and connection recovery repairs. The owner also authorized two narrow follow-up repairs identified by release checks: failed Desktop output initialization must release ownership before publishing failure (#76), and Android small-packet draining must avoid redundant native recovery/head queries (#51). These candidates require fresh validation; installed applications have not been replaced.

Both platforms use version 2.0.1 and WFAS v2. Android release package is io.github.mu23xr.audiobridge; the independent preview package remains .test. Windows stable and preview configuration remain separate. Migration and known limitations are documented in [2.0.1 notes](releases/2.0.1.md).

The November4 Android candidate isolates each source's playback and timers on an owned single thread, with one start/stop owner and first-cleanup retention (#51/#77). Independent IO release, late registration cleanup, actual executor termination and failure barriers are preserved. Normal priority is used, with no buffer/target/native-threshold changes. Focused67 and full173 JVM methods, lint/debug/instrumentation compilation and independent review pass. Candidate953c13b passes all six workflows; both native artifacts establish12/12 methods, zero skips and actual owned playback threads. Adaptive resume loss is0/1.29ms and35s-silence resume loss is0 in both runs, within all original limits. This does not establish zero loss in every phase or overnight acceptance. The final stable source must rebuild both assets and pass all gates again before publication is complete.

The root release workflow requires Android unit tests, lint, strict native audio acceptance, Windows checks and governance on one exact integration commit. Actual package identities, source manifests and checksums must match, and all nine uploaded assets must be downloaded and verified before stable/latest publication. The original stable request is enabled after the accepted owned-thread candidate; final build/publication remains pending. Earlier failed candidates are retained in the issue records and are not substituted for current acceptance. No2.0.1 publication is claimed until the final workflow and asset verification complete.

Open runtime and security issues remain in the [issue tracker](https://github.com/mu23XR/AudioBridge/issues). Compilation and synthetic tests do not close real-device, overnight or network-configuration issues. Existing published tags/assets remain immutable.

Release work item: [release-2.0.1](work-items/release-2.0.1.md). The public project state summarizes product and release facts; private device diagnostics and personal session history are not publication material.
