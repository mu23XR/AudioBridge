# Project State

## Stable 2.0.1 release — in progress

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74).

The authorized release starts from Android source 6a41f957a72ab2615f4a0be3574f8b436372e4a9 and Windows product source 8448887200e2363236156ff6ce1590c8aff01fc3. The existing permanent signing identity is preserved. Windows includes source preference, output selection, live gain, continuous playout and connection recovery repairs. The owner also authorized two narrow follow-up repairs identified by release checks: failed Desktop output initialization must release ownership before publishing failure (#76), and Android small-packet draining must avoid redundant native recovery/head queries (#51). These candidates require fresh validation; installed applications have not been replaced.

Both platforms use version 2.0.1 and WFAS v2. Android release package is io.github.mu23xr.audiobridge; the independent preview package remains .test. Windows stable and preview configuration remain separate. Migration and known limitations are documented in [2.0.1 notes](releases/2.0.1.md).

The root release workflow requires Android unit tests, lint, strict native audio acceptance, Windows checks and governance on the same integration commit. Actual package identities, source manifests and checksums must match, and uploaded assets must be downloaded and verified before stable/latest publication. Publication is pending those CI gates; no successful release is claimed here. The initial native run exposed an invalid CI audio environment. The CI runner now uses the full emulator backend on an isolated virtual display, binds the verified PulseAudio Unix server and requires an owned active stream during instrumentation. With that valid path, one c8d2f9d native run exceeded the unchanged resume-loss limit and another passed. The queue-overflow evidence and deterministic regressions guide the follow-up; neither a simulation nor a single earlier passing run substitutes for fresh native acceptance.

Open runtime and security issues remain in the [issue tracker](https://github.com/mu23XR/AudioBridge/issues). Compilation and synthetic tests do not close real-device, overnight or network-configuration issues. Existing published tags/assets remain immutable.

Release work item: [release-2.0.1](work-items/release-2.0.1.md). The public project state summarizes product and release facts; private device diagnostics and personal session history are not publication material.
