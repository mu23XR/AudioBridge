# Project State

## Stable 2.0.1 release — in progress

Tracking: [Release #74](https://github.com/mu23XR/AudioBridge/issues/74).

The authorized release starts from Android source 6a41f957a72ab2615f4a0be3574f8b436372e4a9 and Windows product source 8448887200e2363236156ff6ce1590c8aff01fc3. The existing permanent signing identity is preserved. Windows includes source preference, output selection, live gain, continuous playout and connection recovery repairs. The owner also authorized two narrow follow-up repairs identified by release checks: failed Desktop output initialization must release ownership before publishing failure (#76), and Android small-packet draining must avoid redundant native recovery/head queries (#51). These candidates require fresh validation; installed applications have not been replaced.

Both platforms use version 2.0.1 and WFAS v2. Android release package is io.github.mu23xr.audiobridge; the independent preview package remains .test. Windows stable and preview configuration remain separate. Migration and known limitations are documented in [2.0.1 notes](releases/2.0.1.md).

The root release workflow requires Android unit tests, lint, strict native audio acceptance, Windows checks and governance on the same integration commit. Actual package identities, source manifests and checksums must match, and uploaded assets must be downloaded and verified before stable/latest publication. Nonpublishing repair candidate fbeff3b11ff41a06d70e4485229b3c00310b4439 passed all six workflows. Both native artifacts independently establish12/12 methods, zero skips, a verified active audio path and unchanged progress/queue/loss limits; adaptive resume discarded26.7ms and0ms respectively, within the original100ms allowance. Both35s silence/resume checks pass. These results do not establish zero loss or eliminate arbitrary host scheduling stalls. Stable publication is now requested under the owner's original authorization, but the final publishing source must pass all gates again and its actual assets must be verified before release completion is claimed.

Open runtime and security issues remain in the [issue tracker](https://github.com/mu23XR/AudioBridge/issues). Compilation and synthetic tests do not close real-device, overnight or network-configuration issues. Existing published tags/assets remain immutable.

Release work item: [release-2.0.1](work-items/release-2.0.1.md). The public project state summarizes product and release facts; private device diagnostics and personal session history are not publication material.
