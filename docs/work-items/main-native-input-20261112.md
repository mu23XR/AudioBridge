# Main native input acceptance follow-up

## Current recovery status

The original input failure is diagnosed; actual native revalidation is pending.
GitHub's official incident dvy0t3mvh1lg reports full recovery/backlog clearance
at21:21UTC. An outage-created recovery run43096226396 still shows queued/zero
jobs; both normal and force cancel return409 (the latter says it has not been
queued yet). Its registry is active, so this is not a disabled workflow.

This documentation-only main update corrects stale PR75 status and invalidates
the obsolete source6668324 recovery script's exact-main-ee9 precondition. No job
had started before the lease changed. The old record/failures are preserved;
it must not be allowed to dispatch an extra test if it ever wakes up later.
A guarded temporary controller will dispatch the ORIGINAL android-native.yml
workflow once against this documentation-fenced main. All original12 methods,
producer/progress/loss/native bounds and permanent signing workflows stay.
Dependabot configuration stays removed; no release, app or device changes.

The stages below preserve the investigation history, not extra passed tests.

Status: diagnosing, owner-authorized under Issue51. Preserve all original tests,
permanent signing, producer/progress/loss/buffer limits and actual native audio.
Do not restore dependency automation or upgrade dependencies. No public product
release, installed-app change or physical-device operation is requested.

Worktree release-flow, branch fix/native-producer-acceptance-20261112, based on
main ee9c0af9d432287ad94f61a40670cf613d2cf26f (Dependabot config removed).
Prior local PR75 closeout7b747db stays on its original branch, not mixed here.

Original post-merge run43090517310/job130766031566, source36a3343, fails
ReceiverPlayoutDeviceTest.intentionalSilenceDoesNotRaiseAdaptiveTargetOrRebuildOutput:
Paced PCM producer404 packets, maxLag88.164ms, exceeding the unchanged80ms guard.
Earlier exact0979 two native suites passed12 tests/zero skipped. A prior green
run does not resolve this failed attempt. Retrieve its exact diagnostic archive
and correlate producer/phase/playback/host evidence before selecting a repair.

The exact original archive (Release442149942/asset736777219,1407866bytes,
SHA256216990589e6b7dc38e776080e87382b38480f7cae537a1f1fa1069f973fba2ca)
is verified/read by cloud43094491853. Direct failed assertion is the paced
producer guard:66 pending packets,88.164ms late,404 packets produced. The adaptive
before-silence phase aborts within its first second, not on the playback discard
assertion. Sampled software discard3584B remains below the original19200B bound,
native discard/recovery/rebuild0. This is not a completed passing phase. Original
host has ample memory/livePA115samples. Same-process GC reports46.294ms pause at
19:55:27.445Z, with system delays nearby. Runtime scheduling/GC interference is
consistent with the evidence; the exact scheduling event is not proven.

No product, fixture, workflow, input/progress/loss bound or signer change is
justified by this failed-input record. Perform one explicit bounded retry of
the original failed native run on a fresh cloud runner, retaining its failure.
Original36a tree equals accepted0979; currentmain ee9 only removes Dependabot.
Rerun does not push or restore that config. Keep all12 methods/zero skips,
actual audio, immediate resume and35s silence; no warmup/automatic retry loop.
If another failure occurs, inspect it instead of repeating for green.

The failed-jobs retry never reaches a runner: attempt2 ends startup_failure with
zero jobs/check runs, not a test result. The connector exposes no startup error
annotation or check-suite read; public page fetch also unavailable. Preserve this
separate infrastructure failure. Recover by requesting the exact original failed
native job130766031566 directly, after confirming attempt2 terminal/no jobs and
original job ownership/source. This is one alternative startup recovery, not an
additional executed native pass or an automatic retry loop. If startup still
fails, do not keep repeating the same request. No code/test/signer/config edits.

Acceptance: diagnose the failed input, make only evidence-supported changes,
add focused coverage if necessary, retain all tests/signing and exact-source
native verification. No automatic retries to obtain green. Any repeat must be
bounded, evidence-driven and retain the original failure. Validate final main,
record remaining real-device/OEM/overnight limits, and leave no temporary
diagnostic automation or background local work.
