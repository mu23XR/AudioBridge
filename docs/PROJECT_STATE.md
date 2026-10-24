# Project state

2026-10-24 PC publication task: docs/work-items/desktop-github-preview.md. Branchpublish/desktop-receive-preview starts at currentmain ea2dfe8 and imports the committed test21 PC continuation. This is a Draft integration, not a merge or stable promotion; related existing tracking#55 and Desktop PR#31 remain open.

The source of this PC candidate is the independent Desktop module tree35b82e9fb798a977f93b8d20fdddb6ab42ba1903, reference productc8c0cb6. Publication adds only portable CI/test helpers and project records around that module. Android must remain tree-identical to the chosen main baseline.

Preview1.3.1-test.21-vpn.2 repairs receiving buffer/clock/startup implementation and automatic update notification behavior, preserving the existing test21 PC features. Cold-start audio cropping and phone/tablet listening/long wireless acceptance remain open. Remote source, Draft PR, CI and assets are still being prepared; do not describe a local reference ZIP as a CI release.
