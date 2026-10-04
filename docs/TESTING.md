# Testing Policy

## Required CI

Every pull request that changes relevant files should run:

- Android unit/build validation
- Windows Desktop build validation
- governance checks

## Android manual regression checklist

For parallel testing, keep the existing test.16/stable installation. Local debug uses `io.github.mu23xr.audiobridge.debug`; signed CI preview uses `io.github.mu23xr.audiobridge.test`; both are labeled AudioBridge Test. Permissions, settings and Shizuku access must be granted separately. Stop the existing app's active transmission before starting a test sender; do not run two captures on the same device. Confirm the permanent package/version remains installed before and after testing. Verify adaptive/manual choice, exact numeric entry, reconnect-to-apply behavior, and one mode-control/streaming notification during WFAS operation; tap the runtime notification to adjust volume.

For changes touching runtime/services/audio:

1. Start SEND using Shizuku.
2. Confirm local playback behavior is as intended.
3. Lock screen and confirm streaming continues.
4. Return with Home/backgrounding and confirm streaming continues.
5. Swipe AudioBridge from Recents.
6. Confirm sender/receiver stops.
7. Confirm Shizuku UserService/AudioPolicy no longer owns the route.
8. Before SEND, set the phone/tablet speaker media volume to a distinctive value (for example 0%).
9. While SEND is active, set the sender software/transmission volume to a different value (for example 80%).
10. Swipe AudioBridge from Recents without pressing OFF first.
11. Confirm the receiver stops receiving audio.
12. Confirm the phone/tablet returns to its original speaker route and original route volume (0% in the example), not the 80% transmission/virtual-route value.
13. Confirm media volume buttons control the local output again.
14. Reopen and reconnect; ensure no stale state remains.
11. Test RECEIVE background/reconnect.
12. Test SEND <-> RECEIVE role switching.

## Protocol regression

When changing WFAS:
- Android -> Android
- Desktop -> Android
- Android -> Desktop
- mismatch behavior
- reconnect/liveness
- BYE/task teardown
- packet loss/reordering where applicable

Wire-incompatible changes require a protocol-version bump.


## Silent-source durability

1. Start a Shizuku SEND/RECEIVE session.
2. Stop all media playback on the sender without pressing OFF.
3. Leave the source silent long enough to cross the old 30-second timeout.
4. Confirm the session remains connected.
5. Resume media playback and confirm audio returns without reconnecting.

## Recents removal

With SEND active, swipe the app from Recents without pressing OFF first.
Confirm the receiver stops promptly and the sender's local audio route/volume is restored.
This test is invalid if the service is configured with `stopWithTask=true`; the runtime
services are intentionally configured to receive `onTaskRemoved()`.
