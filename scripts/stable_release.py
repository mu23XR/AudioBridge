"""Verify exact-source stable assets and publish only a verified immutable bundle."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import sys
import tarfile
import tempfile
import zipfile

sys.dont_write_bytecode = True
import desktop_preview as transport
from preview_bundle import inspect_apk

VERSION = re.compile(r"\d+\.\d+\.\d+")
SOURCE = re.compile(r"[0-9a-f]{40}")
PACKAGE = "io.github.mu23xr.audiobridge"
SIGNER = "4ddf267d25a73f0a28446c9e77293204e32f6777c63417feb7bd925040fefb63"
GENERATED = transport.GENERATED


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def identity(version, source):
    require(isinstance(version, str) and VERSION.fullmatch(version), "Stable version must be MAJOR.MINOR.PATCH")
    require(isinstance(source, str) and SOURCE.fullmatch(source), "Expected an exact source commit")


def source_identity(root, source, generated=False):
    require(transport.git(root, "rev-parse", "HEAD") == source, "Checkout differs from release source")
    changed = set(transport.git(root, "diff", "--name-only", "HEAD").splitlines())
    require(not changed - (GENERATED if generated else set()), "Unexpected source changes during build")
    require(not transport.git(root, "ls-files", "--others", "--exclude-standard"), "Untracked source files during build")
    return {"sourceCommit": source, "androidTree": transport.git(root, "rev-parse", "HEAD:android"),
            "desktopTree": transport.git(root, "rev-parse", "HEAD:desktop")}


def release_request(root, event, event_name, source, repository, ref_name="", inputs=None):
    inputs = inputs or {}
    if event_name == "pull_request":
        pr = event["pull_request"]
        require(pr["head"]["repo"]["full_name"] == repository and pr["head"]["sha"] == source,
                "Fork or changed PR source cannot request a stable release")
        path = root / "docs/releases/stable-build-request.json"
        require(path.stat().st_size <= 4096, "Stable request is too large")
        data = read_json(path)
        require(isinstance(data, dict) and set(data) == {"version", "publish"}
                and type(data["publish"]) is bool, "Invalid stable release request")
        version, publish = data["version"], data["publish"]
    elif event_name == "push":
        require(ref_name.startswith("v"), "Stable release requires a version tag")
        version, publish = ref_name[1:], True
    else:
        require(event_name == "workflow_dispatch", "Unsupported stable release event")
        tag = str(inputs.get("release_tag", ""))
        version = inputs.get("version") or tag.removeprefix("v")
        publish = str(inputs.get("publish", "false")).lower() == "true"
    identity(version, source)
    require(repository == "mu23XR/AudioBridge", "Unexpected release repository")
    notes = root / "docs/releases" / (version + ".md")
    require(notes.is_file() and 0 < notes.stat().st_size <= 64 * 1024, "Missing or oversized stable release notes")
    return {"version": version, "publish": publish}


def prepare(root):
    source = transport.git(root, "rev-parse", "HEAD")
    source_identity(root, source)
    event = read_json(Path(os.environ["GITHUB_EVENT_PATH"]))
    request = release_request(root, event, os.environ["GITHUB_EVENT_NAME"], source,
                              os.environ["GITHUB_REPOSITORY"], os.environ.get("GITHUB_REF_NAME", ""),
                              event.get("inputs", {}))
    now = datetime.now(timezone.utc)
    code = (now.year - 2020) * 100_000_000 + int(now.strftime("%m%d%H%M"))
    require(0 <= now.year - 2020 <= 20 and 0 < code <= 2_100_000_000, "Android versionCode outside supported range")
    result = {"source": source, "version": request["version"], "code": str(code),
              "publish": str(request["publish"]).lower()}
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write("".join(key + "=" + value + "\n" for key, value in result.items()))
    print(json.dumps(result))


def validate_android(actual, version, code):
    require(type(code) is int and 0 < code <= 2_100_000_000, "Invalid Android versionCode")
    expected = {"package": PACKAGE, "versionName": version, "versionCode": code,
                "signingCertificateSha256": SIGNER}
    for key, value in expected.items():
        require(actual.get(key) == value, "Android identity differs: " + key)


def collect_android(root, output, source, version, code, apk, aapt2, apksigner):
    identity(version, source)
    actual = inspect_apk(apk, aapt2, apksigner)
    validate_android(actual, version, code)
    record = source_identity(root, source)
    require(not output.exists(), "Keep the existing Android bundle intact")
    output.mkdir(parents=True)
    name = "AudioBridge-Android-" + version + ".apk"
    shutil.copyfile(apk, output / name)
    write_json(output / "android-build.json", {**record, "version": version, "android": actual, "asset": name})


def safe_member(name):
    path = PurePosixPath(name)
    require("\\" not in name and not path.is_absolute() and ".." not in path.parts and
            path.parts and path.parts[0] == "AudioBridge", "Unsafe stable package member")


def check_windows_zip(path, version):
    with zipfile.ZipFile(path) as package:
        entries = package.infolist()
        require(0 < len(entries) <= 4096 and sum(e.file_size for e in entries) <= 1024 ** 3, "Unexpected ZIP bounds")
        require(len({e.filename for e in entries}) == len(entries), "Duplicate ZIP members")
        for entry in entries:
            safe_member(entry.filename)
            require((entry.external_attr >> 16) & 0o170000 != 0o120000, "ZIP symlinks are forbidden")
        names = {e.filename for e in entries}
        for name in ("AudioBridge.exe", "runtime/bin/java.exe", "wfas.cmd", "app/AudioBridge.cfg"):
            require("AudioBridge/" + name in names, "Incomplete Windows image: " + name)
        mains = [e for e in entries if re.fullmatch(r"AudioBridge/app/AudioBridge-[^/]+\.jar", e.filename)]
        require(len(mains) == 1 and mains[0].file_size <= 32 * 1024 ** 2, "Unexpected main JAR")
        with zipfile.ZipFile(io.BytesIO(package.read(mains[0]))) as jar:
            require(sum(e.file_size for e in jar.infolist()) <= 128 * 1024 ** 2, "Unexpected JAR bounds")
            properties = dict(line.split("=", 1) for line in jar.read("version.properties").decode("utf-8").splitlines()
                              if "=" in line and not line.startswith("#"))
            require(properties.get("app.display.version") == version and properties.get("app.channel") == "stable",
                    "Packaged Windows version/channel differs")
            for name in ("MainKt.class", "DesktopReceiverSessions.class", "DesktopJitterAudioPlayer.class",
                         "PcmRenderStage.class", "native/windows/x86_64/audio_engine.dll"):
                require(name in jar.namelist(), "Missing Windows implementation: " + name)
            require(version in jar.read("man/wfas.1").decode("utf-8"), "Packaged manual has stale version")
        return {entry.filename: {"bytes": entry.file_size, "sha256": hashlib.sha256(package.read(entry)).hexdigest()}
                for entry in entries if not entry.is_dir()}


def check_windows_tar(path, expected):
    files = {}
    total = 0
    with tarfile.open(path, "r:gz") as package:
        for count, entry in enumerate(package, 1):
            total += entry.size
            require(count <= 4096 and total <= 1024 ** 3, "Unexpected TAR bounds")
            safe_member(entry.name)
            require(entry.isfile() or entry.isdir(), "TAR links/special files are forbidden")
            if entry.isfile():
                require(entry.name not in files, "Duplicate TAR member")
                stream = package.extractfile(entry)
                files[entry.name] = {"bytes": entry.size, "sha256": hashlib.file_digest(stream, "sha256").hexdigest()}
    require(files == expected, "ZIP/TAR bytes differ")


def collect_windows(root, output, source, version):
    identity(version, source)
    record = source_identity(root, source, generated=True)
    stem = "AudioBridge-" + version + "-windows-x86_64"
    archives = [root / "desktop/build/packages" / (stem + suffix) for suffix in (".zip", ".tar.gz")]
    require(all(p.is_file() and not p.is_symlink() for p in archives), "Missing exact-version Windows ZIP/TAR")
    check_windows_tar(archives[1], check_windows_zip(archives[0], version))
    require(not output.exists(), "Keep the existing Windows bundle intact")
    output.mkdir(parents=True)
    for path in archives:
        shutil.copyfile(path, output / path.name)
    write_json(output / "windows-build.json", {**record, "version": version, "channel": "stable",
               "assets": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in archives}})


def bundle(root, inputs, output, source, version, code):
    identity(version, source)
    record = source_identity(root, source)
    stem = "AudioBridge-" + version + "-windows-x86_64"
    expected = {"AudioBridge-Android-" + version + ".apk", stem + ".zip", stem + ".tar.gz",
                "android-build.json", "windows-build.json"}
    require({p.name for p in inputs.iterdir()} == expected, "Unexpected or missing platform assets")
    for name in ("android-build.json", "windows-build.json"):
        actual = read_json(inputs / name)
        require(all(actual.get(key) == value for key, value in {**record, "version": version}.items()),
                "Platform source/version differs: " + name)
    validate_android(read_json(inputs / "android-build.json")["android"], version, code)
    require(not output.exists(), "Keep the existing stable bundle intact")
    output.mkdir(parents=True)
    for path in inputs.iterdir():
        require(path.is_file() and not path.is_symlink(), "Platform asset is a link or directory")
        shutil.copyfile(path, output / path.name)
    (output / "SOURCE_COMMIT.txt").write_text(source + "\n", encoding="utf-8")
    shutil.copyfile(root / "docs/releases" / (version + ".md"), output / "release-notes.md")
    write_json(output / "provenance.json", {**record, "version": version, "channel": "stable", "versionCode": code,
               "runId": os.environ.get("GITHUB_RUN_ID"), "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
               "assets": {p.name: {"bytes": p.stat().st_size, "sha256": sha256(p)} for p in output.iterdir()}})
    (output / "SHA256SUMS.txt").write_text("".join(sha256(p) + "  " + p.name + "\n"
                                         for p in sorted(output.iterdir())), encoding="utf-8")
    verify(output, source, version)


def verify(output, source, version):
    identity(version, source)
    stem = "AudioBridge-" + version + "-windows-x86_64"
    expected = {"AudioBridge-Android-" + version + ".apk", stem + ".zip", stem + ".tar.gz", "android-build.json",
                "windows-build.json", "SOURCE_COMMIT.txt", "release-notes.md", "provenance.json"}
    require({p.name for p in output.iterdir()} == expected | {"SHA256SUMS.txt"}, "Unexpected stable bundle files")
    require(all(p.is_file() and not p.is_symlink() for p in output.iterdir()), "Stable bundle contains links/directories")
    parsed = {}
    for line in (output / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        digest, name = line.split("  ", 1)
        require(re.fullmatch(r"[0-9a-f]{64}", digest) and name in expected and name not in parsed, "Invalid checksum row")
        require(sha256(output / name) == digest, "Stable checksum mismatch: " + name)
        parsed[name] = digest
    require(set(parsed) == expected, "Incomplete stable checksums")
    require((output / "SOURCE_COMMIT.txt").read_text(encoding="utf-8") == source + "\n", "Wrong stable source")
    provenance = read_json(output / "provenance.json")
    require(provenance.get("sourceCommit") == source and provenance.get("version") == version
            and provenance.get("channel") == "stable", "Wrong stable provenance")
    for platform in ("android", "windows"):
        record = read_json(output / (platform + "-build.json"))
        require(all(record.get(key) == provenance.get(key) for key in ("sourceCommit", "androidTree", "desktopTree", "version")),
                "Platform provenance differs: " + platform)
    android = read_json(output / "android-build.json")
    validate_android(android["android"], version, provenance["versionCode"])
    apk = output / ("AudioBridge-Android-" + version + ".apk")
    require(android["android"]["sha256"] == sha256(apk) and android["android"]["bytes"] == apk.stat().st_size,
            "Android build record differs from asset")
    windows = read_json(output / "windows-build.json")
    require(windows.get("channel") == "stable", "Wrong Windows build channel")
    for name in (stem + ".zip", stem + ".tar.gz"):
        require(windows["assets"][name] == {"bytes": (output / name).stat().st_size, "sha256": sha256(output / name)},
                "Windows build record differs from asset")
    require(set(provenance["assets"]) == expected - {"provenance.json"}, "Incomplete asset provenance")
    for name, asset in provenance["assets"].items():
        require(asset == {"bytes": (output / name).stat().st_size, "sha256": sha256(output / name)}, "Asset provenance differs")
    check_windows_tar(output / (stem + ".tar.gz"), check_windows_zip(output / (stem + ".zip"), version))


def publish(root, output, source, version):
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GH_TOKEN"), "Stable publication requires CI token")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    event = read_json(Path(os.environ["GITHUB_EVENT_PATH"]))
    request = release_request(root, event, os.environ["GITHUB_EVENT_NAME"], source, repository,
                              os.environ.get("GITHUB_REF_NAME", ""), event.get("inputs", {}))
    require(request == {"version": version, "publish": True}, "Stable publication was not explicitly requested")
    source_identity(root, source)
    verify(output, source, version)
    prefix, tag = "repos/" + repository, "v" + version
    reference = transport.api(prefix + "/git/ref/tags/" + tag, optional=True)
    if reference is None:
        transport.api(prefix + "/git/refs", fields={"ref": "refs/tags/" + tag, "sha": source})
    else:
        target = reference["object"]
        for _ in range(4):
            if target["type"] != "tag":
                break
            target = transport.api(prefix + "/git/tags/" + target["sha"])["object"]
        require(target["type"] == "commit" and target["sha"] == source, "Existing stable tag has another source")
    release = transport.find_release(repository, tag)
    if release is None:
        transport.gh_ok("release", "create", tag, "--repo", repository, "--verify-tag", "--target", source,
                        "--title", "AudioBridge " + version, "--notes-file", str(output / "release-notes.md"),
                        "--draft", "--prerelease", "--latest=false")
        release = transport.find_release(repository, tag)
    require(release is not None and release["draft"] and release["target_commitish"] == source,
            "Existing published release or draft source differs; refusing mutation")
    files = {p.name: p for p in output.iterdir()}
    assets = {asset["name"]: asset for asset in release["assets"]}
    require(len(assets) == len(release["assets"]) and set(assets) <= set(files), "Existing draft assets differ")
    for name, asset in assets.items():
        require(asset["size"] == files[name].stat().st_size, "Existing draft asset size differs: " + name)
    missing = set(files) - set(assets)
    if missing:
        transport.gh_ok("release", "upload", tag, "--repo", repository, *(str(files[name]) for name in sorted(missing)))
    with tempfile.TemporaryDirectory(prefix="stable-release-verify-", dir=output.parent) as directory:
        transport.gh_ok("release", "download", tag, "--repo", repository, "--dir", directory)
        downloaded = Path(directory)
        require({p.name for p in downloaded.iterdir()} == set(files), "Downloaded stable asset list differs")
        for name, path in files.items():
            require(sha256(downloaded / name) == sha256(path), "Downloaded stable asset differs: " + name)
        verify(downloaded, source, version)
    transport.gh_ok("release", "edit", tag, "--repo", repository, "--draft=false", "--prerelease=false", "--latest")
    final = transport.find_release(repository, tag)
    require(final is not None and not final["draft"] and not final["prerelease"], "Stable release promotion failed")
    latest = transport.api(prefix + "/releases/latest")
    require(latest["id"] == final["id"], "Stable release did not become latest")
    print(final["html_url"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "android", "windows", "bundle", "verify", "publish"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--source")
    parser.add_argument("--version")
    parser.add_argument("--code", type=int)
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path, default=Path("out/stable-release"))
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(args.root)
    elif args.command == "android":
        collect_android(args.root, args.output, args.source, args.version, args.code, args.apk, args.aapt2, args.apksigner)
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
