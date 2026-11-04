# AudioBridge

Unified Android + Desktop low-latency LAN audio streaming project.

## Project status and contribution

- [Open Issues](https://github.com/mu23XR/AudioBridge/issues) are the source of truth for active work.
- [Android stabilization tracker #6](https://github.com/mu23XR/AudioBridge/issues/6)
- [Repository governance tracker #7](https://github.com/mu23XR/AudioBridge/issues/7)
- [CONTRIBUTING.md](CONTRIBUTING.md)
- [SECURITY.md](SECURITY.md)

Chat history is supporting context, not the only record of unresolved work.

## Layout

- `android/` — Android sender/receiver, including the Shizuku system-audio bridge.
- `desktop/` — Kotlin/Compose Desktop sender/receiver with native Windows audio capture.
- `WFAS_PROTOCOL.md` — shared wire protocol used by both applications.

Both applications are maintained together so protocol, reconnect, liveness and audio behavior can evolve in sync.

The first Desktop integration target is Windows 10/11 x64, while the existing Linux/macOS architecture remains in the tree.

## Unified Releases

Android and Windows Desktop share one GitHub Releases page.

Creating a `v*` tag such as `v1.2.1` builds both applications and publishes the signed Android APK and Windows portable ZIP together in the same release. Both in-app update checkers use this shared release source.

## Build

Android:

```bash
cd android
./gradlew :app:assembleDebug
```

Desktop:

```bash
cd desktop
./gradlew createReleaseDistributable
```

This repository continues under EUPL v1.2 and retains upstream attribution and third-party notices.
