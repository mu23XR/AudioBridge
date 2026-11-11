# PR75 repair handoff

Owner authorizes diagnosing/fixing the two current Android acceptance failures
and merging PR75 into main after all verification passes. See
work-items/pr75-acceptance-20261112.md. Worktree release-flow, task branch
fix/pr75-acceptance-20261112, base a31b308; prior local docs90f69e0 preserved.
Issue80 is the worker assertion; native scope remains Issue51. No test/signing
gate may be skipped or loosened; publication flagsfalse, existingv2.0.1 immutable.

Current investigation: get full original native failure diagnostics and reproduce
worker thread-name comparison under coroutine debug. Do not treat a previously
passing same-source run as resolving the newer failures. Other checkouts and
existing installations are untouched.

Original native assertion retrieved with verified archive digest/ownership via
cloud-only diagnostic43074638341: adaptive before-silence discarded21060bytes,
limit19200. Need correlate original journal; do not blame producer/host yet.
Worker debug-name race reproduced at iteration64 on unchanged code. Fixed test
will inspect real Thread ownership while active and factory name after shutdown;
all original lifecycle assertions stay. Local focused check passed1000+13.
User forbids further desktop/browser control; use CLI/GitHub/cloud only.

Worker focused1000+13 pass. Native original journal: producer77.975ms valid,
software21060B includes5560B premature soft trims; no native discard/recovery.
Real56ms-burst regression fails old policy. New continuous250ms excess criterion
keeps50→40ms stale cut, cooldown, hard60ms and all native/producer bounds. Original
test methods/assertions retained with sustained-overage setup; two new cases
cover recoverable bursts and age reset/continuation. Focused22 passed before a
small continuation-history refinement; the final focused22 also passed. Full CI next. Temporary diagnostic
workflow removed. No priority/target/protocol/signing changes.

Native result parser now includes a bounded assertion first line in failed-job
logs without changing any pass/skip/count decision. All62 script regressions,
9 original Windows publisher tests, actionlint and diff checks pass. Then
update release/2.0.1 with the exact repaired source to validate through PR75;
mark ready and merge main only after every required check passes.
