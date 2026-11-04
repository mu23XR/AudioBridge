# test21 review corrections — 2026-10-18

Status: review corrections implemented and scoped regression passed for9ecbbc4.
147 committed-source JVM tests (157 local with10 separate pending VPN),22 Python,
zero-error lint/debug/helper build, Android/Windows/governance CI and all11 strict
standard API35 native methods pass,0skips. Permanent-signed artifact-only CI also
passes; fresh candidate identity verified, publication skipped. Earlier failed
native attempts remain evidence; OEM/overnight acoustic
quality, old-API behavior and post-fix MuMu acceptance remain unverified.
Owner: Codex, branch fix/android-multisource-recovery, existing Draft
PR40. Related Issues5,14,45,50,51,52,53,54; review:
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

Standard CI first run39114822655 passed build/package/source guards but exited
before Android boot: avdmanager's default user directory differed from emulator
37.2's lookup (Unknown AVD name). No native method ran; this is harness setup
failure, not an app failure/pass. Diagnostics artifact12132485030 retained.
Set ANDROID_USER_HOME/ANDROID_EMULATOR_HOME/ANDROID_AVD_HOME explicitly under
runner.temp, create the AVD at that path and assert its ini/list-avds before build.
Added seven parser regressions (all22 Python methods pass) for skip/partial/
duplicate/missing-class/count/runner-error rejection. Retry the exact same native
contract without changing any audio threshold. Non-native72e128f Android/Windows/
governance CI pass; signed artifact workflow remains separate/no publication.
The first directory correction's job-level runner.temp expressions were rejected
by workflow validation before creating any job (run39115418127). Use an explicit
/tmp/audiobridge-native-android directory on each disposable isolated Linux runner;
job-level env does not provide the runner context. This is another harness setup
failure, not native acceptance. Audio source/targets/assertions remain unchanged.

Standard API35 run39115588079 now executes the entire clean11-method contract:
9pass/2fail, zero skips (artifact12132680469; tested PR merge db43043cfe0da4563d19d8007d32293b3783ffce).
Adaptive before-silence now passes143896frames/3.013s with0discard; its resumed
phase instead fails the unchanged97.290ms producer guard. The independent35s
silence case fails real head progress; periodic stats show only~38kframes/s and
>40underruns/s with native<=20ms, no reset/rebuild. Runtime/system output evidence
is saved privately under out/native-ci-bc3. Hostavailable memory11.7GiB, so the
silence failure cannot be dismissed as the Windows host memory condition.

The per-tick single10ms refill waits for a second scheduling round even when
quantized head progress freed the entire20ms budget (standard HAL period1088
frames/22.67ms). Fix bounded refill: same tick up to two10ms chunks, recomputing
native headroom each time, stop on short/zero write, and never exceed target or
20ms batch. Five pure regressions cover budget/full/partial/zero/invalid writes.
No target/threshold/progress/loss acceptance changes. Standard AVD uses4CPU for
the next run to reduce producer scheduling invalidation; unchanged guard still
rejects any >80ms source gap. Real audio, targets and physical devices untouched.

Refill final-source validation:145 JVM/25suites,0failures/errors/skips; lint0errors
and debug/helper builds pass. Independent refill review is clear. Actual standard
consumer quantum still exceeds20ms, so only the unchanged native run can decide
continuity acceptance; do not infer success from fake-output coverage. WFAS single/
multicast share this player; RTP/Snapcast PcmPlaybackSink are outside this change.

Standard native refill acceptance now passes: run39116906589, artifact12132084385,
PR merge c8be68d6ca45dbb526741f763d5f22f48b7208ba for head a4dd846. result.json
and acceptance.json confirm11starts/completions,0failures/skips and all7 classes;
all prior loss/progress/native-budget limits remain unchanged. Original MuMu
failures are retained, not retroactively invalidated. Evidence downloaded to
ignored out/native-ci-a4.

