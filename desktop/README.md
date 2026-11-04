# AudioBridge Desktop

AudioBridge Desktop is the PC side of the AudioBridge monorepo. It can send local system audio to Android/Desktop receivers or receive WFAS audio from another device.

Windows 10/11 x64 is the primary validated Desktop target for the current development phase.

## Current capabilities

- Desktop -> Android / Desktop WFAS streaming
- Android -> Desktop playback
- native system-audio capture where supported
- automatic and manual discovery
- unicast / multicast WFAS
- RTP / HTTP / DLNA / Snapcast support
- optional authentication and encryption
- QR pairing through `audiobridge://pair?... `
- system tray and CLI support
- shared releases with the Android app

## Repository

AudioBridge Desktop is maintained only inside:

https://github.com/mu23XR/AudioBridge

Releases:

https://github.com/mu23XR/AudioBridge/releases

Open work:

https://github.com/mu23XR/AudioBridge/issues

There is no separate upstream download site, donation page or independent Desktop release channel for AudioBridge.

## Build

Requires JDK 17 or newer.

```bash
git clone https://github.com/mu23XR/AudioBridge.git
cd AudioBridge/desktop
./gradlew check
./gradlew createReleaseDistributable
```

Windows:

```powershell
cd desktop
.\gradlew.bat check
.\gradlew.bat createReleaseDistributable packagePortableArchives
```

## Protocol

Desktop and Android share the root protocol specification:

[../WFAS_PROTOCOL.md](../WFAS_PROTOCOL.md)

Wire-incompatible changes require coordinated changes on both platforms.

## Project history

Early Desktop code and design evolved from and referenced Marco Morosi's open-source WiFi Audio Streaming Desktop project. AudioBridge Desktop is now maintained as part of the independent AudioBridge repository and uses the repository's own release/update path.

Required copyright/license notices and third-party attribution remain in source and license files.

## License

See:

- [LICENSE.md](LICENSE.md)
- [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
