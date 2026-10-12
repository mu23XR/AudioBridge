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

No phone/tablet Android replacements, signer changes, stable publication, PR #18 merge, clean-room implementation or unrelated handoff edits. A later user-authorized MuMu-only preview install is recorded below.

## Pause checkpoint — 2026-10-12

The user explicitly requested another pause after confirming the new program's Chinese text is normal. Stop implementation, packaging and external updates until the user resumes. No build is running; the accepted GUI is left open for use.

Completed: parity/font code and initial evidence committed locally as 8b5b37e, following startup/build commit 982c146. A scoped review branch build/windows-test17-parity was generated from origin/fix/test17-adaptive-notifications without changing the current checkout or including local clean-room preparation. Its Desktop tree 5b579a853cb0817dc1295a497bc7e1c9168824be matches the tested local source. Review commit 048fa8b24d5a0a621c981699eb160ed9ceb28247 is pushed and attached as Draft PR #31: https://github.com/mu23XR/AudioBridge/pull/31. No merge was performed; fresh CI status has not been inspected.

Delivered: D:/project/app/AudioBridge/desktop-test17/AudioBridge-Test/AudioBridge-Test.exe and AudioBridge-1.3.1-test.17-windows-x86_64.zip, 147132204 bytes / SHA-256 4C901F983DDFE161A6EE688455F53A09AFA69122EBF66E90C14D990647443541. The archive's 256 entries include the runtime, Chinese instructions and BUILD-INFO; main JAR SHA-256 BE77DC9E8FEA18170C5B660D7FD38145064A2D8BF24E4D1BE12F32EB0E33E981 matches the accepted running window. No software-renderer override is archived. The older parent 使用说明.txt was corrected to the current test entry/features.

Uncommitted at pause: this checkpoint and the work-item/current-state follow-up; the original user-owned two handoff files remain untouched and excluded. GitHub #30 still has the pre-final-acceptance progress text; #20 also has an old pause/progress body. External Issue updates were not performed after the new stop request.

Resume next: reconcile #20/#21–#30 with the already recorded tests and PR #31, closing only accepted focused criteria; commit/sync the remaining documentation to both local and review branches without unrelated files; inspect CI once; continue the explicitly pending real-device/long-run/manual checks only within resumed scope. Do not rebuild or reopen the accepted GUI merely to repeat passed font checks.

## Resumed — 2026-10-12

The user explicitly resumed the task. Current branch remains build/test17-desktop @8b5b37e, with only the three task-owned follow-up documents plus the original two user-owned handoff changes. PR #31 remains Draft, mergeable and unmerged at 048fa8b. Its associated Windows run38157915549, Android run38157915493 and governance run38157915508 are completed/success. The legacy commit-status wrapper returned an empty list; the workflow-run API established the actual CI result. Reconcile issue acceptance and finish scoped documentation delivery; no product rebuild or restart is needed for the accepted font fix.

The resumed evidence check confirms ZIP147132204 bytes / SHA4C901F98...443541 and main JAR SHA BE77DC9E...E981 still match the recorded accepted output. CI job detail confirms Desktop checks/package, Android tests/lint/debug compile, and governance succeeded; the ordinary PR Windows job skipped artifact upload by existing workflow design. The local ZIP has not been published to Releases.

Issues #20/#21–#30 are now reconciled and linked to PR #31. #21/#22/#23/#30 are closed based on their completed native load/capture, version, selector/startup and user appearance/archive acceptance. #24–#29 and parent #20 remain open for pending GUI persistence/tray/role/volume/coexistence, Android interoperability, multicast, hardware faults and longevity. Do not conflate these closures with Android runtime fixes.

## MuMu interoperability acceptance — 2026-10-12

The user chose an emulator peer instead of a physical phone. Requested index3 is absent; the user explicitly confirmed the only Android15 instance, MuMuManager index1. Initial direct/manager launches failed; event1000 showed main exiting with0x40000015. Launch through a workspace shortcut using Windows Explorer succeeded. Do not claim a deeper host/CEF cause from that workaround alone. Emulator ADB127.0.0.1:16416 / SDK35, existing preview .test /1.3.1-test.17 /code610040557 installed successfully; original APK SHA E10986B3B942243C0B14EA8EAE2FF27096B8779CD82778FB61046F0B5CF77ECA and permanent signer were checked without reading or changing private signing material.

