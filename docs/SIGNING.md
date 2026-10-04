# Android Signing Policy

## Permanent identity

All future signed Android test and stable releases use one permanent signing certificate.

SHA-256:

`4D:DF:26:7D:25:A7:3F:0A:28:44:6C:9E:77:29:32:04:E3:2F:67:77:C6:34:17:FE:B7:BD:92:50:40:FE:FB:63`

This fingerprint is public metadata. The keystore bytes and passwords are private and must never be committed.

## GitHub Actions secrets

The release workflow expects:

- `SIGNING_KEY_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

The private keystore must also be kept by the owner in an offline/encrypted backup.

## Historical signer

Stable #153 and test.11 were signed by a different historical certificate:

`4C:C4:F5:04:BA:95:3B:31:FF:AB:B2:55:5E:30:99:DA:31:D9:42:59:CF:58:25:9B:9E:6B:6F:AE:19:78:27:92`

That signer is not the future release baseline.

## Parallel testing identities (2026-10-04)

- Stable/release: `io.github.mu23xr.audiobridge`, permanent signer above.
- Signed prerelease/preview: `io.github.mu23xr.audiobridge.test`, same permanent signer. `assemblePreview` keeps signing verification; test installation does not replace the stable package or its data.
- Local debug: `io.github.mu23xr.audiobridge.debug`, standard development signer, clearly labeled AudioBridge Test. This is a local development artifact, not a signed published prerelease. It can be removed independently when migrating to the CI-signed test app.

Changing only a signer does not allow two apps with the same applicationId to coexist. The separate applicationId is the isolation boundary; no permanent key is replaced. test.16 remains in the stable package identity and will upgrade to a future stable build with the same signer and higher versionCode. Settings, permissions and Shizuku authorization are separate per test identity; do not run competing sender captures simultaneously.

## Hard rules

- Never auto-generate a release key when a secret is missing.
- Missing signing secrets must fail the release.
- CI verifies the permanent certificate before building.
- CI verifies the produced APK certificate after building.
- Never upload a raw JKS/keystore to Releases or commit history.
- A signing change is a migration event and must be documented explicitly.

## Verified local artifacts (2026-10-04)

Owner-supplied backup verified later on 2026-10-04: workspace-root `wfas-signing-backup.zip` contains `wfas-release.jks`, `github-signing-secrets.txt` and README.txt; root `github-signing-secrets.txt` contains the same keystore bytes as the ZIP. Using the provided store/key credentials without logging their values, keytool verified alias `wfas-release`, PrivateKeyEntry and the permanent 4DDF certificate above. A certificate-request signing operation also passed, confirming the private key is usable. These two files are one identity in two backup/config formats, not two different signers. No original files or GitHub secrets were changed; a temporary verification keystore was removed and process credential variables cleared. The owner now has a verified local backup; encrypted archival/password separation remains to be arranged. Never commit either root file or its contents.

The current device test.16 APK was pulled and verified against the permanent certificate above. This computer's current local debug APK has certificate SHA-256 `97018146BFC9A30C8BD7117EC9CFCB3284537D9DD43B75EBF00AD6C67306AF83` (Android Debug); this is not a permanent or universal debug identity. The new CI preview is configured for the permanent signer but its signed output has not yet been produced/verified. Historical #153's fingerprint above is a saved project record, not a fresh historical-APK inspection in this turn. See `docs/handoffs/2026-10-04-signing-and-channel-plan.md` for the pending two-channel rollout plan.
