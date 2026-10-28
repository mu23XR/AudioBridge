# Android endpoint repair commit — 2026-10-21

## Scope

The user authorized committing the pending Android repair, then continuing the
project-governance documentation. Android is isolated in `runtime-fix`, branch
`fix/android-multisource-recovery`, parent `18575c8`, existing Draft PR #40 and
Issue #61. Do not include Desktop or project-governance changes in this commit.

## Implementation and review

The receiver may learn one rewritten sender reply port from a recognized
handshake response at the expected address. Audio, keepalives, teardown and volume
messages cannot trigger learning. Handshake completion freezes the accepted
advertised/learned port set; a later rewrite requires reconnecting. The sender's
control-request endpoint validation and WFAS v2 framing are unchanged.

Pre-commit review fixed repeated learning during a multi-step handshake and added
a focused JVM regression. The native fixture now sends third-port audio before
any legitimate source frame and asserts that it neither connects nor consumes the
connection cue, avoiding an already-connected false positive. Protocol wording
distinguishes sender request validation from receiver reply-port learning.
The strict native acceptance manifest now includes the added transport method:
two connection-cue methods and 12 total, with exact completion and zero skips still
required. This does not relax any audio assertion or acceptance threshold.

## Validation checkpoint

Final validation passed:

```text
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
--offline --no-daemon -Pkotlin.compiler.execution.strategy=in-process --max-workers=2
```

It used existing Android SDK/Gradle caches and did not connect to a device. The
first sandboxed attempts did not run the checks: the external Gradle wrapper
cache lock was denied. The authorized retry log is under ignored
`out/endpoint-final-20261021-123831.log`: BUILD SUCCESSFUL in 6m47s, 158 JVM methods
in 26 suites with zero failures/errors/skips, lint zero errors (223 warnings,
20 hints), and both APK compilation tasks completed. The 22 Python checks pass
in `out/endpoint-python-20261021.log` after updating the native runner's fake
12-method output; its initial count mismatch was a failed run, not a pass.
Independent endpoint/fixture/manifest review found no remaining commit blocker.
Historical emulator results in the work item precede these review corrections;
no fresh native or physical-device acceptance is claimed.

## Version and continuation

No new delivery version is requested. The verified test21 APK remains bound to
`9ecbbc4`, `1.3.1-test.21`, code `610181030`, not this source patch. Build request
remains `publish:false`. A later distributable requires a new test version,
increasing versionCode and clean-source artifact verification. No installation,
Release, merge or runtime-Issue closure is included.

Keep the mirrored sender-side VPN endpoint concern separate. Current Desktop work
has progressed in the independent `desktop-keepalive` repository; the old Desktop
endpoint finding in the historical work item is not a fresh audit of that copy.
The overall project guide is maintained independently in `project-governance`.
