import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("preview_bundle", Path(__file__).parents[1] / "preview_bundle.py")
bundle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bundle)


class PreviewBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "candidate.apk"
        self.apk.write_bytes(b"signed APK fixture")
        self.output = self.root / "bundle"
        self.receipt_path = self.root / "receipt.json"
        self.identity = {
            "package": bundle.PACKAGE, "versionName": "1.3.1-test.21", "versionCode": 610181200,
            "signingCertificateSha256": bundle.SIGNER,
            "bytes": self.apk.stat().st_size, "sha256": bundle.sha256(self.apk),
        }
        self.receipt = {
            "schemaVersion": 1, "kind": "local-preview-build", "localBuild": True,
            "source": "a" * 40, "sourceCleanBefore": True, "sourceCleanAfter": True,
            "apk": self.identity,
        }
        bundle.write_json(self.receipt_path, self.receipt)

    def inspector(self, apk, *_):
        return {**self.identity, "bytes": apk.stat().st_size, "sha256": bundle.sha256(apk)}

    def collect(self):
        with patch.object(bundle, "inspect_apk", side_effect=self.inspector):
            return bundle.collect_bundle(self.apk, self.receipt_path, self.output, Path("aapt2"), Path("apksigner"))

    def verify(self):
        with patch.object(bundle, "inspect_apk", side_effect=self.inspector):
            return bundle.verify_bundle(self.output, Path("aapt2"), Path("apksigner"))

    def test_collection_and_independent_verification_agree(self):
        manifest = self.collect()
        self.assertEqual(manifest, self.verify())
        self.assertIs(manifest["localBuild"], True)
        self.assertIs(manifest["published"], False)
        self.assertEqual((self.output / "SOURCE_COMMIT.txt").read_text().strip(), "a" * 40)

    def test_existing_bundle_is_never_overwritten(self):
        self.output.mkdir()
        marker = self.output / "historical.txt"
        marker.write_text("keep old evidence")
        with self.assertRaisesRegex(bundle.VerificationError, "destination exists"):
            self.collect()
        self.assertEqual(marker.read_text(), "keep old evidence")

    def test_apk_changed_after_receipt_is_rejected(self):
        self.apk.write_bytes(b"a different signed APK")
        with self.assertRaisesRegex(bundle.VerificationError, "(bytes|sha256) mismatch"):
            self.collect()
        self.assertFalse(self.output.exists())

    def test_tampered_asset_and_unrecorded_file_are_rejected(self):
        self.collect()
        apk = next(self.output.glob("*.apk"))
        original = apk.read_bytes()
        apk.write_bytes(b"changed bytes")
        with self.assertRaisesRegex(bundle.VerificationError, "(bytes|sha256) mismatch"):
            self.verify()
        apk.write_bytes(original)
        (self.output / "forgotten.apk").write_bytes(b"another asset")
        with self.assertRaisesRegex(bundle.VerificationError, "unrecorded files"):
            self.verify()

    def test_wrong_manifest_version_and_checksum_are_rejected(self):
        manifest = self.collect()
        manifest["versionCode"] += 1
        (self.output / "verification.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(bundle.VerificationError, "versionCode mismatch"):
            self.verify()
        manifest["versionCode"] -= 1
        (self.output / "verification.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(bundle.VerificationError, "SHA256SUMS"):
            self.verify()

    def test_source_disagreement_rejected_even_with_refreshed_hashes(self):
        manifest = self.collect()
        source_file = self.output / "SOURCE_COMMIT.txt"
        source_file.write_text("b" * 40 + "\n", encoding="utf-8", newline="\n")
        manifest["assets"] = [bundle.asset(self.output / entry["name"]) for entry in manifest["assets"]]
        (self.output / "verification.json").write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(bundle.VerificationError, "SOURCE_COMMIT mismatch"):
            self.verify()

    def test_bad_signer_wrong_package_and_ci_claim_are_rejected(self):
        for key, value in (("signingCertificateSha256", "0" * 64), ("package", "other.app")):
            receipt = copy.deepcopy(self.receipt)
            receipt["apk"][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(bundle.VerificationError, key + " mismatch"):
                bundle.validate_receipt(receipt)
        receipt = copy.deepcopy(self.receipt)
        receipt["localBuild"] = False
        with self.assertRaisesRegex(bundle.VerificationError, "CI claims"):
            bundle.validate_receipt(receipt)

    def test_failed_atomic_collection_leaves_no_partial_bundle(self):
        with patch.object(bundle, "inspect_apk", side_effect=self.inspector), \
                patch.object(bundle, "verify_bundle", side_effect=bundle.VerificationError("verification failed")):
            with self.assertRaisesRegex(bundle.VerificationError, "verification failed"):
                bundle.collect_bundle(self.apk, self.receipt_path, self.output, Path("aapt2"), Path("apksigner"))
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob(".preview-bundle-*")))

    def test_android_sdk_output_parsing_requires_success_and_single_signer(self):
        badging = "package: name='io.github.mu23xr.audiobridge.test' versionCode='610181200' versionName='1.3.1-test.21' platformBuildVersionName=''\n"
        certs = "Verifies\nSigner #1 certificate SHA-256 digest: " + bundle.SIGNER + "\n"
        with patch.object(bundle, "run_output", side_effect=[badging, certs]):
            self.assertEqual(bundle.inspect_apk(self.apk, "aapt2", "apksigner"), self.identity)
        with patch.object(bundle, "run_output", side_effect=[badging, certs + certs]):
            with self.assertRaisesRegex(bundle.VerificationError, "exactly one"):
                bundle.inspect_apk(self.apk, "aapt2", "apksigner")


class CleanBuildRecordTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.git("init", "-q")
        (self.repo / ".gitignore").write_text("out/\n", encoding="utf-8")
        (self.repo / "source.txt").write_text("original source", encoding="utf-8")
        self.git("add", ".")
        self.git("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-qm", "fixture")
        self.source = self.git("rev-parse", "HEAD").strip()
        (self.repo / "out").mkdir()
        self.apk = self.repo / "out" / "candidate.apk"
        self.receipt = self.repo / "out" / "receipt.json"

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.repo, text=True, stderr=subprocess.STDOUT)

    def record(self, code):
        identity = {
            "package": bundle.PACKAGE, "versionName": "1.3.1-test.21", "versionCode": 610181200,
            "signingCertificateSha256": bundle.SIGNER, "bytes": 4, "sha256": "c" * 64,
        }
        with patch.object(bundle, "inspect_apk", return_value=identity):
            return bundle.record_build(self.repo, self.apk, self.receipt, self.source, "1.3.1-test.21", 610181200,
                                       [sys.executable, "-c", code], "aapt2", "apksigner")

    def test_clean_fresh_build_records_exact_commit(self):
        receipt = self.record("from pathlib import Path; Path('out/candidate.apk').write_bytes(b'test')")
        self.assertEqual(receipt["source"], self.source)
        self.assertTrue(receipt["sourceCleanAfter"])
        self.assertTrue(self.receipt.is_file())

    def test_dirty_source_is_rejected_before_build(self):
        (self.repo / "source.txt").write_text("user changes")
        with self.assertRaisesRegex(bundle.VerificationError, "dirty"):
            self.record("raise SystemExit('must not run')")
        self.assertFalse(self.receipt.exists())

    def test_source_edit_during_build_prevents_receipt(self):
        with self.assertRaisesRegex(bundle.VerificationError, "dirty"):
            self.record("from pathlib import Path; Path('source.txt').write_text('changed'); Path('out/candidate.apk').write_bytes(b'test')")
        self.assertFalse(self.receipt.exists())

    def test_existing_apk_cannot_be_given_a_fresh_source_claim(self):
        self.apk.write_bytes(b"old build")
        with self.assertRaisesRegex(bundle.VerificationError, "already exists"):
            self.record("pass")
        self.assertFalse(self.receipt.exists())

    def test_noop_or_failed_build_cannot_create_receipt(self):
        with self.assertRaisesRegex(bundle.VerificationError, "did not produce a fresh APK"):
            self.record("pass")
        with self.assertRaisesRegex(bundle.VerificationError, "build failed \\(7\\)"):
            self.record("raise SystemExit(7)")
        self.assertFalse(self.receipt.exists())

    def test_wrong_commit_is_rejected_before_build(self):
        self.source = "a" * 40
        with self.assertRaisesRegex(bundle.VerificationError, "differs from requested source"):
            self.record("raise SystemExit('must not run')")
        self.assertFalse(self.receipt.exists())


if __name__ == "__main__":
    unittest.main()