The delivered Windows GUI was not running at resume; opened it with --gui --mode server --port19090 --persist --debug and a separate out/test17-desktop/emulator-20261012/pc-send-config.json. Its CLI entry/Java helper needs compose.application.resources.dir for the packaged selector bootstrap; direct raw-classpath helpers without that property are not launcher acceptance. Initial planned config preparation stopped on a uiautomator export error; the GUI created a valid default test config itself. It selects system Chinese and native WASAPI; runtime port19090 overrides saved default9090. No user's earlier preview-config.json was changed.

Android onboarding/notification permission and receive role were set via observed UI XML and cmd input; guest host10.0.2.2 is reachable. Main audio port was set19090 in Android settings; the manual IP field normalizes away a supplied port, so use bare IP with the configured port. The manual field is recreated after explicit stop, while the persistent receiver target is restored on app/VM startup. MuMu input/uiautomator utilities can exit139 after producing XML; unique output paths, successful pull and XML validation are recorded, rather than treating exit status as successful without evidence. No Android source was modified.

Initial stream (17:02:50–17:09:27 CST): 78,901 packets, reported loss0%, jitter about7.2ms/peak7.93ms, mean interval5ms. Actual48k stereo PCM16 track57 advanced. During the low-level nine-second tone/silence/tone fixture, positions14400480/14541792/14686176/14832096 at1/4/7/10s advanced431616 frames, with underruns18976 unchanged in those samples. This is transport/playback progress, not independent acoustic/bit-perfect or physical Wi-Fi delay acceptance. Stop released ClientService201 and the old audio track.

Reconnect failed: receiver foreground/UI returned but track60 was inactive with server position0 and480 preroll frames. A test-app process reset restored its remembered target yet replacement track63 also had no progress. A safely stopped/reopened Windows sender and a subsequent reset of both applications did not recover the receiver. Restarting only the selected VM retained the install/data but again produced track56 inactive/position0 across two samples. Java debuggerd trace was unavailable without root; no root setting was enabled. Do not assume the missing packet metrics prove a specific blocking AudioTrack.play call. Issue #34 records the unconfirmed Android/startup/handshake/NAT boundary.

Separate new-PC checks: MODE_PROBE returned UNICAST; two consecutive local WFAS v2 test peers received HELLO_ACK and283 packets/272108 PCM bytes, then293 packets/281708 PCM bytes, each ending with CLIENT_BYE. Only packet counts were saved, not audio contents. This supports the Windows sender core/reconnect, not the complete emulator route. #20/#25/#34 remain open. Real-device interoperability, reverse Android send (Shizuku unavailable here), long run and full manual controls remain unaccepted.

Current test state: MuMu1 Android15 remains open, its test17 receiver preserves the failing startup scene; one current Windows GUI sender uses19090 and20% application send gain. Simulator system media-volume requests report fixed100/100; requested100 was restored, and actual gain control is through the Desktop app. Same-host sound feedback is the reason for the reduced application gain. Do not label this state as successfully playing. Earlier task-owned GUI launcher/JVM pair exited during controlled replacement; identity guards prevented acting on a dying/unverifiable PID, and a fresh zero-process check preceded the single replacement. Fresh launch record is out/test17-desktop/emulator-20261012/pc-restarted-launch.json. No builds/fixture playback continue.

Parallel workspace activity added docs/handoffs/2026-10-12-android-runtime-diagnosis.md, docs/work-items/android-test17-night-disconnect.md and a night-diagnosis paragraph in PROJECT_STATE. Preserve these and the original user-owned two handoffs; stage only this task's Windows state section and own two documents. Product source/tree and the accepted archive remain unchanged. Next meaningful step for #34 is startup/socket boundary telemetry in an isolated diagnostic Android build, then repeated reconnect and real peers; do not disable recovery checks, claim root cause from an inactive track alone or change the emulator root setting merely to force tracing.
