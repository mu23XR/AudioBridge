# AudioBridge for Android

AudioBridge is a low-latency LAN audio bridge for Android. An Android device can act as either a sender or a receiver.

The primary Android path is now **App + Shizuku system-audio capture**: no root, and no MediaProjection screen-sharing prompt by default. MediaProjection remains only as an optional compatibility path.

## Current capabilities

- Android -> Android low-latency WFAS audio
- App + Shizuku system-audio capture
- explicit sender / receiver roles
- screen-off and background operation
- recovery from transient network changes
- silence-safe liveness
- explicit cleanup when the app task is removed
- independent send/receive volume controls
- RTP / HTTP / DLNA / Snapcast
- QR pairing via `audiobridge://pair?... `
- optional authentication and encryption
- Simplified Chinese / English UI

## Development status

Integration branch: `main`

Open bugs and active feature work are tracked in GitHub Issues:

- https://github.com/mu23XR/AudioBridge/issues
- Android stabilization tracker: #6
- Repository governance tracker: #7

Chat history is supporting context, not the task source of truth.

## Installation and releases

Future Android builds use the permanent AudioBridge identity:

- Application ID: `io.github.mu23xr.audiobridge`
- Releases: https://github.com/mu23XR/AudioBridge/releases

Historical Stable #153 / test.11 belong to an older package/signing line and are not the future publishing baseline.

## Build

JDK 17 and the Android SDK are required.

```bash
git clone https://github.com/mu23XR/AudioBridge.git
cd AudioBridge/android
./gradlew :app:assembleDebug
```

CI runs unit tests, Android lint and APK compilation.

## Protocol

Android and Desktop share the root WFAS specification:

[../WFAS_PROTOCOL.md](../WFAS_PROTOCOL.md)

Wire-incompatible changes must be coordinated across both clients.

## History

Early AudioBridge code and design evolved from and referenced Marco Morosi's open-source WiFi Audio Streaming projects. AudioBridge is now maintained as an independent project with its own product identity, releases, signing, Shizuku audio path and Desktop client.

Required copyright/license notices and third-party license texts remain preserved in source and license files. Upstream donation/download/product links are not part of the AudioBridge runtime UI.

## License

AudioBridge is distributed under the repository's EUPL v1.2 license terms.

- [LICENSE.md](LICENSE.md)
- [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
