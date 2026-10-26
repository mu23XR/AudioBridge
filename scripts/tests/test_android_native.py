import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "android_native", Path(__file__).parents[1] / "run_android_native.py")
native = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(native)


class AndroidNativeResultTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        for class_name, count in native.CLASSES.items():
            for index in range(count):
                for status in (1, 0):
                    self.events.append(self.event(class_name, f"fixture{index}", status))
        self.summary = "OK (12 tests)\n\nINSTRUMENTATION_CODE: -1\n"

    @staticmethod
    def event(class_name, method, status, count=12):
        return (f"INSTRUMENTATION_STATUS: class={class_name}\n"
                f"INSTRUMENTATION_STATUS: test={method}\n"
                f"INSTRUMENTATION_STATUS: numtests={count}\n"
                f"INSTRUMENTATION_STATUS_CODE: {status}\n")

    def output(self, events=None):
        return "\n".join(self.events if events is None else events) + "\n" + self.summary

    def test_all_twelve_successful_events_and_junit_summary_are_required(self):
        # Native ADB output can contain CRLF and blank lines between records.
        output = self.output().replace("\n", "\r\n")
        self.assertEqual(native.CLASSES, native.validate_instrumentation(output))

    def test_ignored_and_assumption_skips_fail_despite_success_summary(self):
        for status in (-3, -4):
            with self.subTest(status=status):
                events = list(self.events)
                events[1] = events[1].replace("STATUS_CODE: 0", f"STATUS_CODE: {status}")
                with self.assertRaisesRegex(RuntimeError, "failed/skipped"):
                    native.validate_instrumentation(self.output(events))

    def test_partial_run_and_summary_without_events_are_rejected(self):
        for events in (self.events[:-1], []):
            with self.subTest(event_count=len(events)):
                with self.assertRaisesRegex(RuntimeError, "Incomplete"):
                    native.validate_instrumentation(self.output(events))

    def test_duplicate_start_or_completion_is_not_an_additional_pass(self):
        for duplicate in (self.events[0], self.events[1]):
            with self.subTest(duplicate=duplicate):
                with self.assertRaisesRegex(RuntimeError, "Duplicate|Invalid"):
                    native.validate_instrumentation(self.output(self.events + [duplicate]))

    def test_missing_expected_class_or_missing_class_field_is_rejected(self):
        missing_class = list(native.CLASSES)[-1]
        without_class = [event for event in self.events if missing_class not in event]
        with self.assertRaisesRegex(RuntimeError, "Incomplete"):
            native.validate_instrumentation(self.output(without_class))
        events = list(self.events)
        events[0] = "\n".join(events[0].splitlines()[1:]) + "\n"
        with self.assertRaisesRegex(RuntimeError, "Unexpected"):
            native.validate_instrumentation(self.output(events))

    def test_wrong_reported_count_or_wrong_summary_count_is_rejected(self):
        wrong_reported = self.output().replace("numtests=12", "numtests=13", 1)
        wrong_summary = self.output().replace("OK (12 tests)", "OK (11 tests)")
        for output in (wrong_reported, wrong_summary):
            with self.subTest(output_tail=output[-100:]):
                with self.assertRaises(RuntimeError):
                    native.validate_instrumentation(output)

    def test_runner_failure_marker_overrides_otherwise_successful_events(self):
        for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"):
            with self.subTest(marker=marker):
                with self.assertRaisesRegex(RuntimeError, "runner failure"):
                    native.validate_instrumentation(self.output() + marker + "\n")


