import importlib.util
from pathlib import Path
import unittest


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


if __name__ == "__main__":
    unittest.main()
