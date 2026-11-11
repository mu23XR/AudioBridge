# Direct Release delivery handoff

Issue78; worktree `release-flow`, task branch `fix/release-assets-direct`, base
published source4cbea4a. Current main remains ea2dfe8; PR75 is Draft/unmerged,
and v2.0.1/Latest plus historical Releases are immutable. Product code,
installations, signing identity, protocol and native thresholds are unchanged.

Four workflows replace all Actions artifact uploads/downloads with private
run/source-bound draft Release transfer. Stable and preview publishers still
require their original gates. Preview APK verification now also binds actual
package/version/code/public signer to platform records and final checksums.
All three product request files are false for this nonpublishing validation.

Successful verification removes staging. Failed runs retain retry inputs, eligible
for cleanup after one day on the next prepare/report invocation; diagnostics are
eligible after14days. Only completed owning runs and matching private drafts can
be collected. Errors are explicit; no asset clobber or public-release replacement.
Signing keys are rejected as transport payloads and are always removed after build.

Local preflight: existing40 script tests +21 new tests and existing9 Windows
publisher tests passed; YAML and official hash-verified actionlint1.7.12 passed.
The key-material rejection regression also passes. Next: commit only this scoped change,
push a dedicated branch/PR, exercise the
real small-file smoke and both build/draft-transfer paths with publish=false.
Keep all native acceptance tests and signer checks; do not bypass a failed gate
or publish a new product version as a workflow test.
