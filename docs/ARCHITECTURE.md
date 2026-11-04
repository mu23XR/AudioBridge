# AudioBridge Monorepo Architecture

```text
AudioBridge/
├── android/             Android application
├── desktop/             Desktop application
├── docs/                project governance and architecture
├── WFAS_PROTOCOL.md     single shared protocol specification
├── AGENTS.md            contributor/AI entrypoint
└── .github/workflows/   CI and release workflows
```

## Architecture rules

1. The repository root is the coordination layer for Android and Desktop.
2. `WFAS_PROTOCOL.md` at the root is the single protocol source of truth.
3. A wire-incompatible WFAS change must be implemented on both Android and Desktop before release.
4. Platform-only implementation details stay inside their platform directory.
5. Windows 10/11 x64 is the primary Desktop validation target for the current development phase.
6. Android and Desktop may have different UI/platform details while sharing the same WFAS protocol version.
7. Android application identity is `io.github.mu23xr.audiobridge`.
8. Release signing and publishing rules are defined in `docs/SIGNING.md` and `docs/RELEASE.md`.
