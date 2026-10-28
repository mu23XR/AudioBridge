# Security Policy

AudioBridge includes networking, audio capture, Shizuku-based privileged execution, authentication and optional encryption. Security-sensitive reports should not include exploit details, credentials, keys or private device data in a public Issue.

## Reporting a vulnerability

If GitHub shows a private **Report a vulnerability** option for this repository, use it.

If a private reporting option is unavailable, open a minimal public Issue titled **Security contact request** with no exploit steps, secrets, proof-of-concept payloads or sensitive logs. The maintainer can then establish an appropriate private channel before technical details are shared.

For ordinary non-sensitive bugs, use the Bug report template instead.

## Scope

Security-relevant areas include:
- Shizuku/UserService privilege boundaries
- authentication / pairing / encryption
- remote command/control surfaces
- update/download behavior
- secret storage
- signing/release integrity
- network packet validation

## Secrets

Never attach:
- Android signing keystores or passwords
- API tokens
- private pairing/authentication keys
- cookies
- logs containing credentials

## Supported versions

Until the next permanent stable line is released, security fixes target the current development line and the latest stable release when a backport is practical.
