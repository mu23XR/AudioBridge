# test21 adaptive follow-up — 2026-10-18

User authorized continued repair, emulator only. Existing Draft PR40/branch
fix/android-multisource-recovery is retained. Physical devices, the other task's
MuMu1 and shared ADB server were not operated. Dedicated2 is closed and its
temporary4CPU configuration is restored to2CPU. Pending VPN endpoint files remain
unstaged and excluded from the correction.

## Findings and implementation

- Raw AudioTrack playback-head subtraction is invalid across flush/replacement.
  Governor now preserves observed epoch progress and separately counts unplayed
  native tails. Receiver carries those totals across replacement. This does not
  explain away the earlier real FIFO loss; bad phases had no reset/rebuild.
- Normal stop can interrupt a native read/write on the release worker. Such
  cancellation no longer reports an active playback failure; cleanup still runs.
- Rate-limited >40ms operation/loop evidence distinguishes application scheduling
  from a non-advancing native sink. Actual threshold/mode/rate, output identity,
  recovery count, native loss and cumulative progress remain observable.
- FAST output accepts creation but rejects setPlaybackRate(-3), so clock drift
  was not actually corrected (Issue53). The normal-output candidate does apply
  native rate changes, verified by AudioTrack readback. Same target/threshold/
  admission bounds are retained. Final standard native regression passes; system
  mixer latency may differ, and queue counters are not acoustic measurements.

## Evidence and acceptance

Local157 JVM methods/26suites pass (147 committed-source plus10 pending VPN methods),
lint0errors/231warnings/20hints and debug/helper builds pass. Fifteen artifact-tool
tests pass; seven native-result parser tests add22 Python methods in total.
Independent review found no remaining product-code blocker; its second-source
native-loss assertion suggestion is included.

MuMu2 baseline/candidate evidence is under ignored out/test21-emulator. The final
4CPU cold focused run170034 still fails125404/144528frames with no reset or
post-build >40ms loop gap. Full run20261018-170302 completes9/12: adaptive initial
loss,35s-resume21408B above19200B, and manual45 producer126ms gap. Fixedmanual20
passed with actual native rate correction. These results are not a full pass;
neither producer-only invalidation nor4CPU is a sufficient explanation. minBytes
12312 (~64ms) is larger than35ms admission on this emulator; do not raise user
targets simply to hide a platform limit.

Source72e128f adds a reusable standard API35 x86_64 SDK-emulator CI check: clocked
Pulse null sink with audio enabled, no signing secrets/APK publication, clean
source/debug/emulator guards, exact11 committed starts/completions and zero skips,
plus failure diagnostics. Run39114822655 failed before boot because of mismatched default AVD directories;
explicit runner.temp paths and an ini/list-avds preflight correct the harness.
No native method ran in that attempt. A retry is pending. The local uncommitted
VPN fixture accounts for the twelfth local method and is intentionally absent
from clean CI. Keep original90% progress/100ms loss/60ms FIFO bounds and immediate
resume; native loss is included rather than hidden by the monotonic counter.

## Next action

Scoped regressions and signed artifact verification pass for source9ecbbc4.
Keep the explicit acceptance limits below and preserve all MuMu failures. Keep
Issues5/14/45/51/53/54 open, no Release/stable promotion/merge or
physical install. The old local test21 bundle remains explicitly unverified;
clean-source receipts, not refreshed metadata, establish new artifact provenance.

Current detailed work item: docs/work-items/android-test21-review-repair.md.


## Standard-emulator follow-up

After the AVD setup repairs (a6363e3/bc3d01b), run39115588079 actually executes
11 methods:9pass/2fail. Adaptive initial3.013s has143896frames/0discard and real
rate changes. Resume hits97ms producer invalidation. The independent35s silent
head fails progress at roughly38kframes/s, within20ms occupancy with no reset.
This is not a Windows-memory-only diagnosis (11.7GiB available on the runner).
The normal consumer quantum is1088frames/22.67ms; supplying one10ms chunk then
waiting for another scheduling round creates avoidable refill gaps.

New bounded refill supplies at most two10ms chunks in the same tick, re-reading
remaining native budget before each call and stopping on short/zero. It never
raises the target or adds50ms silence. Five budget/partial/error regressions bring
local JVM total to145 (135 committed plus10 pending VPN); lint/debug/helper builds
pass,22 Python checks pass and independent review is clear. Standard AVD4CPU
reduces fixture-producer scheduling risk without changing its80ms invalidation
threshold. Native rerun remains required. WFAS single/multicast use this output;
RTP/Snapcast still use their separate PcmPlaybackSink and are not covered.

