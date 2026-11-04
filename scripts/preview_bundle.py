#!/usr/bin/env python3
"""Record a clean local preview build and collect it without rewriting old bundles.

This is a consistency/audit tool, not a cryptographic build attestation. It never
reads keys, signs APKs, or creates a CI/publication claim. Pass credentials to the
build through its existing environment; command arguments are not saved.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile


PACKAGE = "io.github.mu23xr.audiobridge.test"
SIGNER = "4ddf267d25a73f0a28446c9e77293204e32f6777c63417feb7bd925040fefb63"
SCHEMA = 1


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def equal_fields(actual, expected, context):
    for key, value in expected.items():
        require(actual.get(key) == value,
                f"{context} {key} mismatch: expected {value!r}, got {actual.get(key)!r}")


def run_output(command, cwd=None):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                            encoding="utf-8", errors="replace", check=False)
    require(result.returncode == 0,
            f"{Path(command[0]).name} failed ({result.returncode}): "
            f"{result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    with path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")


def validate_source(source):
    require(isinstance(source, str) and re.fullmatch(r"[0-9a-f]{40}", source),
            "source must be an exact lowercase 40-character Git commit SHA")


def clean_head(repo, expected):
    validate_source(expected)
    actual = run_output(["git", "rev-parse", "HEAD"], repo).strip()
    require(actual == expected, f"HEAD changed or differs from requested source: {actual}")
    # Includes tracked edits and non-ignored untracked source/configuration files.
    status = run_output(["git", "status", "--porcelain", "--untracked-files=all"], repo)
    require(not status.strip(), "source tree is dirty; commit or isolate changes before building")


def inspect_apk(apk, aapt2, apksigner):
    require(apk.is_file() and not apk.is_symlink(), f"APK is missing or a symlink: {apk}")
    badging = run_output([str(aapt2), "dump", "badging", str(apk)])
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' "
                        r"versionName='([^']+)'", badging, re.MULTILINE)
    require(package is not None, "aapt2 returned no readable package/version metadata")
    certs = run_output([str(apksigner), "verify", "--print-certs", str(apk)])
    signers = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F:]+)$",
                         certs, re.MULTILINE)
    require(len(signers) == 1, "APK must have exactly one current signing certificate")
    return {
        "package": package.group(1), "versionCode": int(package.group(2)),
        "versionName": package.group(3),
        "signingCertificateSha256": signers[0].replace(":", "").lower(),
        "bytes": apk.stat().st_size, "sha256": sha256(apk),
    }


def validate_identity(identity, version, code):
    require(re.fullmatch(r"\d+\.\d+\.\d+-test\.\d+", version) is not None,
            "version must be MAJOR.MINOR.PATCH-test.N")
    require(type(code) is int and 0 < code <= 2100000000, "invalid Android versionCode")
    expected = {"package": PACKAGE, "versionName": version, "versionCode": code,
                "signingCertificateSha256": SIGNER}
    equal_fields(identity, expected, "APK")


def validate_receipt(receipt):
    require(receipt.get("schemaVersion") == SCHEMA and receipt.get("kind") == "local-preview-build",
            "unsupported build receipt")
    require(receipt.get("localBuild") is True and receipt.get("sourceCleanBefore") is True
            and receipt.get("sourceCleanAfter") is True,
            "receipt must describe a clean local build; CI claims are not accepted here")
    validate_source(receipt.get("source"))
    identity = receipt.get("apk", {})
    validate_identity(identity, identity.get("versionName", ""), identity.get("versionCode"))
    require(re.fullmatch(r"[0-9a-f]{64}", identity.get("sha256", "")) is not None,
            "receipt has no valid APK SHA-256")
    require(type(identity.get("bytes")) is int and identity["bytes"] > 0,
            "receipt has no valid APK size")


def record_build(repo, apk, receipt_path, source, version, code, command, aapt2, apksigner):
    repo, apk, receipt_path = repo.resolve(), apk.resolve(), receipt_path.resolve()
    require(apk.is_relative_to(repo), "build APK must be inside the checked source repository")
    require(not apk.exists(), "build APK already exists; use a fresh output, do not relabel an old APK")
    require(not receipt_path.exists(), "build receipt already exists; choose a new path")
    require(command, "a build command is required after --")
    clean_head(repo, source)
    started = datetime.now(timezone.utc).isoformat()
    result = subprocess.run(command, cwd=repo, check=False)
    require(result.returncode == 0, f"build failed ({result.returncode}); no receipt was created")
    clean_head(repo, source)
    require(apk.is_file() and not apk.is_symlink(), "build succeeded but did not produce a fresh APK")
    identity = inspect_apk(apk, aapt2, apksigner)
    validate_identity(identity, version, code)
    clean_head(repo, source)
    receipt = {
        "schemaVersion": SCHEMA, "kind": "local-preview-build", "localBuild": True,
        "source": source, "sourceCleanBefore": True, "sourceCleanAfter": True,
        "buildStartedAtUtc": started, "buildFinishedAtUtc": datetime.now(timezone.utc).isoformat(),
        "apk": identity,
    }
    receipt_path.parent.mkdir(parents=True, exist_ok=True)
    write_json(receipt_path, receipt)
    return receipt


def safe_name(name):
    require(isinstance(name, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name),
            "bundle asset name must be a simple filename")
    return name


def asset(path):
    return {"name": path.name, "bytes": path.stat().st_size, "sha256": sha256(path)}


def verify_bundle(directory, aapt2, apksigner):
    manifest = read_json(directory / "verification.json")
    require(manifest.get("schemaVersion") == SCHEMA,
            "unsupported bundle schema; historical APKs need a fresh verified build receipt")
    require(manifest.get("localBuild") is True and manifest.get("published") is False
            and manifest.get("release") is None,
            "bundle must be an unpublished local build, not a CI release")
    validate_source(manifest.get("source"))
    records = manifest.get("assets", [])
    require(isinstance(records, list), "manifest assets must be a list")
    names = [safe_name(entry["name"]) for entry in records]
    require(len(names) == len(set(names)), "duplicate manifest asset")
    apks = [name for name in names if name.endswith(".apk")]
    require(len(apks) == 1 and set(names) == {apks[0], "SOURCE_COMMIT.txt", "BUILD_RECEIPT.json"},
            "bundle must contain one APK, source record, and build receipt")
    expected_files = set(names) | {"verification.json", "SHA256SUMS.txt"}
    require({p.name for p in directory.iterdir()} == expected_files,
            "bundle contains missing or unrecorded files")
    require(all(p.is_file() and not p.is_symlink() for p in directory.iterdir()),
            "bundle entries must be regular files, not directories or symlinks")
    for record in records:
        path = directory / record["name"]
        require(path.is_file() and not path.is_symlink(), f"invalid asset: {record['name']}")
        equal_fields(asset(path), record, f"asset {record['name']}")
    source_record = (directory / "SOURCE_COMMIT.txt").read_text(encoding="utf-8")
    require(source_record == manifest["source"] + "\n",
            f"SOURCE_COMMIT mismatch: expected {manifest['source']!r}, got {source_record!r}")
    receipt = read_json(directory / "BUILD_RECEIPT.json")
    validate_receipt(receipt)
    equal_fields(receipt, {"source": manifest["source"]}, "build receipt")
    identity = inspect_apk(directory / apks[0], aapt2, apksigner)
    equal_fields(identity, receipt["apk"], "APK vs build receipt")
    for key in ("package", "versionCode", "versionName", "signingCertificateSha256"):
        equal_fields(manifest, {key: identity[key]}, "verification vs APK")
    sums = (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
    expected_sums = [f"{sha256(directory / name)}  {name}" for name in sorted(expected_files - {"SHA256SUMS.txt"})]
    require(sums == expected_sums, "SHA256SUMS disagrees with bundle files")
    return manifest


def collect_bundle(apk, receipt_path, destination, aapt2, apksigner):
    require(not destination.exists(), "destination exists; old bundles must not be overwritten")
    receipt = read_json(receipt_path)
    validate_receipt(receipt)
    identity = inspect_apk(apk, aapt2, apksigner)
    equal_fields(identity, receipt["apk"], "APK vs build receipt; refusing to relabel")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".preview-bundle-", dir=destination.parent))
    try:
        filename = f"AudioBridge-Android-Preview-{identity['versionName']}-localbuild.apk"
        shutil.copyfile(apk, temporary / filename)
        shutil.copyfile(receipt_path, temporary / "BUILD_RECEIPT.json")
        (temporary / "SOURCE_COMMIT.txt").write_text(receipt["source"] + "\n", encoding="utf-8", newline="\n")
        manifest = {
            "schemaVersion": SCHEMA, "localBuild": True, "published": False, "release": None,
            "source": receipt["source"],
            **{key: identity[key] for key in ("package", "versionCode", "versionName", "signingCertificateSha256")},
            "assets": [asset(temporary / name) for name in sorted((filename, "SOURCE_COMMIT.txt", "BUILD_RECEIPT.json"))],
        }
        write_json(temporary / "verification.json", manifest)
        files = sorted(path.name for path in temporary.iterdir())
        (temporary / "SHA256SUMS.txt").write_text(
            "".join(f"{sha256(temporary / name)}  {name}\n" for name in files), encoding="utf-8", newline="\n")
        verify_bundle(temporary, aapt2, apksigner)
        # Never merge into an existing directory, even if another process created it.
        require(not destination.exists(), "destination appeared during collection")
        os.rename(temporary, destination)
    except BaseException:
        shutil.rmtree(temporary)
        raise
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--aapt2", required=True, type=Path)
    parser.add_argument("--apksigner", required=True, type=Path)
    commands = parser.add_subparsers(dest="action", required=True)
    build = commands.add_parser("record-build", help="run a fresh build bracketed by clean-source checks")
    build.add_argument("--repo", type=Path, required=True)
    build.add_argument("--apk", type=Path, required=True)
    build.add_argument("--receipt", type=Path, required=True)
    build.add_argument("--source", required=True)
    build.add_argument("--version", required=True)
    build.add_argument("--code", type=int, required=True)
    build.add_argument("command", nargs=argparse.REMAINDER)
    bundle = commands.add_parser("bundle", help="atomically create a new local bundle from a receipt")
    bundle.add_argument("--apk", type=Path, required=True)
    bundle.add_argument("--receipt", type=Path, required=True)
    bundle.add_argument("--output", type=Path, required=True)
    verify = commands.add_parser("verify", help="check all local bundle records and the actual signed APK")
    verify.add_argument("directory", type=Path)
    args = parser.parse_args()
    try:
        if args.action == "record-build":
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            record_build(args.repo, args.apk, args.receipt, args.source, args.version, args.code,
                         command, args.aapt2, args.apksigner)
        elif args.action == "bundle":
            collect_bundle(args.apk, args.receipt, args.output, args.aapt2, args.apksigner)
        else:
            verify_bundle(args.directory, args.aapt2, args.apksigner)
    except (VerificationError, OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"preview bundle verification failed: {error}\n")
    print(f"{args.action}: verified local preview (not a CI release)")


if __name__ == "__main__":
    main()
