# test21 code and delivery review — 2026-10-18

## Scope

User requests review only. No product source/configuration changes, GitHub mutations,
builds, ADB connections, emulator operations or physical-device operations were performed.
Review uses committed `065cb01..e31f68b` plus separately identified existing uncommitted
endpoint-identity changes. Do not treat those changes as part of a clean test21 commit.
Existing worktree modifications are preserved. Only this review and a work-item pointer
are added to project memory; an ignored Java reproduction is saved under `out/`.

## Confirmed findings

1. **High priority: the local APK cannot be bound to the recorded source using its
   current verification manifest.** `out/verified-test21-release/verification.json`
   records source `e31f68bc146013c918ce62e494fbaf39302fa7b0`, versionCode `610152047`
   and APK SHA-256 `be8cd535...`. The existing APK actually reports versionCode
   `610161312` via SDK `aapt2 dump badging`, and its SHA-256 is `A16CBBE0...`.
   It matches the current preview build output. `SHA256SUMS.txt` matches the newer
   binary, but `verification.json` and `SOURCE_COMMIT.txt` retain the older record.
   This demonstrates inconsistent provenance, not malicious modification or a
   signing-key problem. Regenerate all metadata atomically from an immutable clean
   source/build before delivering the candidate; do not claim the current binary
   contains only the recorded commit without verifying its actual build inputs.

2. **Queue cleanup is unreachable in the real pipeline.**
   `ReceiverBufferPolicy.kt:70` returns null whenever the software queue is at or
   below its 60ms capacity. `PcmQueue.offer` guarantees that same bound before
   releasing its lock. Thus normal callers can never trigger the policy; overflow
   is already discarded by `offer`, and the advertised 50ms retention/250ms cooldown
   do not operate. Direct calls against the existing compiled classes, whose source
   files are unchanged from HEAD, offered 90 x256B packets to an11520B queue:
   queue11520B, discarded11520B, policyTrims0. The ignored reproduction is
   `out/ReviewTest21Queue.java`; it did not rebuild or change application classes.
   The policy's tests instead supply a hypothetical70ms queue, which the production
   queue cannot expose. There is a second latent error: that input with native=0
   returns20ms as the *retained limit*, so `trimTo` would discard50ms, the reverse
   of retaining50ms and discarding20ms. Fix the integrated bound/retention semantics
   and test the real queue-policy-trim chain, rather than only integer examples.

3. **The idle path does not implement its documented continuous keepalive and
   bypasses the normal native admission ceiling.** `ReceiverPlayout.kt:162` feeds
   at most one50ms zero block, then sets `pausedForSilence=true` until fresh PCM.
   No further zero blocks are fed during the same silence. Existing native evidence
   `out/test21-emulator/20261016-041916/fixture-log.txt` confirms the head is flat:
   manual45 from403ms to4437ms, adaptive from1009ms to4441ms. Therefore the green
   suite does not demonstrate the claimed continuous head advance. `feedSilence`
   also calls the governor directly, bypassing the pump's target+15ms admission;
   adaptive20ms can receive50ms of zeros, behind which returning real PCM waits.
   Decide on a bounded tail, budgeted keepalive or deliberate idle/restart design,
   then verify immediate and prolonged-silence resume under that actual contract.
   The previous causal story about a frozen head leaving no room is unproven:
   head immobility alone does not imply a full buffer, and the fixture resets its
   producer epoch on resume instead of replaying a silence-long backlog.

4. **Residual cue lifecycle risk.** `ConnectionSoundPlayback.kt:25` retains its
   MediaPlayer only through local variables/listener self-references. There is no
   live owner retaining it through completion. Android requires a reference to
   prevent garbage collection from releasing a playing MediaPlayer. The old
   implementation had the same pattern; this is a remaining weakness, not a new
   test21 regression. Keep active instances strongly owned until completion/error,
   with a bounded lifetime/cleanup policy. The cue native fixture replaces playback
   with a counter, so it cannot validate this lifetime or audible output. Reference:
   https://developer.android.com/reference/android/media/MediaPlayer

## Other follow-ups

- At a frozen native buffer exactly at the pump's admission ceiling, no write is
  attempted, so the governor's500ms write-time local recovery is not reached;
  the health path instead waits roughly5s to rebuild and consumes the three-rebuild
  allowance. The older receiver also gated writes by native occupancy, so this
  must not be presented as proven new in test21. Add an integrated frozen-output
  test before deciding how to expose progress recovery independently of admission.
- Removing `trimTo`'s discontinuity signal is not justified by saying surviving
  samples are internally contiguous: deleting a prefix still changes the boundary
  from already submitted PCM. `PcmRamp` changes sample amplitude, not accepted-byte
  admission. Since trimming is currently unreachable in this path, repair its
  reachability before separately validating boundary smoothing with waveform data.
- The control-generation reset and fresh silent-frame cue rules are reasonable.
  Batch draining with bounded work, exact partial-write retention and independent
  per-source clocks are appropriate improvements to the packet-count bottleneck.

## Evidence and delivery limits

- Both saved041524/041916 native reports have9 starts,9 successes and no skipped
  methods; the latter reports61.061s. They are genuine test runs, but do not prove
  acoustic quality, long-run stability, all Android API paths or the claimed
  continuous idle mechanism. No full suite was rerun during this review.
- Current JVM XML reports118 passing methods in24 suites, with10 from the existing
  untracked PeerEndpointPolicyTest. The other108 align with the committed test21
  set, but the current XML is not an isolated rebuild of clean e31f68b. The later
  saved native10-method suite likewise belongs to the endpoint follow-up.
- `git diff --check` passes; its line-ending notice is not a format failure.
- Read-only GitHub inspection: test21 Release lookup404; latest Release remains
  test20; Draft PR40 head is9ac88f0 while local HEAD is e31f68b. The build request
  still asks for test20 with publish=false. No tracked test21 release notes or
  CI-generated test21 Windows artifact were found. A local signed test21 APK
  exists, but CI publication and artifact provenance are not complete.

## Recommended order

Repair the reachable queue budget and retention semantics first; define and test
the actual silence strategy next; address cue ownership and stalled-output coverage.
Keep commits distinct from the VPN follow-up. Produce a clean-source candidate,
rerun the relevant regressions without weakening assertions, then use the existing
CI preview workflow and verify all assets/source/version/signer together. Keep
subjective/OEM/overnight acceptance open. This review authorizes none of those
implementation or publication actions by itself.
