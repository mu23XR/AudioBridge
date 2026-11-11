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
all original lifecycle assertions stay. Local focused check in progress.
User forbids further desktop/browser control; use CLI/GitHub/cloud only.
