# test.17 Windows desktop delivery

Started 2026-10-11; acceptance continued on 2026-10-12. Branch: `build/test17-desktop`. Tracking: #20; focused work #21–#30. The user requested a usable Windows edition based on the existing test.17 implementation and then required Chinese and Android test.17 feature parity. Independent rewriting is deferred.

## Baseline and ownership

Product baseline: test.17 @86bab43, equivalent to 2b69037 and PR #18 @68779ab except documentation/CI. Test.16 @60137ef → test.17 changes affect 26 Android files (+667/-840); Desktop product sources were unchanged. The parity matrix and exact commit references are in `docs/work-items/test17-desktop-parity.md`.

Preserve user-owned changes in `docs/handoffs/2026-10-04-test17-implementation.md` and untracked `docs/handoffs/2026-10-04-clean-room-plan.md`. Do not stage them with this task.

## Implementation

Build/startup phase committed as 982c146: correct CLI display version (#22), instantiate Windows device-property GUIDs for mingw-w64 12 (#21), and bootstrap the Windows selector temporary path (#23). The C: TEMP selector error occurred with both Java 17 and installed Oracle 21; a valid D: path worked. Do not attribute it solely to JDK version or the 8.3 alias.

Parity phase adds UTF-8 Chinese and saved language choice, current test.17 changelog, adaptive/manual 20–80/20–400 ms receiver settings, legacy manual migration, half-target preroll, continuous healthy/shortage timing, partial-write handling, bounded JavaSound rebuild/reconnect and a separate stalled-worker watchdog. Single/multicast share the player and health policy; retries retain the target and suppress duplicate connection sounds.

Unified Windows tray and window actions share stop/start ownership, session-generation guards and a command mutex. Existing receive-to-send cleanup and receiver gain bugs are tracked separately as #28/#29. Preview identity isolates settings, credential storage, IPC files, autostart and release selection; it skips stable PATH and URL registration.

The user observed Chinese and Adaptive, reported blurry/crowded Chinese glyphs, and supplied cropped and full Settings screenshots. #30 tracks this acceptance regression. Named YaHei UI, SOFTWARE_COMPAT rendering, and physical YaHei regular/bold files all failed visual acceptance. Actual JAR/renderer, shared Settings theme and Compose FontCache normal/bold CJK glyphs were verified; neither missing fonts nor a GPU-only cause is proven. The actual product ExposedDropdown was rendered offscreen at 100/125/150% scale. At 150%, full hinting matched default pixels and subpixel antialiasing changed little. The user compared equal-size/equal-scale YaHei/DengXian previews and explicitly selected DengXian as clearer. The final bundle uses physical DengXian regular/bold consistently; actual Compose faces 400/700 and CJK glyphs pass. The new window runs main JAR AudioBridge-7.0-9cb4817a695df50d1c853ea24fb264.jar, normal AWT threads and empty stderr. The user confirmed the full program matches the selected preview and is now normal. Optional absent CJK fonts warn then use system sans-serif, while read errors are not swallowed. Windows font files are read in place, not redistributed or changed. The failed renderer override is removed. The missed Lightness label is translated.

## Verification and artifact

Final complete `check createReleaseDistributable packagePortableArchives` passed in 53s. It includes existing protocol/security/control/help checks plus new 751-key/formatter coverage, all 381 manual integers, channel/config migration and injected playback failure/lifecycle checks. No Gradle/JVM dependency versions were changed.

Packaged runtime evidence:
- CLI displays 1.3.1-test.17; Windows GUI has actual AWT-Windows / AWT-EventQueue / D3D threads and no startup exception.
- WFAS v2 fixture → actual JavaSound output → WASAPI loopback: manual 41 ms and adaptive target 20 ms both delivered 384,000 PCM samples and resumed nonzero rendered output after three seconds of explicit silence.
- WASAPI capture → actual WFAS UDP sender: 604 frames / 579,840 PCM bytes / 282,948 nonzero samples, with local render unmuted; normal stop completed.
- Actual font/glyph checks and user full-window Chinese appearance acceptance passed. No end-to-end latency measurement, Android interoperability, overnight session or real hardware fault acceptance is claimed.

Latest delivered directory: `D:/project/app/AudioBridge/desktop-test17/AudioBridge-Test`. It is self-contained Java 17 with native DLL embedded in its main JAR. The first English prototype remains an older build, not the current entry point. The pre-font-fix GUI bundle is retained under ignored `out/test17-desktop/gui-before-font-fix`.

On user request, the active preview was stopped through its own IPC before its verified launcher/JVM processes were terminated for replacement. The final GUI was opened once with `--gui --config out/test17-desktop/smoke/preview-config.json`; user settings in that test config were preserved. Launching with only `--config` had earlier entered CLI mode; those task-owned processes were stopped before the GUI acceptance. Do not confuse jpackage's launcher and JVM child with two app windows. Do not blindly kill recorded/reused PIDs.

## Local build reproduction

Ignored `out/test17-desktop/build-source` contains a tracked baseline export plus the exact modified Desktop sources. `out/test17-desktop/build-local.ps1` runs the complete checks/package tasks with `WFAS_VERSION_NAME=1.3.1-test.17` and process-local environment settings.

Toolchain: Temurin 17.0.20.1+1, CMake 3.31.6, WinLibs GCC 14.2 / mingw-w64 UCRT 12 r3, Gradle 8.14. Downloads were checked against upstream hashes. Installed Oracle 21 remains untouched; Java 17 is selected because the project/CI explicitly use toolchain 17. System JAVA_HOME/PATH were not modified.

Build logs/caches/helpers are ignored output. Existing proxy endpoint is used with verified TLS; failed Maven downloads were preloaded only after upstream hash checks. Use PowerShell 7/default shell to run the build script: Windows PowerShell 5.1 misclassifies ordinary JAVA_TOOL_OPTIONS stderr as an error. Native DLL SHA-256: FEC353CD17E96FD8930970E68802F03B735801B20EA72323432F9DD577AC6855; it imports Windows system libraries, not external MinGW runtime DLLs.

## Remaining acceptance and boundaries

Confirm manual GUI input and persistence, connected role changes/tray actions/volume, hardware output replacement, genuine multicast, Android ↔ Windows and a long continuous session. Issues stay open for these criteria. Chinese font appearance has user acceptance. Desktop target milliseconds describe the receiving buffer, not measured total delay. Preview and stable may coexist, but need different listening ports if both send simultaneously.

No Android installs, signer changes, stable publication, PR #18 merge, clean-room implementation or unrelated handoff edits. The earlier explicit pause was resumed by the user; it is no longer a blocker. The first build phase is committed and the parity phase is ready for a scoped code/documentation commit and a Draft PR against the test.17 branch.
