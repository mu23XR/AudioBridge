# Branching Policy

## Permanent branches

- `main` — the only long-lived integration/release branch.

## Working branches

Use short-lived branches for fixes, features, release hardening and governance work. Open a pull request into `main`, link the relevant Issue, pass CI and complete the Issue's acceptance test before merge.

## Historical branches

The following branches are historical and must not be used for new development:

- `master`
- `zh-v1.2`
- `audio-bridge-lab`

`master` and `zh-v1.2` are fully behind `main` and contain no commits ahead of it.

`audio-bridge-lab` contains historical Stable #153/test signing/build commits that are intentionally not part of the permanent release line. Keep it only while historical signing recovery/audit value is still needed; otherwise archive its final commit/tag and delete the branch.

## Merge policy

Preferred merge method for ordinary changes: **Squash merge**.

Do not merge a runtime bug solely because CI is green. The linked Issue's real-device regression test must pass.

Delete merged working branches after merge.

## Direct pushes

Normal development must not push directly to `main`. Repository rules should require pull requests and the Android, Desktop and Project Governance status checks before merge.
