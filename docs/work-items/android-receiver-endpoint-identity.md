# Android receiver endpoint identity: rewritten source ports

Owner: Codex. Tracking: [Issue #61](https://github.com/mu23XR/AudioBridge/issues/61),
existing Draft PR #40. 2026-10-21: user authorized a scoped commit of this repair;
final local validation passed. Fresh native/real-device acceptance remains open.
No release or physical-device action.

## Scope and authorization

User report: with the current test build, one device had a userspace VPN active and the two
devices could no longer connect ("以前随便开 VPN 都可以使用"). Earlier in the same session the
stream had connected but played with heavy stutter and clipping. The user authorized read-only
device diagnosis and a local fix; installing the fix on the real devices is a separate approval.

Boundary for this work item: no real-device install, no VPN or device setting change, no
overwrite of the versions the user keeps for daily use.

## Symptom and evidence

The receiving client retried forever and each attempt ended after exactly six seconds with
`NO_ACK`; the sending side reported a client connection every attempt.

Evidence comes from the app's own diagnostics (exported read-only with the shell-UID
`content read --uri content://<test package>.diagnostics/bundle` path documented in
`docs/ANDROID_DIAGNOSTICS.md`); values below are the event names, kept free of private
addresses because the raw bundle stays local:

```
SOURCE_SOCKET_READY      peer=<advertised sender endpoint>
SOURCE_HELLO_SENT        x6, one per second
SOURCE_ENDPOINT_REJECTED count=1..6   from=<sender address>:<rewritten port>
                                       differentAddress=false  differentPort=true
SOURCE_SESSION_END       reason=NO_ACK received=false rejectedEndpoints=6
SOURCE_RETRY_SCHEDULED   delayMs=10000
```

Every rejection carried `differentAddress=false` and `differentPort=true`, and one diagnostic
session accumulated 342 rejections, all from a single rewritten source port. The replies
therefore reached the client within tens of milliseconds and were discarded by the client's own
endpoint check, not lost by the network.

## Root cause

Two facts together:

1. The sending device runs a userspace TUN VPN. It re-originates the sender's UDP replies, so
   they leave with a source port that differs from the port the sender advertised and listens on.
2. The client compared **address and port**. `git log -S 'isPeer'` and `git log -S 'differentPort'`
   both land on `819eefc` ("fix(android): recover receiver sessions and synchronize remembered
   volume", the test20 implementation). Before that, test19 (`f1662f9`) only had
   `if (packet.address != remote) continue`, i.e. address-only matching, which is why a VPN used to
   be harmless.

So this is a regression introduced by our own stricter endpoint check, not a change in the VPN
or the sender backend: the Shizuku sender predates test20 by many commits.

## Implementation

- New `PeerEndpointPolicy` decides the verdict for one datagram: a different address is always
  rejected; multicast trusts the address because group senders share a port; the advertised port
  and a previously learned port are accepted; otherwise only a **handshake reply** observed
  before the endpoint is bound may learn the rewritten port. `isHandshakeReply` recognizes only
  the handshake shapes (`HELLO_ACK*`, `WFAS_INCOMPATIBLE`, `WFAS_BUSY`, `WFAS_PENDING`,
  `WFAS_UNAUTHORIZED`, `WFAS_AUTH_REQUIRED`).
- `ReceiverTransport.isPeer` consults the policy, records the learned port, and emits a
  rate-limited `SOURCE_ENDPOINT_REBOUND` when a session re-points at a rewritten port. The
  existing `SOURCE_ENDPOINT_REJECTED` counter is unchanged. Learning is disabled the moment the
  handshake completes (`endpointBound`), and both fields reset at the start of every attempt.
- `PING`/`PONG`/`BYE` and audio frames deliberately never widen the accepted set: three arbitrary
  bytes would otherwise let an unrelated socket on the same host tear a session down
  (`SERVER_BYE`) or starve the real source by outrunning the one-second heartbeat. The first
  attempt accepted control-shaped traffic mid-session and failed the cue fixture; independent
  review flagged it and the change was narrowed to the handshake window.
- The learned port lives on the per-attempt `ReceiverTransport` instance, so every reconnect
  starts from the advertised port again.
- A source-port rewrite that begins mid-session is not adopted in place: the client hits
  `ACK_ACTIVITY_TIMEOUT` (or `NETWORK_ACTIVITY_TIMEOUT`) and reconnects, and the new handshake
  adopts the rewritten port. The wire format is unchanged, so `WFAS_PROTOCOL.md` keeps version 2.

## Validation and limits

- 118 JVM tests in 24 suites pass, including ten `PeerEndpointPolicyTest` cases (shape
  classification, priority of the advertised port over a stale learned port, keepalive/volume
  traffic cannot rebind, audio magic is never a handshake reply).
- Ten native methods pass on the isolated MuMu emulator (`OK (10 tests)`). `ConnectionCueDeviceTest`
  was strengthened twice: the foreign socket now also sends `PING`/`HELLO_ACK;v=2`/`BYE` after the
  handshake (none may re-point or end the session), and a new fixture pins that a handshake reply
  from a rewritten port binds the session, audio from that learned port is accepted, and audio from
  a third port stays rejected. `docs/TESTING.md`'s "skipped fixtures are not passes" applies here:
  the original foreign-socket fixture never sent a control shape, so the first implementation was
  green without actually pinning the new behavior.
- Not validated yet: real-device acceptance with the VPN on. Emulator results do not substitute
  for it.
- Follow-ups flagged by the original review, not fixed here: that Desktop baseline performed no
  endpoint check (its receive loop only inspected payload magic/version); this is historical
  evidence, not a fresh audit of the newer independent `desktop-keepalive` implementation.
  The Android sender's acceptance of `CLIENT_BYE`/`PONG` compares
  IP+port, which is the mirror image of this defect when the *receiving* device sits behind a
  rewriting VPN. Both are separate work.

## Delivery

The user authorized committing the existing repair on 2026-10-21. This supersedes
the previous local-only commit boundary; device installation is a separate action
and is not a prerequisite for preserving the source in Git. Keep the existing
Draft PR #40 and Issue #61 open. Do not merge, promote stable, publish a release
or operate physical devices as part of this commit.

Pre-commit review found two corrections: enforce at most one learned port even
during a multi-step handshake, and check third-port audio rejection before the
first valid source frame so the native fixture cannot pass on an already-set
connected flag/one-shot cue. Protocol text now distinguishes receiver reply-port
learning from the sender's unchanged exact-endpoint control-request validation.
The strict native runner now expects both connection-cue transport methods (12
total instead of 11); its zero-skip and exact-count requirements remain intact.
Final validation passed: 158 JVM methods in 26 suites (zero failures/errors/skips),
22 Python checks, lintDebug with zero errors (223 warnings, 20 hints), assembleDebug
and assembleDebugAndroidTest. The Gradle run completed in 6m47s; evidence is under
ignored `out/endpoint-final-20261021-123831.log` and `out/endpoint-python-20261021.log`.
Independent review of the endpoint rule, fixtures and strict native manifest is
clear. The first Python run exposed hardcoded 11-method fake output after the
manifest increased to 12; fixture counts were updated and all 22 checks rerun.
No acceptance threshold was relaxed. The earlier native passes
above apply to the pre-review implementation, not a fresh execution of this change.

The verified test21 APK remains source `9ecbbc4`, version `1.3.1-test.21`, code
`610181030`; committing this source does not change that APK. A later delivery
must request a new test version and increasing versionCode. Build-request and
version configuration are unchanged in this commit; publication stays disabled.
