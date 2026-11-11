import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).parents[1]))
import release_stage as stage


class FakeGitHub:
    def __init__(self):
        self.release = None
        self.files = {}
        self.operations = []
        self.corrupt_download = False
        self.reference = None

    def find(self, repository, tag):
        return copy.deepcopy(self.release)

    def api(self, endpoint, optional=False, fields=None):
        if "/git/ref/tags/" in endpoint:
            return self.reference
        raise AssertionError("Unexpected read: " + endpoint)

    def gh(self, *args):
        self.operations.append(args)
        if args[:2] == ("release", "create"):
            self.release = {"id": 12, "tag_name": args[2], "draft": True, "prerelease": True,
                            "body": args[args.index("--notes") + 1], "target_commitish": args[args.index("--target") + 1],
                            "assets": [], "html_url": "https://github.com/mu23XR/AudioBridge/releases/tag/ci-stage/native/123"}
        elif args[:2] == ("release", "upload"):
            for name in args[args.index("--repo") + 2:]:
                path = Path(name)
                content = path.read_bytes()
                self.files[path.name] = content
                self.release["assets"].append({"name": path.name, "size": len(content), "state": "uploaded",
                                               "digest": "sha256:" + hashlib.sha256(content).hexdigest()})
        elif args[:2] == ("release", "download"):
            data = self.files[args[args.index("--pattern") + 1]]
            Path(args[args.index("--output") + 1]).write_bytes(data + (b"corrupt" if self.corrupt_download else b""))
        elif args[:3] == ("api", "--method", "DELETE"):
            if "/releases/" in args[3]:
                self.release = None
            elif "/git/refs/tags/" in args[3]:
                self.reference = None
            else:
                raise AssertionError(args)
        else:
            raise AssertionError(args)
        return ""


class ReleaseStageTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = "a" * 40
        event = self.root / "event.json"
        event.write_text(json.dumps({"pull_request": {"head": {
            "repo": {"full_name": stage.REPOSITORY}, "sha": self.source}}}), encoding="utf-8")
        env = {"GITHUB_ACTIONS": "true", "GH_TOKEN": "fixture-not-a-token", "GITHUB_REPOSITORY": stage.REPOSITORY,
               "GITHUB_RUN_ID": "123", "GITHUB_RUN_ATTEMPT": "1", "GITHUB_EVENT_NAME": "pull_request",
               "GITHUB_EVENT_PATH": str(event), "GITHUB_OUTPUT": str(self.root / "outputs.txt"),
               "GITHUB_STEP_SUMMARY": str(self.root / "summary.md")}
        self.environment = patch.dict(os.environ, env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.git = patch.object(stage.github, "git", return_value=self.source)
        self.git.start()
        self.addCleanup(self.git.stop)
        self.identity = stage.context(self.root, "stable", self.source)
        self.backend = FakeGitHub()
        for name, fake in (("find_release", self.backend.find), ("api", self.backend.api), ("gh_ok", self.backend.gh)):
            mock = patch.object(stage.github, name, side_effect=fake)
            mock.start()
            self.addCleanup(mock.stop)
        self.payload = self.root / "payload"
        self.payload.mkdir()
        (self.payload / "app.apk").write_bytes(b"verified package")

    def upload(self):
        stage.prepare(self.identity)
        return stage.upload(self.identity, "android", self.payload)

    def test_run_bound_transfer_and_cleanup_never_touch_product_release(self):
        manifest = self.upload()
        target = self.root / "downloaded"
        stage.download(self.identity, json.dumps(manifest), target)
        self.assertEqual((target / "app.apk").read_bytes(), b"verified package")
        self.assertIn("manifest=", (self.root / "outputs.txt").read_text(encoding="utf-8"))
        stage.finish(self.identity, "success")
        self.assertIsNone(self.backend.release)
        self.assertTrue(all("v2.0.1" not in str(call) and "--clobber" not in call for call in self.backend.operations))
        stage.remove(self.identity)  # Idempotent when a cleanup job itself is retried.

    def test_fork_or_changed_source_cannot_obtain_a_staging_identity(self):
        event = Path(os.environ["GITHUB_EVENT_PATH"])
        for repo, source in (("another/fork", self.source), (stage.REPOSITORY, "b" * 40)):
            event.write_text(json.dumps({"pull_request": {"head": {"repo": {"full_name": repo}, "sha": source}}}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Fork or changed"):
                stage.context(self.root, "stable", self.source)
        with self.assertRaisesRegex(ValueError, "checkout"):
            stage.context(self.root, "stable", "c" * 40)
        self.assertFalse(self.backend.operations)

    def test_published_foreign_or_source_changed_draft_cannot_be_deleted(self):
        for changes in ({"draft": False}, {"body": "{}"}, {"target_commitish": "b" * 40}, {"tag_name": "v2.0.1"}):
            self.backend.release = None
            stage.prepare(self.identity)
            self.backend.release.update(changes)
            before = len(self.backend.operations)
            with self.assertRaises(ValueError):
                stage.remove(self.identity)
            self.assertEqual(len(self.backend.operations), before)

    def test_wrong_source_run_scope_or_unsafe_filename_is_rejected_before_download(self):
        manifest = self.upload()
        for key, value in (("source", "b" * 40), ("run", "456"), ("scope", "preview")):
            changed = {**manifest, key: value}
            before = len(self.backend.operations)
            with self.assertRaises(ValueError):
                stage.download(self.identity, json.dumps(changed), self.root / "wrong")
            self.assertEqual(len(self.backend.operations), before)
        for name in ("../app.apk", "C:\\app.apk", "/app.apk", "file*", "--flag"):
            changed = {**manifest, "files": {name: next(iter(manifest["files"].values()))}}
            with self.assertRaises(ValueError):
                stage.download(self.identity, json.dumps(changed), self.root / "wrong")

    def test_download_corruption_and_output_collisions_preserve_local_files(self):
        manifest = self.upload()
        self.backend.corrupt_download = True
        target = self.root / "bad"
        with self.assertRaisesRegex(ValueError, "Downloaded stage"):
            stage.download(self.identity, json.dumps(manifest), target)
        self.assertFalse(target.exists())
        target.mkdir()
        (target / "app.apk").write_bytes(b"existing")
        with self.assertRaisesRegex(ValueError, "collision"):
            stage.download(self.identity, json.dumps(manifest), target)
        self.assertEqual((target / "app.apk").read_bytes(), b"existing")

    def test_signing_material_is_rejected_before_any_upload(self):
        stage.prepare(self.identity)
        (self.payload / "release.jks").write_bytes(b"fixture is not a key")
        before = len(self.backend.operations)
        with self.assertRaisesRegex(ValueError, "Signing material"):
            stage.upload(self.identity, "android", self.payload)
        self.assertEqual(len(self.backend.operations), before)

    def test_upload_is_immutable_and_consumers_use_exact_attempt_not_latest(self):
        first = self.upload()
        stage.upload(self.identity, "android", self.payload)
        self.assertEqual(sum(op[:2] == ("release", "upload") for op in self.backend.operations), 1)
        (self.payload / "app.apk").write_bytes(b"another build")
        with self.assertRaisesRegex(ValueError, "refusing replacement"):
            stage.upload(self.identity, "android", self.payload)
        os.environ["GITHUB_RUN_ATTEMPT"] = "2"
        second = stage.upload(self.identity, "android", self.payload)
        stage.download(self.identity, json.dumps(first), self.root / "first")
        stage.download(self.identity, json.dumps(second), self.root / "second")
        self.assertEqual((self.root / "first/app.apk").read_bytes(), b"verified package")
        self.assertEqual((self.root / "second/app.apk").read_bytes(), b"another build")

    def test_failure_preserves_retry_inputs_and_success_removes_them(self):
        self.upload()
        for outcome in ("failure", "cancelled", "skipped"):
            stage.finish(self.identity, outcome)
            self.assertIsNotNone(self.backend.release)
        stage.finish(self.identity, "success")
        self.assertIsNone(self.backend.release)

    def test_cleanup_checks_tag_source_before_any_release_mutation(self):
        self.upload()
        self.backend.reference = {"object": {"type": "commit", "sha": "b" * 40}}
        before = len(self.backend.operations)
        with self.assertRaisesRegex(ValueError, "another source"):
            stage.remove(self.identity)
        self.assertEqual(len(self.backend.operations), before)
        self.backend.reference["object"]["sha"] = self.source
        stage.remove(self.identity)
        self.assertIsNone(self.backend.reference)

    def test_staging_retention_skips_active_runs_and_never_selects_product_releases(self):
        for scope, days, active, expected in (("stable", 2, False, True), ("stable", 2, True, False),
                                              ("native", 2, False, False), ("native", 15, False, True)):
            identity = {**self.identity, "scope": scope}
            release = {"id": 12, "tag_name": stage.tag(identity), "draft": True, "prerelease": True,
                       "body": json.dumps(identity), "assets": [], "target_commitish": self.source,
                       "created_at": (datetime.now(timezone.utc) - timedelta(days=100)).isoformat()}
            published = {**release, "tag_name": "v2.0.1", "draft": False}
            def api(endpoint, **kwargs):
                if "/releases?" in endpoint:
                    return [release, published]
                if "/actions/runs/" in endpoint:
                    return {"repository": {"full_name": stage.REPOSITORY}, "head_sha": self.source,
                            "created_at": (datetime.now(timezone.utc) - timedelta(days=days)).isoformat(),
                            "event": "pull_request", "status": "in_progress" if active else "completed"}
                self.fail(endpoint)
            with patch.object(stage.github, "api", side_effect=api), patch.object(stage, "remove") as remove:
                stage.prune_expired()
                self.assertEqual(remove.call_count, int(expected))
                if expected:
                    remove.assert_called_once_with(identity)

    def test_native_reports_include_no_installer_or_signing_material(self):
        identity = {**self.identity, "scope": "native"}
        diagnostics = self.root / "diagnostics"
        diagnostics.mkdir()
        (diagnostics / "result.json").write_text('{"passed":true}', encoding="utf-8")
        stage.native_report(identity, diagnostics)
        self.assertEqual(len(self.backend.release["assets"]), 1)
        self.assertIn("Native diagnostics", (self.root / "summary.md").read_text(encoding="utf-8"))
        (diagnostics / "debug.apk").write_bytes(b"not a report")
        with self.assertRaisesRegex(ValueError, "must not include"):
            stage.native_report(identity, diagnostics)

    def test_live_storage_smoke_cleans_up_even_when_download_is_corrupt(self):
        identity = {**self.identity, "scope": "smoke"}
        self.backend.corrupt_download = True
        with self.assertRaisesRegex(ValueError, "Downloaded stage"):
            stage.smoke(identity, self.root)
        self.assertIsNone(self.backend.release)


if __name__ == "__main__":
    unittest.main()
