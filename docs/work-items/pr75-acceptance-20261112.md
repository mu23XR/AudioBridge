# PR75 acceptance repairs and main integration

Status: diagnosis in progress. Owner explicitly requests fixing both failed
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
