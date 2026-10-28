# Contributing to AudioBridge

AudioBridge is maintained as one Android + Desktop repository. Correctness and recoverability are more important than landing a change quickly.

## Before starting work

Read:

1. `AGENTS.md`
2. `docs/PROJECT_STATE.md`
3. open GitHub Issues, starting with the active tracking Issue
4. linked open/Draft pull requests
5. the relevant architecture/protocol/release documents

## Issues first

Reproducible bugs and active features should have a GitHub Issue before substantial implementation.

A bug Issue should contain:
- reproduction steps
- expected behavior
- actual behavior
- device/environment
- acceptance/regression test

Do not create duplicate Issues when a fix fails. Update the existing Issue with the new observation.

## Branches and pull requests

- `main` is the integration/release branch.
- Use short-lived branches for fixes/features/governance work.
- Do not commit directly to `main` for normal development.
- Keep one PR focused on one coherent change.
- Link the relevant Issue(s).
- Prefer squash merge for ordinary fix/feature PRs so `main` stays readable.

## Required validation

Before merge:
- Android CI must pass for Android/shared changes.
- Desktop CI must pass for Desktop/shared changes.
- Project Governance CI must pass.
- Runtime/audio bugs require the Issue's real-device acceptance test; CI alone is not proof of a fix.

## Sensitive changes

Never commit:
- keystores/private keys
- signing passwords
- tokens/cookies
- device secrets
- private logs containing credentials

Android release signing must follow `docs/SIGNING.md`.

A wire-incompatible WFAS change requires a protocol-version bump and coordinated Android/Desktop work.

## Documentation

Update the relevant documentation in the same PR when changing:
- package identity
- signing
- release flow
- protocol
- runtime lifecycle
- known blockers
- migration behavior
