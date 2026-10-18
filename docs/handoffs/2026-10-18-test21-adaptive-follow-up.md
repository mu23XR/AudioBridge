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
  admission bounds are retained. It remains under native validation; system mixer
  latency may differ, and emulator queue counters are not acoustic measurements.

## Evidence and acceptance

Local140 JVM methods/25suites pass (130 committed plus10 pending VPN methods),
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

Inspect standard native CI result and retained system/journal evidence before
accepting the output candidate. Preserve all MuMu failures even if another
emulator passes. Keep Issues5/14/51/53 open, no Release/stable promotion/merge or
physical install. The old local test21 bundle remains explicitly unverified;
clean-source receipts, not refreshed metadata, establish new artifact provenance.

Current detailed work item: docs/work-items/android-test21-review-repair.md.
