# PR75 acceptance repairs and main integration

Status: additional native scheduling repair under validation; main integration pending. Owner explicitly requests fixing both failed
checks, retaining all existing tests and signer verification, then marking PR75
ready and merging into main only after complete validation. Tracks #80 (worker
test), #51 (native silence/playout) and PR75. No new product release or install
is requested; v2.0.1 and its assets remain immutable.

## Baseline and isolation

Reuse release-flow, clean branch fix/pr75-acceptance-20261112, based on remote
release/2.0.1@a31b308025634db38385c02f23a5b5336f215f06. The prior local final
docs90f69e0 remain on fix/release-assets-direct and are not mixed into this repair.
Other worktrees, source dirty work, installed programs, keys and tools stay.
Current PR75 is Draft/open, head a31, base main@ea2dfe8; all3 publication flagsfalse.

## Failures and investigation

- Root43069367277/job130693037999:173 JVM tests/1 ComparisonFailure in
  ReceiverPlaybackWorkerTest.suspendingPlaybackKeepsOneOwnedThread. Reproduce
  debug thread-name decoration and confirm actual thread identity/shutdown.
- Independent native43069367138/job130692777440:
  ReceiverPlayoutDeviceTest.intentionalSilenceDoesNotRaiseAdaptiveTargetOrRebuildOutput,
  status-2. Retrieve the exact original assertion and journal before attributing
  this to code, producer scheduling or the host. Root native in that round passed.

## Acceptance

Keep test coverage, actual native audio, all progress/loss/producer/latency bounds,
permanent signing, package/source/checksum and download verification. Add focused
regressions for confirmed causes, then run complete Android/software/native,
Windows, governance, signed stable/preview nonpublishing rehearsals and workflow
tests. Preserve failure evidence; never silently retry to obtain green. All
package transfer stays in Releases, zero Actions installer artifacts. Before
merging, re-read PR head/base/checks and use an expected-head merge. Afterwards
verify main and release/tag/assets; do not republish2.0.1 or install candidates.

Record diagnosis, implementation, local and cloud results here by milestone.

## Diagnosis evidence

The original native report was read by a temporary cloud GET-only diagnostic
job, after verifying its repository/run/source ownership and SHA2567efcd51c...
(run43074638341). No rerun was substituted for the failure. It is actual
adaptive **before-silence** discard21060bytes (>19200bytes/100ms), not a failed
post-silence assertion. Producer/queue/timing journal correlation is next.
The initial diagnostic job403 required push-level draft visibility; it changed
no Release state. The temporary workflow will be removed before main integration.

Worker failure is reproduced on unchanged sources with assertions and coroutine
debug enabled: iteration64 reports expected test-playout-thread, actual
test-playout-thread @coroutine#194. Actual Thread identity still passes.
The test now keeps the coroutine active during identity inspection, retains all
suspension/ownership/shutdown checks, and checks the undecorated factory name
after the thread terminates. Production worker behavior is unchanged.
Evidence stays in ignored out/pr75-acceptance-20261112. No desktop/browser UI
will be used further; the user's machine-control preference is explicit.

## Scoped repair and local regressions

Worker fix passes1000 debug-enabled ownership iterations and the original13
lifecycle methods. Both original Thread identity and termination guarantees stay.
The earlier read-only UI attempt was stopped; no further desktop/browser input.

Original native phase/journal correlation (cloud43075982962) confirms valid
producer maxlag77.975ms<80ms; no native discard/rebuild/recovery. Measured software
loss21060B includes two soft cuts3004+2556=5560B after48–49ms worker gaps. Neighboring
samples show these high queues recover within250ms. The policy treated the first
full-buffer burst as stale immediately, adding avoidable loss to hard-cap drops.
New realQueue→pump→policy regression fails that old path: recoverable56ms burst
is cut before its native headroom opens. Repair requires continuous250ms excess
before the same50→40ms soft cut. Writable continuation still skips a cut without
consuming eligibility; recovery/config reset its age. All original test methods
and stale-cut/tail/cooldown/hard-cap assertions remain, with sustained-overage
setup for the changed soft-cut timing. Hard60ms capacity, all buffer/native/loss/
producer limits, targets, thread priority and protocol are unchanged. Focused
buffer suite22 methods passes. Strict cloud native acceptance is still required.
Temporary diagnostic workflow is removed from the final tree.

Native runner errors now include the original assertion's bounded first line,
while retaining full stacks in diagnostics and unchanged strict result parsing.
This prevents future queue-loss and producer-contract failures being hidden
behind the same status-2 message. Original parser tests remain; a new regression
checks that a failed assertion still fails even with a success summary.
Final local preflight passes:62 script tests,9 original Windows publisher tests,
actionlint, clean diff,1000 worker ownership repeats plus13 original worker
methods and22 buffer methods. Native fixture code and thresholds, Desktop
product tree, all signing/transport workflows and original stable publisher
are unchanged froma31. New full Android total is expected175 methods; cloud
results, actual APK signer and both native runs remain to be checked.

## First repaired-source cloud results and remaining native cause

Sourceb6fd39a passes Android175-method build/lint, Windows, governance, signed
preview transfer/bundle/cleanup and root signed Android/Windows gates. Independent
native attempt1 rejects invalid86.533ms input; its one bounded attempt2 passes
all12 methods/0skips. Root SDK download stalls16minutes (10→20% progress), is
explicitly cancelled/restarted on a fresh runner with successful outputs retained.
Both environmental failures remain recorded and never count as native passes.

Root native attempt2 then finds REAL before-silence discard40440B. Its original
verified report (cloud inspection43084177504) shows valid64.254ms producer lag,
110ms playback loop gaps, no native discard/rebuild/recovery, no soft trims and
ample host memory. Thus sustained-backlog repair is insufficient by itself:
the normal-priority owner can still miss the60ms FIFO deadline while input runs.
No main merge or retry-for-green is performed on this actual failure.

The receiver now sets Android's standard AUDIO Linux priority on its actual
owned thread before creating AudioTrack, records its actual Linux TID/priority, and
lets setup failures follow the existing explicit playback-failure cleanup.
It does not elevate shared IO, input, cleanup or UI threads. Original12-method
native fixtures independently query that TID's actual Linux priority and assert AUDIO;
all original progress/loss/producer/buffer assertions remain. SDK setup has an
explicit10minute failure bound instead of consuming the entire35minute job.
No target/capacity/acceptance bound or signing change. Fresh full CI is required.

Local preflight for this additional repair passes the changed playback Kotlin
source compile against the cached Android API/dependencies, actionlint and
git diff checks. This is not full Android/native acceptance; the new source
must still compile the modified instrumentation fixture and pass every cloud gate.