class AndroidNativeAudioEnvironmentTest(unittest.TestCase):
    def setUp(self):
        self.environment = {"PULSE_SERVER": "unix:/run/user/1001/pulse/native",
                            "QEMU_PA_SERVER": "unix:/run/user/1001/pulse/native",
                            "XDG_RUNTIME_DIR": "/run/user/1001", "PULSE_SINK": "audiobridge_native"}
        self.metadata = {"/run/user/1001/pulse/native": SimpleNamespace(st_mode=stat.S_IFSOCK | 0o600, st_uid=1001),
                         "/run/user/1001": SimpleNamespace(st_mode=stat.S_IFDIR | 0o700, st_uid=1001)}
        self.stream = {"index": 3, "sink": 1, "corked": False, "properties": {"application.process.id": "42"}}

    def test_explicit_owned_socket_and_private_runtime_are_required(self):
        record = native.validate_pulse_environment(self.environment, 1001, self.metadata.__getitem__)
        self.assertEqual(record["server"], self.environment["PULSE_SERVER"])
        for key, value in (("PULSE_SERVER", "tcp:localhost"), ("QEMU_PA_SERVER", ""),
                           ("XDG_RUNTIME_DIR", "/tmp/another-home"), ("PULSE_SINK", "another-sink")):
            with self.subTest(key=key), self.assertRaises(RuntimeError):
                native.validate_pulse_environment({**self.environment, key: value}, 1001, self.metadata.__getitem__)
        for key, value in (("/run/user/1001/pulse/native", SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=1001)),
                           ("/run/user/1001/pulse/native", SimpleNamespace(st_mode=stat.S_IFSOCK | 0o600, st_uid=0)),
                           ("/run/user/1001", SimpleNamespace(st_mode=stat.S_IFDIR | 0o777, st_uid=1001))):
            metadata = {**self.metadata, key: value}
            with self.subTest(metadata=key), self.assertRaises(RuntimeError):
                native.validate_pulse_environment(self.environment, 1001, metadata.__getitem__)

    def test_init_failure_is_rejected_even_if_tests_or_cleanup_otherwise_pass(self):
        native.validate_emulator_audio_log("emulator booted; AudioTrack advanced")
        for marker in ("Could not init `pa' audio driver", "pulseaudio: Failed to initialize PA context"):
            with self.assertRaisesRegex(RuntimeError, "failed to initialize"):
                native.validate_emulator_audio_log("OK (12 tests)\n" + marker)

    def test_only_owned_uncorked_stream_on_exact_sink_counts(self):
        parents = {42: 30, 30: 10, 11: 1}
        expected = [{"pid": 42, "inputIndex": 3, "sinkIndex": 1}]
        self.assertEqual(expected, native.owned_active_streams([self.stream], 1, 10, parents.get))
        for stream in ({**self.stream, "sink": 2}, {**self.stream, "corked": True},
                       {**self.stream, "properties": {"application.process.id": "11"}},
                       {**self.stream, "properties": {"application.process.id": "99"}}):
            self.assertEqual([], native.owned_active_streams([stream], 1, 10, parents.get))
        with self.assertRaises(PermissionError):
            native.owned_active_streams([self.stream], 1, 10, lambda _: (_ for _ in ()).throw(PermissionError("proc denied")))
        with self.assertRaisesRegex(RuntimeError, "ancestry"):
            native.owned_active_streams([self.stream], 1, 10, lambda _: 42)

    def test_monitor_collects_active_evidence_while_action_runs_and_joins_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            acceptance = native.Acceptance(SimpleNamespace())
            acceptance.output = Path(directory)
            acceptance.emulator = SimpleNamespace(pid=42)
            acceptance.pulse_sink_index = 1
            sampled = threading.Event()
            in_action = threading.Event()

            def pactl(*args, **kwargs):
                self.assertTrue(in_action.is_set(), "Sample ran before instrumentation action")
                sampled.set()
                return subprocess.CompletedProcess(args[0], 0, json.dumps([self.stream]).encode(), b"")

            def fixtures():
                in_action.set()
                self.assertTrue(sampled.wait(1), "Monitor never overlapped instrumentation")
                return b"instrumentation result"

            with patch.object(native.subprocess, "run", side_effect=pactl):
                self.assertEqual(b"instrumentation result", acceptance.with_live_audio(fixtures, interval=0.01))
                sampled.clear()
                with self.assertRaisesRegex(ValueError, "fixture failed"):
                    acceptance.with_live_audio(lambda: (_ for _ in ()).throw(ValueError("fixture failed")), interval=0.01)
            self.assertFalse(any(t.name == "native-pulse-monitor" for t in threading.enumerate()))
            record = json.loads((Path(directory) / "audio-path-live.json").read_text(encoding="utf-8"))
            self.assertEqual([], record["errors"])

    def test_missing_active_path_and_monitor_errors_cannot_be_green(self):
        for evidence in ({"activeSamples": 0, "errors": []}, {"activeSamples": 1, "errors": ["pactl failed"]}):
            with self.assertRaises(RuntimeError):
                native.validate_live_audio(evidence)
        with tempfile.TemporaryDirectory() as directory:
            acceptance = native.Acceptance(SimpleNamespace())
            acceptance.output = Path(directory)
            acceptance.emulator = SimpleNamespace(pid=42)
            acceptance.pulse_sink_index = 1
            sampled = threading.Event()

            def invalid(*args, **kwargs):
                sampled.set()
                return subprocess.CompletedProcess(args[0], 0, b"invalid JSON", b"")

            with patch.object(native.subprocess, "run", side_effect=invalid), self.assertRaisesRegex(RuntimeError, "monitor failed"):
                acceptance.with_live_audio(lambda: sampled.wait(1), interval=0.01)
            self.assertFalse(any(t.name == "native-pulse-monitor" for t in threading.enumerate()))


if __name__ == "__main__":
    unittest.main()