One additional worker-owned continuity defect is confirmed under Issue45: after
idle zero feed, the ramp is already exhausted and the old RESUME branch ran only
after draining, so the first nonzero PCM could jump from zero at full amplitude.
Restart the existing5ms ramp at the first FIFO write callback after feedingSilence,
before native write/copy preparation; clear the flag there, irrespective of a
later SILENCE changing input state. Admission-full keeps the flag until a real
write attempt; zero/partial writes preserve existing accepted-frame ramp rules.
Short silence whose old FIFO/fade tail never drained is deliberately outside this
change. Existing ramp partial/zero/240-frame tests cover its function semantics;
final full build/native regression will cover the changed callsite. This does
not establish that every reported pop has this cause. No new buffering or target.

Final-source checkpoint ce353f6:145 JVM/25 suites,0failures/errors/skips and22
Python checks pass; lint0errors/231warnings/20hints, debug/helper builds pass.
Independent final diff/strict-runner review is clear. Android39117465057,
Windows39117465029, governance39117465025 and permanent-signed artifact-only
39117465077 succeed; publish job is skipped and latest Release remains test20.
Downloaded artifact12133365734 into fresh ignored out/test21-ci-ce353f6: source
ce353f68db43912de578d1e2faad09f7a1adf54e, package io.github.mu23xr.audiobridge.test,
version1.3.1-test.21/code610180959, declared/downloaded SHA256 and permanent APK
certificate verified. The old unverified bundle is unchanged; this is not a
physical install or a published/promoted Release.

Final native first attempt39117465042 tests PR merge
e359f011db8c68a655b8d87335dff32ef6b0368f and completes10/11,0skips. The35s
silent head/budget assertions pass, then resumed producer has122021us max lag
and trips the unchanged80ms fixture guard before acceptance can be established.
Other10 methods pass. This failed run/artifact12132333679 is retained in ignored
out/native-ci-ce, not relabeled as a pass. Retry only this failed native job once,
with identical source/thresholds; inspect its actual result before final claims.

Final-source retry39117465042 attempt2 is still10/11, but now fails REAL adaptive
resume software discard28540B, not the producer guard. Preserve artifact12133606053
under out/native-ci-ce-retry. Before3.018s has145508frames/0drop; after3.013s has
137053frames (~94.8%, progress passes), native loss/recovery/rebuild all0; producer
max63.269ms stays below80ms. Worker loop gaps67/43ms and WRITE42ms are observed.
Two independent reviews identify a definite ordering defect: a pump limited by
its2ms/20ms budget still has native headroom, but ReceiverPlayout soft-trims FIFO
before yielding/continuing. At WRITE42ms return native is only1ms yet3840B trimmed;
another2560B trims with native13ms. Correct writable limited continuation before
soft trim, preserve hard60ms FIFO,20ms target/20ms batch and all assertions.6400B
is only part of28540B, so do not claim this alone guarantees acceptance; remaining
overflow/scheduling and in-flight accounting need separate evidence. Implement
the confirmed minimal ordering fix with a deterministic delayed-write pipeline
regression before further native tests. No more unchanged retries until a fix.

Also confirmed independently: overflow can evict a chunk during an unlocked
native write and count its entire remaining prefix as discarded, including bytes
the native sink actually accepts on return. Correct in-flight counted-discard
accounting separately, keeping the cumulative actual-loss counter monotonic and
distinguishing non-counted stop clear. Zero/partial/full/throwing/invalid writes
need deterministic coverage; no queue lock during native writes. This accounting
fix is not a substitute for the actual scheduling/soft-trim repair and cannot
explain the full148.6ms measured loss. Dedicated per-source audio scheduling is
not yet implemented; first verify the small confirmed corrections.

Implementation checkpoint: ReceiverBacklogPolicy now receives the actual pump's
limited continuation state and defers soft trim without consuming its cooldown.
The next truly admission-blocked/short-write iteration still evaluates trim;
hard FIFO bounds remain independent. Two real queue/pump/policy regressions
simulate42ms preemption, verify byte-for-byte eventual drain and unchanged
admission, then verify blocked trimming/cooldown/hard overflow.

PcmQueue now owns one in-flight write, defers counted eviction of its request
prefix until accepted bytes are known, and immediately counts losses outside the
request. Count stays monotonic; stop clear(false) is distinct; partial trim does
not remove accepted bytes twice. Finally settles ownership on invalid/throwing
output, without holding the queue lock across the native call. Ten new boundary/
error regressions pass alongside the existing real-thread unlocked-write test.
Local157 JVM/26suites,0failures/errors/skips. No native threshold, target, queue
capacity, batch limit or production scheduling architecture changed. Continue
full build/review, then commit/push and run clean native acceptance once.

