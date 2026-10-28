"""No-network regressions for preview identity, tampering and publication boundaries."""
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import desktop_preview as preview


class PreviewChecks(unittest.TestCase):
    version = "1.3.1-test.21-vpn.2"
    source = "a" * 40

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "desktop").mkdir()
        (self.root / "docs/releases").mkdir(parents=True)
        (self.root / "desktop/gradle.properties").write_text("desktopPreviewVersion=" + self.version, encoding="utf-8")
        (self.root / "docs/releases/desktop-preview.json").write_text(
            json.dumps({"version": self.version, "publish": True}), encoding="utf-8")
        (self.root / "docs/releases" / (self.version + ".md")).write_text("Known preview limits", encoding="utf-8")

    def image(self, path, version=None, extra=None):
        main = io.BytesIO()
        with zipfile.ZipFile(main, "w") as jar:
            jar.writestr("version.properties", "app.display.version=" + (version or self.version) + "\napp.channel=preview\n")
            jar.writestr("man/wfas.1", self.version)
            for name in ("MainKt.class", "DesktopPeerEndpoint.class", "DesktopJitterAudioPlayer.class",
                         "native/windows/x86_64/audio_engine.dll"):
                jar.writestr(name, b"fixture")
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AudioBridge-Test/app/AudioBridge-fixture.jar", main.getvalue())
            for name in ("AudioBridge-Test.exe", "runtime/bin/java.exe", "wfas.cmd", "app/AudioBridge-Test.cfg"):
                archive.writestr("AudioBridge-Test/" + name, b"fixture")
            if extra:
                archive.writestr(extra, b"rejected")

    def test_request_rejects_stable_wrong_types_and_version_mismatch(self):
        self.assertTrue(preview.request(self.root)["publish"])
        file = self.root / "docs/releases/desktop-preview.json"
        for data in ({"version": "1.3.1", "publish": True}, {"version": self.version, "publish": "true"},
                     {"version": self.version, "publish": True, "source": "other"},
                     {"version": "1.3.1-test.22", "publish": True}):
            file.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ValueError):
                preview.request(self.root)

    def test_image_rejects_traversal_and_wrong_packaged_version(self):
        file = self.root / "image.zip"
        self.image(file)
        preview.check_zip(file, self.version)
        for extra in ("../escape", "AudioBridge-Test/../../escape", "OtherApp/image"):
            self.image(file, extra=extra)
            with self.assertRaises(ValueError):
                preview.check_zip(file, self.version)
        self.image(file, version="1.3.1-test.17")
        with self.assertRaises(ValueError):
            preview.check_zip(file, self.version)

    def test_bundle_detects_tampering_and_source_substitution(self):
        output = self.root / "output"
        output.mkdir()
        stem = "AudioBridge-" + self.version + "-windows-x86_64"
        self.image(output / (stem + ".zip"))
        with tarfile.open(output / (stem + ".tar.gz"), "w:gz") as archive:
            with zipfile.ZipFile(output / (stem + ".zip")) as image:
                for entry in image.infolist():
                    item = tarfile.TarInfo(entry.filename)
                    item.size = entry.file_size
                    archive.addfile(item, io.BytesIO(image.read(entry)))
        (output / "SOURCE_COMMIT.txt").write_text(self.source + "\n", encoding="utf-8")
        (output / "release-notes.md").write_text("preview", encoding="utf-8")
        assets = {p.name: {"bytes": p.stat().st_size, "sha256": preview.checksum(p)}
                  for p in output.iterdir() if p.name.endswith((".zip", ".tar.gz"))}
        (output / "provenance.json").write_text(json.dumps({"version": self.version, "sourceCommit": self.source,
            "channel": "preview", "assets": assets}), encoding="utf-8")
        sums = "".join(preview.checksum(p) + "  " + p.name + "\n" for p in sorted(output.iterdir()))
        (output / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
        preview.verify(output, self.version, self.source)
        with self.assertRaises(ValueError):
            preview.verify(output, self.version, "b" * 40)
        (output / "SOURCE_COMMIT.txt").write_text("b" * 40, encoding="utf-8")
        with self.assertRaises(ValueError):
            preview.verify(output, self.version, self.source)

    def test_only_not_found_may_be_optional_api_result(self):
        for status in (403, 404, 500):
            result = subprocess.CompletedProcess([], 1, json.dumps({"status": str(status)}), "injected")
            with patch.object(preview, "gh", return_value=result):
                if status == 404:
                    self.assertIsNone(preview.api("fixture", optional=True))
                else:
                    with self.assertRaises(ValueError):
                        preview.api("fixture", optional=True)

    def test_fork_cannot_reach_publication_network(self):
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"head": {"repo": {"full_name": "fork/repo"},
            "sha": self.source}}}), encoding="utf-8")
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
            "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event), "GH_TOKEN": "fixture-not-a-credential"}
        with patch.dict(os.environ, environment), patch.object(preview, "gh") as network:
            with self.assertRaises(ValueError):
                preview.publish(self.root, self.root / "missing", self.source)
            network.assert_not_called()

    def test_draft_lookup_distinguishes_absence_from_graphql_failure(self):
        draft = {"tag_name": "v" + self.version, "draft": True}
        with patch.object(preview, "api", side_effect=[None,
                {"data": {"repository": {"release": {"databaseId": 123}}}}, draft]) as api:
            self.assertEqual(preview.find_release("mu23XR/AudioBridge", "v" + self.version), draft)
            self.assertTrue(api.call_args_list[-1].args[0].endswith("/releases/123"))
        for response in ({"errors": [{"message": "denied"}]}, {"data": {"repository": None}}):
            with patch.object(preview, "api", side_effect=[None, response]):
                with self.assertRaises(ValueError):
                    preview.find_release("mu23XR/AudioBridge", "v" + self.version)
        with patch.object(preview, "api", side_effect=[None, {"data": {"repository": {"release": None}}}]):
            self.assertIsNone(preview.find_release("mu23XR/AudioBridge", "v" + self.version))

    def test_draft_create_resume_and_download_must_succeed_before_publication(self):
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"head": {"repo": {"full_name": "mu23XR/AudioBridge"},
            "sha": self.source}}}), encoding="utf-8")
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
            "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event), "GH_TOKEN": "fixture-not-a-credential"}
        for resume, tamper in ((False, False), (True, False), (True, True)):
            with self.subTest(resume=resume, tamper=tamper):
                output = self.root / ("output-" + str(resume) + str(tamper))
                output.mkdir()
                (output / "release-notes.md").write_text("fixture", encoding="utf-8")
                (output / "package.zip").write_bytes(b"verified bytes")
                state = {"created": resume, "draft": True}
                uploaded = {}
                operations = []

                def fake_api(endpoint, **kwargs):
                    if "/git/ref/" in endpoint:
                        return {"object": {"type": "commit", "sha": self.source}}
                    release = {"tag_name": "v" + self.version, "draft": state["draft"], "prerelease": True,
                        "target_commitish": self.source, "assets": [], "html_url": "https://github.com/fixture"}
                    if "/releases/tags/" in endpoint:
                        return release if state["created"] and not state["draft"] else None
                    if endpoint == "graphql":
                        return {"data": {"repository": {"release": {"databaseId": 123} if state["created"] else None}}}
                    if endpoint.endswith("/releases/123"):
                        return release
                    self.fail("Unexpected endpoint: " + endpoint)

                def fake_gh(*args):
                    operation = args[1]
                    operations.append(operation)
                    if operation == "create":
                        state["created"] = True
                    elif operation == "upload":
                        uploaded.update({p.name: p.read_bytes() for p in output.iterdir()})
                    elif operation == "download":
                        directory = Path(args[args.index("--dir") + 1])
                        for name, content in uploaded.items():
                            (directory / name).write_bytes(content + (b"corrupt" if tamper else b""))
                    elif operation == "edit":
                        state["draft"] = False
                    else:
                        self.fail("Unexpected gh operation: " + operation)
                    return subprocess.CompletedProcess(args, 0, "", "")

                with patch.dict(os.environ, environment), patch.object(preview, "check_source"), \
                        patch.object(preview, "verify"), patch.object(preview, "api", side_effect=fake_api), \
                        patch.object(preview, "gh", side_effect=fake_gh):
                    if tamper:
                        with self.assertRaisesRegex(ValueError, "Uploaded asset differs"):
                            preview.publish(self.root, output, self.source)
                        self.assertNotIn("edit", operations)
                    else:
                        preview.publish(self.root, output, self.source)
                        self.assertLess(operations.index("download"), operations.index("edit"))
                        self.assertEqual(operations.count("create"), 0 if resume else 1)

    def test_existing_tag_cannot_be_retargeted(self):
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"head": {"repo": {"full_name": "mu23XR/AudioBridge"},
            "sha": self.source}}}), encoding="utf-8")
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
            "GITHUB_EVENT_NAME": "pull_request", "GITHUB_EVENT_PATH": str(event), "GH_TOKEN": "fixture-not-a-credential"}
        with patch.dict(os.environ, environment), patch.object(preview, "check_source"), \
                patch.object(preview, "verify"), patch.object(preview, "api", return_value={
                    "object": {"type": "commit", "sha": "b" * 40}}) as api, patch.object(preview, "gh") as network:
            with self.assertRaises(ValueError):
                preview.publish(self.root, self.root / "missing", self.source)
            self.assertEqual(api.call_count, 1)
            network.assert_not_called()

    def test_manual_dispatch_requires_explicit_publish(self):
        event = self.root / "event.json"
        event.write_text(json.dumps({"inputs": {"publish": "false"}}), encoding="utf-8")
        environment = {"GITHUB_ACTIONS": "true", "GITHUB_REPOSITORY": "mu23XR/AudioBridge",
            "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_EVENT_PATH": str(event), "GH_TOKEN": "fixture-not-a-credential"}
        with patch.dict(os.environ, environment), patch.object(preview, "gh") as network:
            with self.assertRaises(ValueError):
                preview.publish(self.root, self.root / "missing", self.source)
            network.assert_not_called()


if __name__ == "__main__":
    unittest.main()
