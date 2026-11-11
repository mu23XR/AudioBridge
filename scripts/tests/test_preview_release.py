import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).parents[1]))
import preview_release as preview


class PreviewReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.notes = self.root / "docs/releases"
        self.notes.mkdir(parents=True)
        self.version, self.source, self.code = "2.0.2-test.1", "a" * 40, 611120001
        self.record = {"sourceCommit": self.source, "androidTree": "b" * 40, "desktopTree": "c" * 40}
        self.notes.joinpath("build-request.json").write_text(json.dumps({
            "version": self.version, "publish": True, "channel": "preview"}), encoding="utf-8")
        self.notes.joinpath(self.version + ".md").write_text("Test release notes.\n", encoding="utf-8")
        self.event = self.root / "event.json"
        self.event.write_text(json.dumps({"pull_request": {"head": {
            "repo": {"full_name": "mu23XR/AudioBridge"}, "sha": self.source}}}), encoding="utf-8")
        environment = {"GITHUB_ACTIONS": "true", "GH_TOKEN": "fixture-not-a-token", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
                       "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(self.event),
                       "GITHUB_OUTPUT": str(self.root / "outputs.txt")}
        for mock in (patch.dict(os.environ, environment), patch.object(preview.github, "git", return_value=self.source),
                     patch.object(preview.stable, "source_identity", return_value=self.record)):
            mock.start()
            self.addCleanup(mock.stop)

    def apk_identity(self, data=b"signed fixture"):
        return {"package": preview.PACKAGE, "versionName": self.version, "versionCode": self.code,
                "signingCertificateSha256": preview.SIGNER, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}

    def windows_zip(self, path):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, "w") as output:
            output.writestr("version.properties", "app.display.version=" + self.version + "\napp.channel=preview\n")
            output.writestr("man/wfas.1", self.version)
            for name in ("MainKt.class", "DesktopPeerEndpoint.class", "DesktopJitterAudioPlayer.class",
                         "native/windows/x86_64/audio_engine.dll"):
                output.writestr(name, b"fixture")
        with zipfile.ZipFile(path, "w") as output:
            for name in ("AudioBridge-Test.exe", "runtime/bin/java.exe", "wfas.cmd", "app/AudioBridge-Test.cfg"):
                output.writestr("AudioBridge-Test/" + name, b"fixture")
            output.writestr("AudioBridge-Test/app/AudioBridge-fixture.jar", jar.getvalue())

    def inputs(self):
        directory = self.root / "inputs"
        directory.mkdir()
        apk = directory / ("AudioBridge-Android-Preview-" + self.version + ".apk")
        apk.write_bytes(b"signed fixture")
        archive = directory / ("AudioBridge-" + self.version + "-windows-x86_64.zip")
        self.windows_zip(archive)
        preview.stable.write_json(directory / "android-build.json", {
            **self.record, "version": self.version, "android": self.apk_identity(), "asset": apk.name})
        preview.stable.write_json(directory / "windows-build.json", {
            **self.record, "version": self.version, "channel": "preview", "asset": archive.name,
            "bytes": archive.stat().st_size, "sha256": preview.github.checksum(archive)})
        return directory

    def bundle(self):
        output = self.root / "bundle"
        preview.bundle(self.root, self.inputs(), output, self.source, self.version, self.code)
        return output

    def test_same_repository_request_pins_source_and_manual_publish_is_explicit(self):
        self.assertEqual(preview.request(self.root), {"source": self.source, "version": self.version,
                                                    "publish": True, "prefix": "AudioBridge-Android-Preview"})
        preview.prepare(self.root)
        self.assertIn("source=" + self.source, (self.root / "outputs.txt").read_text(encoding="utf-8"))
        os.environ["GITHUB_EVENT_NAME"] = "workflow_dispatch"
        self.event.write_text(json.dumps({"inputs": {"version_name": self.version, "publish": "false"}}), encoding="utf-8")
        self.assertFalse(preview.request(self.root)["publish"])
        self.event.write_text(json.dumps({"inputs": {"version_name": self.version, "publish": "true"}}), encoding="utf-8")
        self.assertTrue(preview.request(self.root)["publish"])

    def test_forks_stable_versions_bad_channel_and_string_boolean_are_rejected(self):
        request = self.notes / "build-request.json"
        for data in ({"version": "2.0.2", "publish": True, "channel": "preview"},
                     {"version": self.version, "publish": "true", "channel": "preview"},
                     {"version": self.version, "publish": True, "channel": "stable"}):
            preview.stable.write_json(request, data)
            with self.assertRaises(ValueError):
                preview.request(self.root)
        self.event.write_text(json.dumps({"pull_request": {"head": {
            "repo": {"full_name": "someone/fork"}, "sha": self.source}}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Fork"):
            preview.request(self.root)

    def test_postbuild_apk_checks_permanent_signer_package_version_and_code(self):
        apk = self.root / "app.apk"
        apk.write_bytes(b"signed fixture")
        actual = self.apk_identity()
        for key, value in (("package", "io.github.mu23xr.audiobridge"), ("versionName", "2.0.2"),
                           ("versionCode", self.code + 1), ("signingCertificateSha256", "0" * 64)):
            with patch.object(preview, "inspect_apk", return_value={**actual, key: value}):
                with self.assertRaisesRegex(ValueError, key):
                    preview.collect_android(self.root, self.root / "invalid", self.source, self.version, self.code,
                                            apk, Path("aapt2"), Path("apksigner"), "AudioBridge-Android-Preview")
                self.assertFalse((self.root / "invalid").exists())
        with patch.object(preview, "inspect_apk", return_value=actual):
            preview.collect_android(self.root, self.root / "android", self.source, self.version, self.code,
                                    apk, Path("aapt2"), Path("apksigner"), "AudioBridge-Android-Preview")
        self.assertTrue((self.root / "android/android-build.json").is_file())

    def test_platform_source_mismatch_and_tampered_package_never_pass_bundle(self):
        inputs = self.inputs()
        path = inputs / "windows-build.json"
        preview.stable.write_json(path, {**preview.stable.read_json(path), "sourceCommit": "d" * 40})
        with self.assertRaisesRegex(ValueError, "source/version"):
            preview.bundle(self.root, inputs, self.root / "bad", self.source, self.version, self.code)
        self.assertFalse((self.root / "bad").exists())
        record = preview.stable.read_json(path)
        record["sourceCommit"] = self.source
        preview.stable.write_json(path, record)
        (inputs / record["asset"]).write_bytes(b"corrupted archive")
        with self.assertRaisesRegex(ValueError, "build record"):
            preview.bundle(self.root, inputs, self.root / "bad", self.source, self.version, self.code)

    def test_preview_bundle_checksums_provenance_and_channel_are_rechecked(self):
        output = self.bundle()
        preview.verify(output, self.source, self.version)
        with self.assertRaises(ValueError):
            preview.verify(output, "d" * 40, self.version)
        (output / "SOURCE_COMMIT.txt").write_text("d" * 40 + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "checksum"):
            preview.verify(output, self.source, self.version)

    def publish(self, output, corrupt=False, already_published=False):
        state = {"draft": not already_published, "prerelease": True, "target_commitish": self.source,
                 "assets": [], "id": 1}
        created = [already_published]
        uploaded, operations = {}, []
        def api(endpoint, optional=False, fields=None):
            if "/git/ref/tags/" in endpoint:
                return None
            if endpoint.endswith("/git/refs"):
                self.assertEqual(fields, {"ref": "refs/tags/v" + self.version, "sha": self.source})
                return {}
            self.fail(endpoint)
        def find(repo, tag):
            self.assertEqual(tag, "v" + self.version)
            return dict(state) if created[0] else None
        def gh(*args):
            operation = args[1]
            operations.append(args)
            if operation == "create":
                self.assertIn("--draft", args)
                self.assertIn("--latest=false", args)
                created[0] = True
            elif operation == "upload":
                for name in args[args.index("--repo") + 2:]:
                    path = Path(name)
                    uploaded[path.name] = path.read_bytes()
            elif operation == "download":
                for name, content in uploaded.items():
                    Path(args[args.index("--dir") + 1], name).write_bytes(content + (b"tamper" if corrupt else b""))
            elif operation == "edit":
                self.assertIn("--prerelease", args)
                self.assertIn("--latest=false", args)
                state["draft"] = False
            else:
                self.fail(args)
            return ""
        with patch.object(preview.github, "api", side_effect=api), patch.object(preview.github, "find_release", side_effect=find), \
                patch.object(preview.github, "gh_ok", side_effect=gh):
            preview.publish(self.root, output, self.source, self.version)
        return operations

    def test_prerelease_promotion_follows_uploaded_byte_verification(self):
        operations = self.publish(self.bundle())
        names = [op[1] for op in operations]
        self.assertLess(names.index("download"), names.index("edit"))

    def test_corrupt_download_or_existing_public_preview_cannot_be_promoted(self):
        output = self.bundle()
        with self.assertRaisesRegex(ValueError, "Downloaded preview bytes"):
            self.publish(output, corrupt=True)
        with self.assertRaisesRegex(ValueError, "Existing published preview"):
            self.publish(output, already_published=True)

    def test_publish_false_cannot_mutate_github(self):
        output = self.bundle()
        preview.stable.write_json(self.notes / "build-request.json", {"version": self.version, "publish": False, "channel": "preview"})
        with patch.object(preview.github, "api") as api, self.assertRaisesRegex(ValueError, "explicitly requested"):
            preview.publish(self.root, output, self.source, self.version)
        api.assert_not_called()

    def test_release_workflows_keep_gates_and_remove_actions_artifact_transport(self):
        root = Path(__file__).parents[2]
        for name in ("release.yml", "android-test.yml", "desktop-windows.yml", "android-native.yml"):
            workflow = (root / ".github/workflows" / name).read_text(encoding="utf-8-sig")
            self.assertNotIn("actions/upload-artifact", workflow)
            self.assertNotIn("actions/download-artifact", workflow)
            self.assertNotIn("continue-on-error", workflow)
        stable = (root / ".github/workflows/release.yml").read_text(encoding="utf-8-sig")
        self.assertIn("needs: [meta, android, windows, governance, native]", stable)
        test = (root / ".github/workflows/android-test.yml").read_text(encoding="utf-8-sig")
        self.assertIn("needs: [meta, build, windows, governance]", test)
        for task in (":app:testDebugUnitTest", ":app:lintDebug", ":app:assemblePreview"):
            self.assertIn(task, test)
        self.assertIn("SIGNING_KEY_BASE64", test)
        windows = (root / ".github/workflows/desktop-windows.yml").read_text(encoding="utf-8-sig")
        for task in (".\\gradlew.bat check", ".\\gradlew.bat generateMan", "packagePortableArchives"):
            self.assertIn(task, windows)
        native = (root / ".github/workflows/android-native.yml").read_text(encoding="utf-8-sig")
        self.assertIn("python3 scripts/run_android_native.py", native)


if __name__ == "__main__":
    unittest.main()
