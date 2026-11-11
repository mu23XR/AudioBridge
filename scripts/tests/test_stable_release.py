import copy
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).parents[1]))
import stable_release as stable


class StableReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source, self.version, self.code = "a" * 40, "2.0.1", 610261200
        self.record = {"sourceCommit": self.source, "androidTree": "b" * 40, "desktopTree": "c" * 40}
        self.notes = self.root / "docs/releases"
        self.notes.mkdir(parents=True)
        (self.notes / "2.0.1.md").write_text("Stable release notes\n", encoding="utf-8")
        stable.write_json(self.notes / "stable-build-request.json", {"version": self.version, "publish": True})
        self.event = {"pull_request": {"head": {"repo": {"full_name": "mu23XR/AudioBridge"}, "sha": self.source}}}
        self.packages = self.root / "desktop/build/packages"
        self.packages.mkdir(parents=True)
        self.stem = "AudioBridge-2.0.1-windows-x86_64"

    def windows_files(self, version="2.0.1", channel="stable"):
        contents = {"version.properties": f"app.display.version={version}\napp.channel={channel}\n".encode(),
                    "man/wfas.1": ("Manual " + version).encode()}
        for name in ("MainKt.class", "DesktopReceiverSessions.class", "DesktopJitterAudioPlayer.class",
                     "PcmRenderStage.class", "native/windows/x86_64/audio_engine.dll"):
            contents[name] = b"fixture"
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as archive:
            for name, data in contents.items():
                archive.writestr(name, data)
        files = {"AudioBridge/app/AudioBridge-7.0.jar": jar.getvalue()}
        for name in ("AudioBridge.exe", "runtime/bin/java.exe", "wfas.cmd", "app/AudioBridge.cfg"):
            files["AudioBridge/" + name] = b"fixture"
        return files

    def archives(self, files=None, tar_files=None):
        files = files or self.windows_files()
        with zipfile.ZipFile(self.packages / (self.stem + ".zip"), "w") as package:
            for name, data in files.items():
                package.writestr(name, data)
        with tarfile.open(self.packages / (self.stem + ".tar.gz"), "w:gz") as package:
            for name, data in (tar_files or files).items():
                info = tarfile.TarInfo(name)
                info.size = len(data)
                package.addfile(info, io.BytesIO(data))

    def valid_bundle(self):
        self.archives()
        windows = self.root / "windows"
        with patch.object(stable, "source_identity", return_value=self.record):
            stable.collect_windows(self.root, windows, self.source, self.version)
        apk = self.root / "fixture.apk"
        apk.write_bytes(b"signed APK fixture")
        actual = {"package": stable.PACKAGE, "versionName": self.version, "versionCode": self.code,
                  "signingCertificateSha256": stable.SIGNER, "bytes": apk.stat().st_size, "sha256": stable.sha256(apk)}
        android = self.root / "android"
        with patch.object(stable, "source_identity", return_value=self.record), patch.object(stable, "inspect_apk", return_value=actual):
            stable.collect_android(self.root, android, self.source, self.version, self.code, apk, Path("aapt2"), Path("apksigner"))
        inputs = self.root / "inputs"
        inputs.mkdir()
        for directory in (android, windows):
            for path in directory.iterdir():
                shutil.copyfile(path, inputs / path.name)
        output = self.root / "release"
        with patch.object(stable, "source_identity", return_value=self.record):
            stable.bundle(self.root, inputs, output, self.source, self.version, self.code)
        return output

    def test_request_requires_same_repository_head_explicit_boolean_and_stable_version(self):
        self.assertEqual({"version": "2.0.1", "publish": True}, stable.release_request(
            self.root, self.event, "pull_request", self.source, "mu23XR/AudioBridge"))
        for field, value in (("sha", "d" * 40), ("repo", {"full_name": "someone/fork"})):
            event = copy.deepcopy(self.event)
            event["pull_request"]["head"][field] = value
            with self.assertRaises(ValueError):
                stable.release_request(self.root, event, "pull_request", self.source, "mu23XR/AudioBridge")
        for request in ({"version": "2.0.1-test.1", "publish": True}, {"version": "2.0.1", "publish": "true"},
                        {"version": "2.0.1", "publish": True, "source": self.source}):
            stable.write_json(self.notes / "stable-build-request.json", request)
            with self.assertRaises(ValueError):
                stable.release_request(self.root, self.event, "pull_request", self.source, "mu23XR/AudioBridge")

    def test_tag_and_manual_requests_preserve_explicit_stable_publication(self):
        self.assertTrue(stable.release_request(self.root, {}, "push", self.source, "mu23XR/AudioBridge", "v2.0.1")["publish"])
        self.assertFalse(stable.release_request(self.root, {}, "workflow_dispatch", self.source, "mu23XR/AudioBridge",
                                              inputs={"release_tag": "v2.0.1"})["publish"])
        with self.assertRaises(ValueError):
            stable.release_request(self.root, {}, "push", self.source, "mu23XR/AudioBridge", "v2.0.1-test.1")

    def test_request_accepts_plain_utf8_and_windows_utf8_bom(self):
        request = self.notes / "stable-build-request.json"
        request.write_text(json.dumps({"version": self.version, "publish": True}), encoding="utf-8-sig")
        self.assertTrue(stable.release_request(self.root, self.event, "pull_request", self.source,
                                             "mu23XR/AudioBridge")["publish"])

    def test_actual_android_identity_must_be_release_package_version_code_and_permanent_signer(self):
        actual = {"package": stable.PACKAGE, "versionName": self.version, "versionCode": self.code,
                  "signingCertificateSha256": stable.SIGNER}
        stable.validate_android(actual, self.version, self.code)
        for field, value in (("package", stable.PACKAGE + ".test"), ("versionName", "1.3.1-test.21"),
                             ("versionCode", self.code - 1), ("signingCertificateSha256", "0" * 64)):
            with self.assertRaises(ValueError):
                stable.validate_android({**actual, field: value}, self.version, self.code)

    def test_windows_checks_content_channel_manual_and_matching_tar_bytes(self):
        self.archives()
        expected = stable.check_windows_zip(self.packages / (self.stem + ".zip"), self.version)
        stable.check_windows_tar(self.packages / (self.stem + ".tar.gz"), expected)
        changed = self.windows_files()
        changed["AudioBridge/AudioBridge.exe"] = b"changed"
        self.archives(tar_files=changed)
        with self.assertRaisesRegex(ValueError, "bytes differ"):
            stable.check_windows_tar(self.packages / (self.stem + ".tar.gz"), expected)
        for files in (self.windows_files(channel="preview"), self.windows_files(version="1.3.1-test.21-vpn.9"),
                      {**self.windows_files(), "../escape": b"bad"}):
            self.archives(files)
            with self.assertRaises((ValueError, KeyError)):
                stable.check_windows_zip(self.packages / (self.stem + ".zip"), self.version)

    def test_missing_exact_version_archive_does_not_select_another_zip(self):
        self.archives()
        (self.packages / (self.stem + ".zip")).rename(self.packages / "AudioBridge-old-windows-x86_64.zip")
        with patch.object(stable, "source_identity", return_value=self.record), self.assertRaisesRegex(ValueError, "exact-version"):
            stable.collect_windows(self.root, self.root / "output", self.source, self.version)

    def test_unified_bundle_rejects_source_substitution_and_tampered_assets(self):
        output = self.valid_bundle()
        stable.verify(output, self.source, self.version)
        with self.assertRaises(ValueError):
            stable.verify(output, "d" * 40, self.version)
        (output / "AudioBridge-Android-2.0.1.apk").write_bytes(b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            stable.verify(output, self.source, self.version)

    def test_platform_record_from_another_commit_cannot_be_bundled(self):
        self.valid_bundle()
        record = self.root / "inputs/windows-build.json"
        stable.write_json(record, {**stable.read_json(record), "sourceCommit": "d" * 40})
        with patch.object(stable, "source_identity", return_value=self.record), self.assertRaisesRegex(ValueError, "Platform source"):
            stable.bundle(self.root, self.root / "inputs", self.root / "wrong", self.source, self.version, self.code)

    def test_workflows_gate_publication_on_exact_source_native_and_hard_lint(self):
        root = Path(__file__).parents[2]
        workflow = (root / ".github/workflows/release.yml").read_text(encoding="utf-8-sig")
        native = (root / ".github/workflows/android-native.yml").read_text(encoding="utf-8-sig")
        self.assertIn("needs: [meta, android, windows, governance, native]", workflow)
        self.assertIn(":app:testDebugUnitTest :app:lintDebug :app:assembleRelease", workflow)
        self.assertNotIn("continue-on-error", workflow)
        self.assertIn('test "$NATIVE_SOURCE" = "$SOURCE_SHA"', workflow)
        self.assertIn('ref: ${{ inputs.source_ref || github.event.pull_request.head.sha || github.ref }}', native)
        self.assertIn('--source-commit "${{ steps.source.outputs.sha }}"', native)

    def publish_fixture(self, output, tamper=False, existing=None, tag_source=None):
        event_path = self.root / "event.json"
        stable.write_json(event_path, self.event)
        environment = {"GITHUB_ACTIONS": "true", "GH_TOKEN": "fixture-token", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
                       "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event_path)}
        draft = {"id": 7, "draft": True, "prerelease": True, "target_commitish": self.source, "assets": [], "html_url": "fixture"}
        final = {**draft, "draft": False, "prerelease": False}
        releases = [existing] if existing is not None else [None, draft, final]
        calls = []

        def api(path, optional=False, fields=None):
            calls.append(("api", path))
            if "/git/ref/tags/" in path:
                return {"object": {"type": "commit", "sha": tag_source}} if tag_source else None
            if path.endswith("/releases/latest"):
                return {"id": 7}
            return {}

        def gh(*args):
            calls.append(args)
            if args[:2] == ("release", "download"):
                target = Path(args[args.index("--dir") + 1])
                for path in output.iterdir():
                    shutil.copyfile(path, target / path.name)
                if tamper:
                    (target / "SOURCE_COMMIT.txt").write_text("bad", encoding="utf-8")
            return ""

        with patch.dict(os.environ, environment), patch.object(stable, "source_identity", return_value=self.record), \
                patch.object(stable.transport, "api", side_effect=api), \
                patch.object(stable.transport, "find_release", side_effect=releases), patch.object(stable.transport, "gh_ok", side_effect=gh):
            try:
                stable.publish(self.root, output, self.source, self.version)
            except ValueError:
                self.calls = calls
                raise
        return calls

    def test_publish_download_verifies_all_assets_before_stable_latest(self):
        calls = self.publish_fixture(self.valid_bundle())
        operations = [call[1] for call in calls if call[0] == "release"]
        self.assertEqual(["create", "upload", "download", "edit"], operations)
        self.assertNotIn("--clobber", [item for call in calls for item in call])

    def test_failed_download_never_promotes_and_existing_publication_or_other_tag_source_is_immutable(self):
        output = self.valid_bundle()
        with self.assertRaisesRegex(ValueError, "Downloaded stable asset differs"):
            self.publish_fixture(output, tamper=True)
        self.assertFalse(any(call[:2] == ("release", "edit") for call in self.calls))
        with self.assertRaisesRegex(ValueError, "another source"):
            self.publish_fixture(output, tag_source="d" * 40)
        with self.assertRaisesRegex(ValueError, "Existing published release"):
            self.publish_fixture(output, tag_source=self.source, existing={"draft": False})

    def test_publish_false_never_creates_tag_or_release(self):
        output = self.valid_bundle()
        stable.write_json(self.notes / "stable-build-request.json", {"version": self.version, "publish": False})
        with self.assertRaisesRegex(ValueError, "not explicitly requested"):
            self.publish_fixture(output)
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
