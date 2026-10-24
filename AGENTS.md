# AudioBridge repository entry

Repository: mu23XR/AudioBridge. Read docs/PROJECT_STATE.md and the relevant work item before changes. Desktop publication and continuation are described in docs/DESKTOP_PREVIEW.md and docs/work-items/desktop-github-preview.md.

- Preserve existing changes and use a task branch. Do not push directly to main, merge a Draft PR or promote a preview without authorization.
- Keep Android source/signing independent from a PC-only task. Never commit private keys, credentials, device logs, raw audio or generated application packages.
- The root WFAS_PROTOCOL.md is the protocol source. Preserve WFAS v2 compatibility for platform-only changes.
- Desktop checks run through desktop/gradlew(.bat) check, including the native lifecycle fixture in scripts/. CI builds a fresh package from the recorded commit; local binaries are not CI attestations.
- Update the work item at diagnosis, implementation and verification checkpoints. Record failed and unrun checks, known issues and the exact source/asset identity. Passing CI is not physical-device listening or overnight acceptance.
- Keep PC previews explicitly prerelease/latest=false. Existing published assets and tags are immutable; do not overwrite them to recover a failed build.
