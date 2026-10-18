# test21 review corrections — 2026-10-18

Status: implementation complete; final135 JVM and15 Python tests pass, zero-error
lint/debug/helper builds pass. Full native acceptance waits for a resource-isolated
window; no11/11claim. Owner: Codex,
branch fix/android-multisource-recovery, existing Draft PR40. Related Issues5,
14,45,50,51; review: docs/handoffs/2026-10-18-test21-code-review.md.

## Authorization and boundaries

User authorized repairing the reviewed defects. Preserve the existing uncommitted
VPN endpoint changes and unrelated source/Desktop work. Test only the dedicated
MuMu instance2 (AudioBridge-test21); never operate instance1, global ADB services
or physical devices. No stable promotion or issue closure. Publishing/installation
is not necessary to complete this local repair; deliver verified source and an
accurately identified candidate without claiming an unrequested stable release.

## Plan and acceptance

1. Make the backlog budget reachable below the hard FIFO ceiling; return retained
   bytes, retain newest aligned audio, and mark/smooth the resulting discontinuity.
   Test the actual queue-policy-drain pipeline, overflow and short/zero writes.
2. Replace the misleading one-shot zero feed with an explicit bounded silence
   strategy. Preserve manual/adaptive targets, observe native admission and verify
   immediate as well as prolonged-silence resume. Ensure output stall recovery is
   not hidden behind the pump's full-buffer gate.
3. Strongly own live cue players until completion/error/timeout, release once,
   and exercise ownership and cleanup without making emulator sound.
4. Preserve the old mismatched APK bundle as historical evidence; prevent metadata
   refresh from attributing an unknown binary to a commit. Build a new candidate
   from a recorded clean source and generate/verify package/version/hash/public
   signer/source records together in a fresh directory.
5. Run appropriate JVM/lint/build/native checks with unchanged existing acceptance
   thresholds, review changes independently, and update work items/current state.

## Entry evidence

HEAD e31f68b. Existing modifications: WFAS_PROTOCOL, ReceiverTransport,
ConnectionCueDeviceTest, PROJECT_STATE, stable1.3.1 notes, endpoint helper/tests/
work item, plus the prior review and test21 work-item pointer. None may be
discarded or silently attributed to the current correction. Existing reports:
108 original test21 JVM methods +10 uncommitted endpoint methods; two original
9-method native passes and a later10-method endpoint pass. These are prior
evidence, not substitutes for the repair baseline/final checks.

## Implementation checkpoint

Baseline Gradle testDebugUnitTest succeeded in20s using its up-to-date118-method
report. Issues5/50/51 now record the authorized correction; Issue52 tracks artifact
provenance. Before edits, the existing tracked diff was saved under ignored out/.

Implemented locally: drain before reachable50ms software trigger, retain40ms with
250ms cooldown and60ms overflow safety; restore trim-boundary ramp notification;
continuously refill intentional silence using at most10ms chunks up to the current
native target; sample real-PCM stall recovery independently of write admission.
Recovery primes the actual output start threshold (full effective buffer on old
APIs), counts accepted preroll and rejects incomplete priming explicitly. Idle
zero writes do not repeatedly flush a deliberately silent/OEM-suspended sink.
Cue lifetime ownership/deadline/exactly-once cleanup and a clean-source immutable
preview-bundle tool are implemented. Fifteen Python tool tests pass.

The first full JVM run had133 methods with one failure in a new fake-output test
that omitted normal startup's initial progress sample; the test now initializes
that state explicitly and retains its recovery assertion. Prior native assertions
are retained and strengthened with silence head progress plus35s idle/resume;
final JVM/lint/APK/native validation is in progress. No physical-device actions,
instance1 operations, APK replacement or publication.

Final-source JVM/lint/debug/helper build now passes (135 methods,25 suites,
0failures/errors/skips; lint0errors,231warnings,20hints). A final rebuild includes
the post-flush playback-head-origin read; bytecode was checked after noticing the
one-line addition overlapped the previous compiler input snapshot. Python15/15
and governance identity checks pass. Build25ede1e scopes the provenance tool,
its tests/CI gate and release policy independently; no VPN file was staged.

First native correction run out/test21-emulator/20261018-153759 has11 starts,
9successes and2failures (no skips). Both failed at the fixture's producer-health
guard after95–100ms scheduler gaps (>80ms), not the discard/progress assertion;
the35s silence contract passed. This is incomplete validation, never an11/11pass.
Own2 was shut down for the final build. The other task is still using1 and was
asked to coordinate a short quiet window; no1/global-ADB operation was issued.

The old local APK was inspected by the new SDK-backed tool: code610161312,
SHA A16CBBE0…, permanent public signer matches, exact source remains unknown.
out/verified-test21-release/UNVERIFIED.txt flags this without altering the original
binary/metadata. Its invalidation and the new clean-source tool do not pretend to
have rebuilt/published a corrected permanent-signed candidate.

Second final-source run out/test21-emulator/20261018-155556 completed11 methods:
8passed,3failed at the same producer >80ms scheduling guard (up to124ms), no skips.
Manual sole-output resume passed with0phase discard. With both VMs active host
available memory measured0.53–0.62GiB, no owned Java/Gradle process remained.
The2号 startup preflight also aborted once before any install/test while its ROM
was still starting; after boot, only transport127.0.0.1:16448 was disconnected/
reconnected to clear its offline state. No shared server restart. Own2 is now
closed; the other owner is asked to finish its current stateful step, then
temporarily release1 for a two-minute serial run. Do not change thresholds or
reinterpret these incomplete runs as success. Exact next step: start only2 once
the quiet window is available, guard its identity/ABI, run the existing runner
with expected `OK (11 tests)`, retain the reports, then close2 and release the window.

Independent review, including the final restart-preroll/origin increment, reports
no remaining blocking source findings. Native API<31/OEM listening/overnight and
the existing in-flight overflow discard-counter overestimate remain limitations.
The original pending VPN files are preserved and excluded from this correction's
commits. Local135 includes its10 existing JVM methods; the correction's committed
test set is125 methods. A CI artifact-only test21 request can validate that clean
commit without publishing a Release or installing it on devices. It does not
silently include the separate pending VPN fix.