## Final-source checkpoint

Refill source a4dd846 passes strict standard native11/11,0skips in39116906589
(tested merge c8be68d6ca45dbb526741f763d5f22f48b7208ba; artifact12132084385).
Final ce353f6 additionally restarts the existing5ms ramp at the first actual
PCM write after idle silence, preserving zero/partial-write accounting and
unchanged buffering targets. Independent review is clear; local145 JVM/22 Python,
zero-error lint/debug/helper builds pass. Android/Windows/governance and signed
artifact-only CI all succeed. Fresh artifact12133365734/sourcece353f6/package
io.github.mu23xr.audiobridge.test/version1.3.1-test.21/code610180959 has verified
source, downloaded checksum and permanent signer under out/test21-ci-ce353f6.
No Release or installation; the old mismatched local bundle remains unverified.

Final native first attempt39117465042/merge e359f011db8c68a655b8d87335dff32ef6b0368f
completes10/11,0skips:35s silent progress/budget passes; its immediate resumed
producer then stalls122ms and hits the unchanged80ms guard. Artifact12132333679
and diagnostics are retained under out/native-ci-ce. One unchanged-source
failed-job retry is in progress; do not report final native acceptance yet.

The identical-source retry is now10/11 with real adaptive-resume software loss
28540B, producer max63ms below80ms guard, native loss/recovery/rebuild0. Artifact
12133606053 retained in out/native-ci-ce-retry. Worker gaps67/43ms and WRITE42ms
are observed. Two independent reviews confirm soft trim precedes writable
limited-batch continuation:3840B dropped at native1ms and2560B at native13ms.
Fix this ordering while retaining all bounds, plus independently confirmed
in-flight counted-discard overstatement. Do not perform more unchanged retries
or call the signed artifact's identity verification audio acceptance. Current
implementation and next native run remain in the active work item.

The continuation-before-trim repair and in-flight monotonic actual-loss repair
are implemented in five source/test files, with12 new deterministic regressions.
Local157 JVM/26suites,0failures/errors/skips; lint0errors/231warnings/20hints and
debug/helper builds pass.147 methods belong to the committed-source candidate,
10 to the separate pending VPN work.22 Python checks remain passing. All targets,
queue/batch limits and native assertions remain unchanged; no dedicated thread
architecture added. Next: independent queue review, scoped push, clean native
run. Prior failing ce artifact has NATIVE_VALIDATION_FAILED.txt locally; identity
verification must not be confused with runtime acceptance.

## Final native acceptance

Source9ecbbc4 run39119476807 passes11/11,0skips on the isolated audio-enabled
standard API35 emulator; tested merge7e60dbbdd47ea27d446868a0d84a187b3b871c3a,
artifact12133468841. Downloaded result/acceptance/source/instrumentation and CRC
verify all7 class counts under out/native-ci-9ec.147 committed-source JVM methods
(157 local with10 pending VPN),22 Python and zero-error lint/debug/helper builds
pass; Android/Windows/governance CI pass. Signed artifact-only39119476829 is
finishing; its identity must be independently verified. Earlier ce10/11 actual-
loss/producer failures and MuMu9/12 remain retained, not reclassified.

Remaining: post-fix MuMu, API<31/OEM/overnight and acoustic latency are unverified;
normal mixer latency may differ from FAST, and RTP/Snapcast use a separate sink.
No physical devices, instance1/sharedADB, merge, Release or Issue closure. Pending
VPN work stays separate. Issue54 tracks the deterministic in-flight accounting
defect in addition to real playout loss in5/51.

All five workflows for implementation9ecbbc4 are now successful, including
signed artifact-only39119476829; publish job skipped. Fresh artifact12133678144
under out/test21-ci-9ecbbc4 verifies source9ecbbc4, declared/downloaded checksum,
permanent signer and package io.github.mu23xr.audiobridge.test/version1.3.1-test.21/
code610181030. verified.json records exact source and native merge/run; it must
not be relabeled to a later docs-only commit. Latest Release remains test20.
This scoped repair is complete to its recorded automated contract; future
physical/OEM/overnight/acoustic acceptance stays open, with no implicit device
window requested. Final docs checkpoint updates state/work item/handoff only;
existing pending VPN files remain unchanged and excluded.

Git-tree API verification confirms native-tested PR merge7e60dbb and signed
head9ecbbc4 share exact tree d82f512287f0a6f3287818666ce3439fae992ab6. Final
acceptance checkpoints are also in Issues5/45/51/52/53/54, all kept open.
