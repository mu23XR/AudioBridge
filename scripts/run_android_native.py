#!/usr/bin/env python3
"""Strict debug-only acceptance on a newly owned official SDK emulator in CI.

Uses only Python's standard library. Never selects a local phone or an existing
emulator, and never restarts the shared ADB server. Logs survive failed tests.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import time


ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "io.github.mu23xr.audiobridge.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
AVD = "AudioBridge-native"
SERIAL = "emulator-5554"
PREFIX = "com.cuscus.wifiaudiostreaming."
# A count change needs an explicit review; assumptions/skips never count as a pass.
# The local uncommitted endpoint-rewrite fixture is not part of this CI contract.
CLASSES = {
    PREFIX + "ReceiverPlayoutDeviceTest": 5,
    PREFIX + "ConnectionCueDeviceTest": 1,
    PREFIX + "MultiSourceDeviceTest": 1,
    PREFIX + "RemoteVolumeDeviceTest": 1,
    PREFIX + "ReceiverRetryDeviceTest": 1,
    PREFIX + "BackgroundIntentDeviceTest": 1,
    PREFIX + "DiagnosticShellExportDeviceTest": 1,
}
EXPECTED_TESTS = sum(CLASSES.values())


def validate_instrumentation(output: str) -> dict[str, int]:
    """Require all expected starts and successful terminal events, not just OK text."""
    bundle: dict[str, str] = {}
    starts: set[tuple[str, str]] = set()
    passed: set[tuple[str, str]] = set()
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.partition(":")[2].strip())
            test = (bundle.get("class", ""), bundle.get("test", ""))
            if code not in (0, 1):
                raise RuntimeError(f"Instrumentation failed/skipped a test: {test}, status={code}")
            if test[0] not in CLASSES or not test[1]:
                raise RuntimeError(f"Unexpected instrumentation test: {test}")
            if bundle.get("numtests") != str(EXPECTED_TESTS):
                raise RuntimeError(f"Expected {EXPECTED_TESTS} tests, got {bundle.get('numtests')}")
            if code == 1:
                if test in starts:
                    raise RuntimeError(f"Duplicate instrumentation start: {test}")
                starts.add(test)
            else:
                if test not in starts or test in passed:
                    raise RuntimeError(f"Invalid instrumentation completion: {test}")
                passed.add(test)
            bundle = {}
    counts = {name: sum(test[0] == name for test in passed) for name in CLASSES}
    if counts != CLASSES or starts != passed:
        raise RuntimeError(f"Incomplete native acceptance: passed={counts}, started={len(starts)}")
    if not re.search(rf"^OK \({EXPECTED_TESTS} tests\)\s*$", output, re.MULTILINE):
        raise RuntimeError("Missing successful JUnit summary")
    if any(marker in output for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")):
        raise RuntimeError("Instrumentation reported a runner failure")
    return counts


class Acceptance:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.output = ROOT / "out/android-native"
        self.emulator: subprocess.Popen[bytes] | None = None
        self.emulator_log = None
        self.connected = False

    def run(self, label: str, command: list[str], timeout: int = 30,
            required: bool = True, binary: bool = False) -> bytes:
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, check=False)
            data = result.stdout
            errors = result.stderr
            code = result.returncode
        except subprocess.TimeoutExpired as failure:
            data = failure.stdout or b""
            errors = (failure.stderr or b"") + f"\nTimed out after {timeout}s\n".encode()
            code = -1
        suffix = ".tar" if binary else ".txt"
        (self.output / (label + suffix)).write_bytes(data if binary else data + errors)
        if binary and errors:
            (self.output / (label + "-stderr.txt")).write_bytes(errors)
        if required and code:
            raise RuntimeError(f"{label} failed (exit={code}); see saved diagnostics")
        return data

    def adb(self, label: str, *arguments: str, timeout: int = 30,
            required: bool = True, binary: bool = False) -> bytes:
        return self.run(label, [self.args.adb, "-s", SERIAL, *arguments], timeout, required, binary)

    def prepare(self) -> None:
        if os.environ.get("GITHUB_ACTIONS") != "true" or sys.platform != "linux":
            raise RuntimeError("This harness is restricted to an isolated Linux GitHub Actions runner")
        if not re.fullmatch(r"[0-9a-f]{40}", self.args.source_commit):
            raise RuntimeError("A full exact source commit is required")
        self.output.mkdir(parents=True, exist_ok=False)
        source = self.run("git-head", ["git", "rev-parse", "HEAD"]).decode().strip()
        if source != self.args.source_commit:
            raise RuntimeError("Checked-out source differs from the requested tested commit")
        if self.run("git-status", ["git", "status", "--porcelain"]).strip():
            raise RuntimeError("Native acceptance requires clean committed sources")
        (self.output / "SOURCE_COMMIT").write_text(source + "\n", encoding="utf-8")
        apks = {
            "application": (ROOT / "android/app/build/outputs/apk/debug/app-debug.apk", PACKAGE),
            "instrumentation": (ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk", PACKAGE + ".test"),
        }
        provenance = {"sourceCommit": source, "expectedTests": CLASSES, "apks": {}}
        for label, (apk, package) in apks.items():
            badging = self.run(label + "-badging", [self.args.aapt2, "dump", "badging", str(apk)]).decode()
            if not re.search(rf"^package: name='{re.escape(package)}' ", badging, re.MULTILINE):
                raise RuntimeError(f"Refusing unexpected {label} package")
            if "application-debuggable" not in badging:
                raise RuntimeError(f"Refusing non-debuggable {label} APK")
            provenance["apks"][label] = {"package": package, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
        (self.output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        devices = self.run("adb-before", [self.args.adb, "devices"]).decode()
        if any(line.strip() and not line.startswith("List of devices") for line in devices.splitlines()):
            raise RuntimeError("Refusing to run alongside an existing ADB device")
        self.run("emulator-version", [self.args.emulator, "-version"])
        sinks = self.run("pulse-before", ["pactl", "list", "short", "sinks"]).decode()
        if not any("audiobridge_native" in line and "module-null-sink" in line for line in sinks.splitlines()):
            raise RuntimeError("The clocked PulseAudio null output is unavailable")
        self.run("host-memory-before", ["free", "-m"])

    def boot(self) -> None:
        self.emulator_log = (self.output / "emulator.txt").open("wb")
        self.emulator = subprocess.Popen([
            self.args.emulator, "-avd", AVD, "-port", "5554", "-no-window",
            "-gpu", "swiftshader_indirect", "-no-snapshot", "-no-boot-anim",
            "-camera-back", "none", "-camera-front", "none",
            "-memory", "2048", "-cores", "4", "-accel", "on",
        ], cwd=ROOT, stdout=self.emulator_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if self.emulator.poll() is not None:
                raise RuntimeError("Owned emulator exited before boot completed")
            completed = self.adb("boot-progress", "shell", "getprop", "sys.boot_completed",
                                 timeout=10, required=False).strip()
            if completed == b"1":
                break
            time.sleep(2)
        else:
            raise RuntimeError("Owned emulator did not boot within 300 seconds")
        self.connected = True
        for property_name, expected in (("ro.kernel.qemu", b"1"),
                                         ("ro.product.cpu.abi", b"x86_64"),
                                         ("ro.build.version.sdk", b"35")):
            actual = self.adb(property_name, "shell", "getprop", property_name).strip()
            if actual != expected:
                raise RuntimeError(f"Emulator identity guard failed: {property_name}={actual!r}")
        avd_name = self.adb("avd-name", "emu", "avd", "name").decode().splitlines()
        if not avd_name or avd_name[0].strip() != AVD:
            raise RuntimeError("Emulator AVD ownership guard failed")
        self.adb("android-build", "shell", "getprop", "ro.build.fingerprint")

    def test(self) -> None:
        self.adb("install-debug", "install", "-r", "-t", str(ROOT / "android/app/build/outputs/apk/debug/app-debug.apk"), timeout=90)
        self.adb("install-tests", "install", "-r", "-t", str(ROOT / "android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"), timeout=90)
        self.adb("notifications", "shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        output = self.adb("instrumentation", "shell", "am", "instrument", "-w", "-r",
                          "-e", "allowPlayoutFixture", "true", "-e", "class", ",".join(CLASSES),
                          RUNNER, timeout=360).decode("utf-8", errors="replace")
        counts = validate_instrumentation(output)
        (self.output / "acceptance.json").write_text(json.dumps({"stage": "instrumentation", "status": "passed", "tests": counts}, indent=2) + "\n", encoding="utf-8")
        print(f"Native acceptance passed: {EXPECTED_TESTS} tests, zero skipped")

    def collect(self) -> None:
        if not self.output.exists():
            return
        if self.emulator is not None:
            self.adb("logcat", "logcat", "-b", "all", "-d", "-v", "threadtime", timeout=30, required=False)
            self.adb("audioflinger", "shell", "dumpsys", "media.audio_flinger", required=False)
            self.adb("audio", "shell", "dumpsys", "audio", required=False)
            # The debug journal is already redacted. Export only its allowlisted
            # directory, never app preferences, credentials or the host environment.
            if self.connected:
                journal = self.adb("runtime-journal", "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files/diagnostics", required=False, binary=True)
                if (self.output / "acceptance.json").exists():
                    with tarfile.open(fileobj=io.BytesIO(journal)) as archive:
                        if "files/diagnostics/runtime.log" not in archive.getnames():
                            raise RuntimeError("Successful native acceptance did not retain its runtime journal")
        self.run("pulse-after", ["pactl", "list", "sinks"], required=False)
        self.run("pulse-inputs-after", ["pactl", "list", "sink-inputs"], required=False)
        self.run("host-memory-after", ["free", "-m"], required=False)

    def close(self) -> None:
        if self.emulator is not None:
            if self.connected and self.emulator.poll() is None:
                self.adb("emulator-stop", "emu", "kill", timeout=10, required=False)
            try:
                self.emulator.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.emulator.terminate()
                try:
                    self.emulator.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.emulator.kill()
                    self.emulator.wait(timeout=5)
        if self.emulator_log is not None:
            self.emulator_log.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--emulator", required=True)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--aapt2", required=True)
    acceptance = Acceptance(parser.parse_args())
    failures: list[str] = []
    try:
        acceptance.prepare()
        acceptance.boot()
        acceptance.test()
    except Exception as failure:
        print(f"Native acceptance failed: {failure}", file=sys.stderr)
        if acceptance.output.exists():
            (acceptance.output / "failure.txt").write_text(str(failure) + "\n", encoding="utf-8")
        failures.append(str(failure))
    finally:
        try:
            acceptance.collect()
        except Exception as failure:
            print(f"Diagnostic collection failed: {failure}", file=sys.stderr)
            failures.append(f"Diagnostic collection: {failure}")
        finally:
            try:
                acceptance.close()
            except Exception as failure:
                print(f"Owned emulator cleanup failed: {failure}", file=sys.stderr)
                failures.append(f"Owned emulator cleanup: {failure}")
        if acceptance.output.exists():
            summary = {"status": "failed" if failures else "passed",
                       "sourceCommit": acceptance.args.source_commit,
                       "expectedTests": EXPECTED_TESTS, "failures": failures}
            (acceptance.output / "result.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
