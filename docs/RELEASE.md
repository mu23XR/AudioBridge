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
Assets include SHA-256 checksums and the exact Android source commit. Manual
workflow dispatch remains available for artifact-only test builds.

Before this workflow reaches `main`, an in-repository PR may request a signed
prerelease by changing `docs/releases/build-request.json` with a unique version
and `publish: true`. Only same-repository PRs can run the signing job. The exact
head SHA is used by all build/check jobs; publication creates the tag only after
all gates pass. Fork PRs cannot sign or publish. Existing versions are never
silently replaced. This explicit request file also allows artifact-only builds
with `publish: false`.

## Rollback

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
