# Release Policy

## Stable releases

Stable tags use exactly:

`vMAJOR.MINOR.PATCH`

Example: `v2.0.0`.

The stable workflow rejects prerelease/test-like tags such as `v2.0.0-test.1`; test builds use the dedicated test/prerelease path instead.

Stable releases are generated only by `.github/workflows/release.yml`.

The workflow:
1. validates the tag
2. checks out the exact tag
3. restores the permanent Android signing key from GitHub Secrets
4. verifies the expected certificate fingerprint
5. runs Android unit tests
6. builds an Android release APK
7. verifies the APK signer
8. builds the Windows Desktop package
9. generates `SHA256SUMS.txt` for the release assets
10. publishes both assets and checksums to one GitHub Release

## Versioning

Application version names follow SemVer.

Android `versionCode` must monotonically increase across **all** test and stable workflows in the permanent package/signing line.

The release workflow uses a shared time-based scheme derived from UTC `(year - 2020) + MMDDHHMM`, rather than a workflow-local GitHub run number. New test workflows must use the same scheme or a guaranteed lower code followed by a higher stable code.

Do not reuse historical Stable #153/test.11 package/signing assumptions for the new permanent line.

## Test builds

New signed test artifacts use the `preview` build type (`assemblePreview`) and applicationId `io.github.mu23xr.audiobridge.test`, alongside the stable package. Local `debug` builds use `.debug`. Both are clearly named AudioBridge Test. test.16 and earlier permanent-package tests retain their historical identity; do not uninstall them to test the new independent app. Stable `release` builds and the permanent signing certificate remain unchanged. A test branch does not by itself change application identity; the build variant does.

Test builds are prerelease/development artifacts and must be clearly labeled. They must never silently become the latest stable release.

Where a signed test APK is intended to upgrade into a future stable build, it must use the permanent signing identity.

Tags matching `vMAJOR.MINOR.PATCH-test.N` trigger the reusable `android-test.yml`
workflow. Android signing/build, Windows checks/package and governance must all
pass before the tag is published as a GitHub prerelease (`latest=false`). Provide
release notes in `docs/releases/MAJOR.MINOR.PATCH-test.N.md` before tagging.
Assets include SHA-256 checksums, platform build records and the exact shared
source commit. Manual dispatch defaults to publishing a prerelease after all
checks; select `publish: false` for a nonpublishing validation build.

The preview workflow resolves one source, version and Android versionCode before
either platform builds. Its reusable Windows job receives an explicit
`stage_scope: preview` and returns the exact uploaded-file manifest. Reused
workflows inherit the caller's event, so delivery is not inferred from
`event_name=workflow_call`. Ordinary standalone Windows validation builds and
verifies its packages but retains no installation-package copy in Actions.

Before this workflow reaches `main`, an in-repository PR may request a signed
prerelease by changing `docs/releases/build-request.json` with a unique version
and `publish: true`. Only same-repository PRs can run the signing job. The exact
head SHA is used by all build/check jobs; publication creates the tag only after
all gates pass. Fork PRs cannot sign or publish. Existing versions are never
silently replaced. This explicit request file also allows nonpublishing validation builds
with `publish: false`.

## Rollback

### Local preview consistency

Local acceptance APKs are explicitly unpublished; they do not replace CI releases.
Use `scripts/preview_bundle.py --help` for the record-build, bundle and verify
commands. record-build requires a clean exact source SHA, a previously absent
APK output and a real successful build; it checks the tree again afterwards and
reads the actual APK package/version/public signer. Existing signing environment
variables remain private and are never saved in the receipt. bundle creates a
fresh directory atomically, including the APK, build receipt, SOURCE_COMMIT,
verification manifest and checksums. verify cross-checks every file and actual
APK identity. This is a local consistency record, not a CI attestation.

Do not relabel an existing binary with the current HEAD. If its provenance is
unknown, preserve it as historical/unverified and rebuild from an isolated clean
source. In particular the old out/verified-test21-release directory has a newer
APK alongside older verification/source metadata; its UNVERIFIED.txt invalidates
use as a verified candidate. Its original files stay preserved. Pending unrelated
working-tree edits must not be committed merely to make a build appear clean.

Do not delete previous stable Releases merely because a new release is bad. Publish a corrected higher version instead, or clearly mark the affected release as withdrawn.

## Release checklist

- Android CI green
- Desktop CI green
- governance CI green
- signer fingerprint verified
- package id verified
- version metadata verified
- WFAS protocol version compatible with both clients
- release notes describe breaking/migration behavior

## 2.0.1 exact-source release request

Stable publication may be requested by an explicitly authorized same-repository PR
with `docs/releases/stable-build-request.json` containing a unique semantic
version and `publish: true`. The root release workflow checks out the exact PR
head, runs Android unit/lint/native, Windows and governance gates, verifies the
release identities and one shared source SHA, and only then creates the release.
Forks cannot publish. Existing releases/tags are not replaced. Uploaded assets are
downloaded and checksum-verified before the draft becomes stable/latest.

The stable Windows build injects `WFAS_VERSION_NAME`; the preview default and its
independent publication request remain preview-only. Generate the versioned manual
in a separate Gradle invocation before resource packaging. Stable Android uses
`assembleRelease`, the permanent certificate and the monotonic UTC versionCode.
Both native and software regression checks are required, but are not a substitute
for real-device or overnight listening. See `releases/2.0.1.md` for migration.

## Release storage and temporary transfer

Installation packages are delivered through GitHub Releases, not Actions
artifacts. Android previews publish as prereleases with `latest=false`; stable
versions are promoted to stable/Latest only after their original gates pass.
Windows-only previews keep their explicit request and preview identity.

Separate build jobs transfer files through a draft `ci-stage-<scope>-<run-id>`
Release. The draft is bound to this repository, exact source and owning workflow
run; it is never a public product version. Each producer returns an immutable,
attempt-qualified filename/size/SHA-256 manifest. Consumers use that exact
manifest, verify downloaded bytes and then run the existing package/provenance
checks. Files are never clobbered, and published Releases cannot be staging.

Successful validation/publication removes its transfer draft and any owned tag.
Failed/cancelled runs retain their draft for failed-job retries; drafts older than
one day become eligible for cleanup on the next staging/report operation, only
after the owner run has ended. A retry after that cleanup must rerun all jobs.
Native diagnostic ZIPs use a separate run-bound draft with a 14-day cleanup
eligibility window. Fork checks retain workflow logs without Release writes.
The strict native test commands, test counts and timing/loss limits are unchanged.
No installation package or diagnostic ZIP is uploaded with `upload-artifact`.

`release_stage.py smoke` exercises a small real upload/download/checksum/cleanup
before stable builds. Unit regressions additionally reject wrong runs/sources,
changed or published drafts, corrupt downloads, unsafe names and output
collisions. Storage and cleanup errors fail explicitly; reducing storage must
never skip tests, signing, source identity or final download verification.