Full build checkpoint:157 JVM/26suites all pass, lint0errors/231warnings/20hints,
debug and instrumentation helper assemble pass in3m11s. Existing22 Python checks
remain passing with no tool-source changes since that run. Pending VPN source,
protocol/native fixture and stable notes still match the saved baseline and
remain unstaged/excluded. Await final independent queue review, then push.

Independent final queue review is clear: prefix intersections remain contiguous,
request-external loss counts immediately, counted/stop clear and invalid/throwing
writes settle correctly, and accepted bytes are not removed twice after partial
trim. Ready for scoped push and unchanged clean native contract.

Source9ecbbc466758b6b62bee5bb15ec9d4808f28323f is pushed to existing Draft PR40.
Clean native run39119476807 is executing; governance39119476909 and Windows
39119476821 pass, Android/signing checks still running. Issue54 separately tracks
the deterministic in-flight loss accounting bug;5/51 retain the real audio-loss
repair. No Issue closure or new Release. Only original pending VPN edits remain
outside the scoped commit.

Final native acceptance checkpoint: source9ecbbc4 run39119476807 succeeds;
artifact12133468841 tested PR merge7e60dbbdd47ea27d446868a0d84a187b3b871c3a.
Downloaded archive CRC, result.json, acceptance.json, SOURCE_COMMIT and actual
instrumentation OK(11 tests) verify11starts/completions, all7 expected classes,
0failures/skips. Evidence retained privately under out/native-ci-9ec. Both prior
ce353f6 failures, earlier MuMu reports and their actual loss remain preserved;
no thresholds were relaxed.147 committed-source JVM tests/157 local,22 Python,
zero-error lint/debug/helper builds and Android39119476866/Windows39119476821/
governance39119476909 all pass. Signed artifact workflow39119476829 is finishing;
do not reuse the failed ce353f6 APK as this source's candidate.

Remaining acceptance is explicit: no post-fix MuMu rerun, no real acoustic latency
measurement or physical/OEM overnight listening, no API<31 device acceptance.
Normal mixer latency can differ from FAST. WFAS single/multicast use the changed
player; separate RTP/Snapcast output is outside this repair. All runtime Issues
stay open, Draft PR40 stays unmerged and no Release/stable promotion/installation
is authorized here. Dedicated2 remains closed/restored2CPU;1/globalADB/physical
devices were untouched. Pending VPN source/native fixture/protocol/state/stable
notes remain excluded and unchanged.

Final artifact/CI checkpoint: all five workflows for implementation9ecbbc4 pass:
native39119476807, Android39119476866, Windows39119476821,
governance39119476909 and signed artifact-only39119476829 (publish skipped).
Fresh Android artifact12133678144 downloaded to ignored out/test21-ci-9ecbbc4;
ZIP CRC, declared/downloaded SHA256, SOURCE_COMMIT9ecbbc466758b6b62bee5bb15ec9d4808f28323f,
package io.github.mu23xr.audiobridge.test,1.3.1-test.21/code610181030 and permanent
APK signer all verified. verified.json records integrity and the exact native
merge/run, without attributing this APK to a later documentation-only commit.
GitHub Git-tree verification also confirms packaged head9ecbbc4 and native-tested
merge7e60dbb have identical tree d82f512287f0a6f3287818666ce3439fae992ab6.
Old unverified bundle and failed ce candidate remain separate. Latest Release
is still test20; no installation/publication/stable promotion/merge/Issue closure.

This scoped repair is implemented and verified to the recorded automated
contract. Remaining real listening/overnight/platform acceptance above is not
claimed complete. Project state, testing/diagnostics contracts and this handoff
are updated; only the existing pending VPN edits remain outside these commits.

Issues5/45/51/52/53/54 now record final scoped automated acceptance and explicit
remaining limits without closing them. Final follow-up commit contains memory
only; tested/packaged implementation stays9ecbbc4.
