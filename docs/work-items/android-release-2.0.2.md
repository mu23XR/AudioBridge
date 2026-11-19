# Android 2.0.2 stable release

Tracking [Issue #96](https://github.com/mu23XR/AudioBridge/issues/96) and the
playback regressions in [Issue #51](https://github.com/mu23XR/AudioBridge/issues/51).

The owner approved an Android stable release from the latest `main` playback
fixes. The product source is `7cb412b3735699ea6590933cf3fc6faa93290eac`.
The Windows tree matches the published v2.0.1 source. Keep its existing build
check and distribute the v2.0.1 Windows archives from their original Release.
Both clients select updates using platform-specific asset names.

The root stable workflow runs the original Android unit, lint, native, Windows,
governance, signing and source/hash checks. An explicit Android platform request
selects the APK and metadata as the complete bundle. GitHub must verify the
new v2.0.2 downloads before stable promotion. Preserve the previous release and
the disabled Dependabot configuration.

Local validation passed 64 script tests, 9 desktop publication script tests,
and workflow lint. The Android-only regressions require the exact asset list
and reject a modified staged download before promotion. The release branch
diff is limited to release tooling and documentation. The Android and desktop
trees and the WFAS protocol match the approved main source.
