# test21 review corrections — 2026-10-18

Status: continued repair/validation in progress; final local140 JVM and15 Python
tests pass, zero-error lint/debug/helper builds pass. Latest MuMu native9/12 is
not acceptance; standard SDK-emulator CI is being added without weakening the
old bounds. Owner: Codex, branch fix/android-multisource-recovery, existing Draft
PR40. Related Issues5,14,45,50,51,52,53; review:
docs/handoffs/2026-10-18-test21-code-review.md.

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

## Final implementation/validation checkpoint

Correction commits25ede1e and e45a7f6 are pushed to existing Draft PR40. Android,
Windows, governance and signed artifact-only test21 CI all pass for exact source
e45a7f650999e753c11f0da420e22d78c5ea9470. Run39109951133 creates Android/Windows
artifacts; publication is correctly skipped. No Release or installation. The
pending VPN tracked/untracked files remain unchanged and unstaged.

After the other task completed checkpoint3233196 and closed1, the serial run
out/test21-emulator/20261018-160923 completed11 methods,10passed/no skips. This
time the remaining adaptive test failed its **actual discard assertion**, not the
producer guard: the before-silence3.006s phase discarded48128B after its warmup,
while producer maximum lag was27.212ms. The head stayed2560 at phase start and
203ms, then advanced again; this does not by itself establish why output stalled
or whether the device position was temporarily stale. The small-packet single/
dual test, manual silence/resume and35s silence contract all passed in this run.

A single-method follow-up, focused-adaptive-20261018-161813, still failed before
silence on progress90656/163008frames. Raw AudioTrack head resets during local
flushes can also distort that progress metric; correlate the persistent journal's
local-recovery/underrun/rate/trim events before naming a cause. Do not dismiss this
as a producer-only/environment-invalid result, and do not weaken assertions,
raise the default target or add a warmup to conceal it. The four reviewed code/
provenance mechanisms are corrected, but adaptive20ms runtime acceptance remains
open and this candidate must not be promoted as fully validated.

The serial host remained severely memory constrained (0.01–0.53GiB observed even
with only the owned VM active). No user app/foreign process was terminated to
free RAM. Own2 was closed and its owner window released; no further VM occupation
is planned without a usable resource window. Next: with adequate host resources,
collect the existing debug journal through run-as/provider on owned2 and compare
AudioTrack head/timestamp/start-threshold/operation-duration evidence around the
initial adaptive stall, distinguishing counter resets from FIFO/native output
loss. Repeat the unchanged focused test, then the full suite after any actual fix.
Keep Issues5/14/50/51/52 open; CI success is not a native-quality acceptance.

## Resumed adaptive investigation (2026-10-18)

User explicitly asks to continue repair. Root claims the remaining adaptive20ms
acceptance failure in this work item and Issues5/51. Static review confirms raw
AudioTrack head subtraction is invalid across local flush/rebuild; cumulative
observed progress and explicit reset/loss evidence must remain distinct. This
cannot explain away the serial run's48128B software discard. Before tuning,
retrieve existing private debug journals and correlate rate changes, recovery,
startup/head and queue loss. Keep all acceptance thresholds and targets unchanged.
Only dedicated emulator2 may be used after coordinating its resource window;
physical devices, instance1 and the shared ADB server remain untouched. Preserve
all pending VPN changes. No Release, promotion, merge or issue closure.

Existing journal retrieval succeeded using debug run-as (private ignored journal-
20261018-163800). Bad serial/focused phases have no local recovery or rebuild;
raw-head resets are an independent real measurement defect, not the explanation
for these losses. Fresh unchanged-source focused run163820 fails130048/144864
frames and58880B discard, producer max lag17.889ms. Native FAST output creation
succeeds; rate attempts return-3 (rejected), with no evidence of recreation.
Implementation adds cumulative observed progress across flushes/replacements,
separate native-loss/recovery/epoch counters, and rate-limited >40ms operation/
loop-gap diagnostics plus actual start-threshold/head/underrun snapshots. Four
new JVM regressions cover reset/wrap/stale readings and lost-tail accounting.
Native/software discard assertions and configured targets remain independent.
Compiling with own2 closed; dedicated2 journal access was the only device action.

Diagnostic baseline164551 (new counters, same FAST playback policy) reproduces
128512/144432frames and nativeDiscardedFrames=0/localRecoveries=0/epoch1. No
post-build loop gap or output operation reached40ms while the head stalled;
actual threshold stayed480 and native admission35ms. This excludes local reset
and a >40ms application scheduling gap for this observed interval. FastMixer
postdump reports512-frame period, measuredWarmup23.4ms/10cycles and57underruns/
59overruns; these do not alone prove the host HAL's exact blocked duration.
A separate confirmed issue is FAST rejecting every rate change with-3, so actual
clock-drift correction never executes. A/B candidate uses a normal rate-adjustable
track, retaining the same20ms target,10ms start threshold and35ms native admission.
No producer/progress/discard threshold changes or startup warmup are allowed.

Normal-track cold A/B165017 now accepts rate changes (48012..48156), fixing the
FAST/control incompatibility tracked separately in Issue53. It still fails initial
129795/144432frames and48744B discard; no local reset, post-build >40ms loop gap
or threshold change. Adaptive legitimately moves20→30 after two shortage samples.
Do not attribute the cold-output stall to FAST alone or call this a full fix.
Final regressions now also cover fixed manual20, read back AudioTrack.playbackRate
rather than internal success only, and count flushed native loss under the same
100ms discard cap. Normal cancellation no longer reports the interrupted native
operation as an active playback failure. Rate events carry output identity and
are limited to one per10s; operation/loop warnings are limited to one per1s.

Final local JVM140/25suites,15 tool tests and zero-error lint (231warnings,
20hints) pass; debug/helper builds pass. Dedicated2 temporarily used4CPU instead
of2, RAM unchanged, then was shut down and restored to2CPU. Cold focused170034
still fails125404/144528frames; head/reset evidence excludes counter reset, and
actualRateHz equals requested accepted values. Source-ready minBytes12312 on
this emulator is about64ms, larger than35ms admission; this suggests platform
quantum/minimum-output limitations but is not license to raise user targets.
Full native run (12 methods including one separately pending VPN fixture) has
9passes/3failures: adaptive initial loss;35s-resume21408B exceeds unchanged19200B;
manual45 producer126ms stall invalidates that phase. Fixedmanual20 did pass with
native rate readback; do not describe the entire suite as passing. A separate
second-source assertion now also includes native flush loss under the old cap.
The normal-output candidate remains under validation, not ready for promotion.

To isolate the platform, add a reusable standard Android35 x86_64 GitHub-emulator
native check in PR40 (debug/no secrets/no APK publication). It must retain the
same input, silence, progress and loss assertions and reject skips; the clean
committed suite has11methods, the local pending VPN fixture adds one. Compare
cold/hot state honestly, save instrumentation and system output evidence, and
keep the MuMu failures visible even if a different emulator succeeds. No instance1
or physical-device action. Issue53 tracks the newly identified clock-control
incompatibility separately from initial stalls. Next: verify source/workflow,
push scoped commit, inspect actual native CI result before candidate claims.
