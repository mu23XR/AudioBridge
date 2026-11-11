"""Pin, verify and publish the signed Android + Windows test channel."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile

sys.dont_write_bytecode = True
import desktop_preview as github
import stable_release as stable
from preview_bundle import inspect_apk, PACKAGE, SIGNER


VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+-test\.[0-9]+")
require = stable.require


def request(root):
    source = github.git(root, "rev-parse", "HEAD")
    stable.source_identity(root, source)
    require(os.environ.get("GITHUB_REPOSITORY") == "mu23XR/AudioBridge", "Unexpected preview repository")
    event = stable.read_json(Path(os.environ["GITHUB_EVENT_PATH"]))
    kind = os.environ["GITHUB_EVENT_NAME"]
    inputs = event.get("inputs", {})
    if kind == "pull_request":
        head = event["pull_request"]["head"]
        require(head["repo"]["full_name"] == "mu23XR/AudioBridge" and head["sha"] == source,
                "Fork or changed PR source cannot request a signed preview")
        path = root / "docs/releases/build-request.json"
        require(path.stat().st_size <= 4096, "Oversized preview request")
        data = stable.read_json(path)
        require(isinstance(data, dict) and set(data) == {"version", "publish", "channel"}
                and data["channel"] == "preview" and type(data["publish"]) is bool, "Invalid preview request")
        version, publish = data["version"], data["publish"]
    elif kind == "push":
        version, publish = os.environ.get("GITHUB_REF_NAME", "").removeprefix("v"), True
        require(os.environ.get("GITHUB_REF", "").startswith("refs/tags/v"), "Preview push requires a version tag")
    else:
        require(kind == "workflow_dispatch", "Unsupported preview event")
        version = str(inputs.get("version_name", "")).removeprefix("v")
        publish = str(inputs.get("publish", "false")).lower() == "true"
    require(isinstance(version, str) and VERSION.fullmatch(version), "Preview version must be MAJOR.MINOR.PATCH-test.N")
    prefix = inputs.get("artifact_name") or "AudioBridge-Android-Preview"
    require(isinstance(prefix, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", prefix), "Unsafe APK prefix")
    if publish:
        notes = root / "docs/releases" / (version + ".md")
        require(notes.is_file() and 0 < notes.stat().st_size <= 64 * 1024, "Missing or oversized preview notes")
    return {"source": source, "version": version, "publish": publish, "prefix": prefix}


def prepare(root):
    result = request(root)
    now = datetime.now(timezone.utc)
    code = (now.year - 2020) * 100_000_000 + int(now.strftime("%m%d%H%M"))
    require(0 <= now.year - 2020 <= 20 and 0 < code <= 2_100_000_000, "Unsupported Android versionCode")
    result.update(code=str(code), publish=str(result["publish"]).lower())
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write("".join(k + "=" + v + "\n" for k, v in result.items()))


def validate_android(actual, version, code):
    require(type(code) is int and 0 < code <= 2_100_000_000, "Invalid preview versionCode")
    for key, expected in {"package": PACKAGE, "versionName": version, "versionCode": code,
                          "signingCertificateSha256": SIGNER}.items():
        require(actual.get(key) == expected, "Preview APK identity differs: " + key)


def collect_android(root, output, source, version, code, apk, aapt2, apksigner, prefix):
    require(VERSION.fullmatch(version), "Invalid preview version")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", prefix), "Unsafe APK prefix")
    actual = inspect_apk(apk, aapt2, apksigner)
    validate_android(actual, version, code)
    record = stable.source_identity(root, source)
    require(not output.exists(), "Keep existing preview APK bundle intact")
    output.mkdir(parents=True)
    name = prefix + "-" + version + ".apk"
    shutil.copyfile(apk, output / name)
    stable.write_json(output / "android-build.json", {**record, "version": version, "android": actual, "asset": name})


def collect_windows(root, output, source, version):
    require(VERSION.fullmatch(version), "Invalid preview version")
    record = stable.source_identity(root, source, generated=True)
    stem = root / "desktop/build/packages" / ("AudioBridge-" + version + "-windows-x86_64")
    archive, tar = Path(str(stem) + ".zip"), Path(str(stem) + ".tar.gz")
    github.check_tar(tar, github.check_zip(archive, version))
    require(not output.exists(), "Keep existing Windows preview bundle intact")
    output.mkdir(parents=True)
    shutil.copyfile(archive, output / archive.name)
    stable.write_json(output / "windows-build.json", {**record, "version": version, "channel": "preview",
                      "asset": archive.name, "bytes": archive.stat().st_size, "sha256": github.checksum(archive)})


def bundle(root, inputs, output, source, version, code):
    record = stable.source_identity(root, source)
    android = stable.read_json(inputs / "android-build.json")
    windows = stable.read_json(inputs / "windows-build.json")
    expected = {"android-build.json", "windows-build.json", android["asset"], windows["asset"]}
    require({p.name for p in inputs.iterdir()} == expected and len(expected) == 4, "Unexpected preview platform assets")
    for name, platform in (("android", android), ("windows", windows)):
        require(all(platform.get(k) == v for k, v in {**record, "version": version}.items()),
                "Preview source/version differs: " + name)
    validate_android(android["android"], version, code)
    require(not output.exists(), "Keep existing unified preview bundle intact")
    output.mkdir(parents=True)
    for path in inputs.iterdir():
        require(path.is_file() and not path.is_symlink(), "Preview input is not a regular file")
        shutil.copyfile(path, output / path.name)
    (output / "SOURCE_COMMIT.txt").write_text(source + "\n", encoding="utf-8")
    notes = root / "docs/releases" / (version + ".md")
    if notes.is_file():
        shutil.copyfile(notes, output / "release-notes.md")
    else:
        # Nonpublishing CI checks may verify a future candidate without release notes.
        require(not request(root)["publish"], "Publishing preview requires release notes")
        (output / "release-notes.md").write_text("Unpublished validation candidate.\n", encoding="utf-8")
    stable.write_json(output / "provenance.json", {**record, "version": version, "channel": "preview", "versionCode": code})
    (output / "SHA256SUMS.txt").write_text("".join(github.checksum(p) + "  " + p.name + "\n"
        for p in sorted(output.iterdir())), encoding="utf-8")
    verify(output, source, version)


def verify(output, source, version):
    require(VERSION.fullmatch(version) and github.SOURCE.fullmatch(source), "Invalid preview identity")
    android = stable.read_json(output / "android-build.json")
    windows = stable.read_json(output / "windows-build.json")
    provenance = stable.read_json(output / "provenance.json")
    expected = {"android-build.json", "windows-build.json", "provenance.json", "SOURCE_COMMIT.txt", "release-notes.md",
                android["asset"], windows["asset"]}
    require(len(expected) == 7 and {p.name for p in output.iterdir()} == expected | {"SHA256SUMS.txt"}, "Preview assets differ")
    require(all(p.is_file() and not p.is_symlink() for p in output.iterdir()), "Preview bundle contains links/directories")
    hashes = {}
    for line in (output / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        require(name in expected and name not in hashes and re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid preview checksum row")
        require(github.checksum(output / name) == digest, "Preview checksum differs: " + name)
        hashes[name] = digest
    require(set(hashes) == expected, "Incomplete preview checksums")
    require((output / "SOURCE_COMMIT.txt").read_text(encoding="utf-8") == source + "\n"
            and provenance.get("sourceCommit") == source and provenance.get("version") == version
            and provenance.get("channel") == "preview", "Preview provenance differs")
    for platform in (android, windows):
        require(all(platform.get(k) == provenance.get(k) for k in ("sourceCommit", "androidTree", "desktopTree", "version")),
                "Preview platform source differs")
    validate_android(android["android"], version, provenance["versionCode"])
    require(android["asset"].endswith("-" + version + ".apk") and windows["asset"] == "AudioBridge-" + version + "-windows-x86_64.zip",
            "Unexpected preview package names")
    for platform, data in ((android, android["android"]), (windows, windows)):
        path = output / platform["asset"]
        require(path.stat().st_size == data["bytes"] and github.checksum(path) == data["sha256"], "Preview package differs from build record")
    require(windows["channel"] == "preview", "Wrong Windows preview channel")
    github.check_zip(output / windows["asset"], version)


def publish(root, output, source, version):
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GH_TOKEN"), "Preview publication requires CI token")
    data = request(root)
    require(data["publish"] and data["source"] == source and data["version"] == version, "Preview publication was not explicitly requested")
    verify(output, source, version)
    prefix, tag = "repos/mu23XR/AudioBridge", "v" + version
    reference = github.api(prefix + "/git/ref/tags/" + tag, optional=True)
    if reference is None:
        github.api(prefix + "/git/refs", fields={"ref": "refs/tags/" + tag, "sha": source})
    else:
        require(reference["object"]["type"] == "commit" and reference["object"]["sha"] == source, "Existing preview tag has another source")
    release = github.find_release("mu23XR/AudioBridge", tag)
    if release is None:
        github.gh_ok("release", "create", tag, "--repo", "mu23XR/AudioBridge", "--verify-tag", "--target", source,
                     "--title", "AudioBridge " + version, "--notes-file", str(output / "release-notes.md"),
                     "--draft", "--prerelease", "--latest=false")
        release = github.find_release("mu23XR/AudioBridge", tag)
    require(release and release["draft"] and release["prerelease"] and release["target_commitish"] == source,
            "Existing published preview or draft source differs; refusing mutation")
    files = {p.name: p for p in output.iterdir()}
    assets = {a["name"]: a for a in release["assets"]}
    require(len(assets) == len(release["assets"]) and set(assets) <= set(files), "Unexpected existing preview assets")
    for name, asset in assets.items():
        require(asset["size"] == files[name].stat().st_size and asset.get("digest") == "sha256:" + github.checksum(files[name]),
                "Existing preview asset differs")
    missing = set(files) - set(assets)
    if missing:
        github.gh_ok("release", "upload", tag, "--repo", "mu23XR/AudioBridge", *(str(files[n]) for n in sorted(missing)))
    with tempfile.TemporaryDirectory(prefix="preview-download-", dir=output.parent) as temporary:
        github.gh_ok("release", "download", tag, "--repo", "mu23XR/AudioBridge", "--dir", temporary)
        downloaded = Path(temporary)
        require({p.name for p in downloaded.iterdir()} == set(files), "Downloaded preview files differ")
        for name, path in files.items():
            require(github.checksum(downloaded / name) == github.checksum(path), "Downloaded preview bytes differ: " + name)
        verify(downloaded, source, version)
    github.gh_ok("release", "edit", tag, "--repo", "mu23XR/AudioBridge", "--draft=false", "--prerelease", "--latest=false")
    final = github.find_release("mu23XR/AudioBridge", tag)
    require(final and not final["draft"] and final["prerelease"], "Preview promotion failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "android", "windows", "bundle", "verify", "publish"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--source")
    parser.add_argument("--version")
    parser.add_argument("--code", type=int)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--apksigner", type=Path)
    parser.add_argument("--prefix", default="AudioBridge-Android-Preview")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.root)
    elif args.command == "android":
        collect_android(args.root, args.output, args.source, args.version, args.code, args.apk, args.aapt2, args.apksigner, args.prefix)
    elif args.command == "windows":
        collect_windows(args.root, args.output, args.source, args.version)
    elif args.command == "bundle":
        bundle(args.root, args.input, args.output, args.source, args.version, args.code)
    elif args.command == "verify":
        verify(args.output, args.source, args.version)
    else:
        publish(args.root, args.output, args.source, args.version)


if __name__ == "__main__":
    main()
