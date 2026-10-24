"""Windows preview identity, bounded bundle verification and immutable CI publication."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

VERSION = re.compile(r"\d+\.\d+\.\d+-test\.\d+(?:-(?:vpn|pc)\.\d+)?")
SOURCE = re.compile(r"[0-9a-f]{40}")
GENERATED = {"desktop/src/main/resources/native/windows/x86_64/audio_engine.dll",
             "desktop/src/main/resources/version.properties", "desktop/src/main/resources/man/wfas.1"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, encoding="utf-8").strip()


def checksum(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def request(root):
    path = root / "docs/releases/desktop-preview.json"
    require(path.stat().st_size <= 4096, "PC publication request is too large")
    data = json.loads(path.read_text(encoding="utf-8"))
    require(isinstance(data, dict) and set(data) == {"version", "publish"}, "Unexpected PC request fields")
    version = data["version"]
    require(isinstance(version, str) and VERSION.fullmatch(version), "PC version must be a supported test preview")
    require(type(data["publish"]) is bool, "publish must be a JSON boolean")
    properties = (root / "desktop/gradle.properties").read_text(encoding="utf-8")
    require(re.search(r"(?m)^desktopPreviewVersion=" + re.escape(version) + r"\s*$", properties),
            "Request and Desktop build versions differ")
    notes = root / "docs/releases" / (version + ".md")
    require(notes.is_file() and 0 < notes.stat().st_size <= 64 * 1024, "Missing or oversized preview notes")
    return data


def check_source(root, source, generated=False):
    require(SOURCE.fullmatch(source) and git(root, "rev-parse", "HEAD") == source, "Checkout source SHA differs")
    changed = set(git(root, "diff", "--name-only", "HEAD").splitlines())
    require(not (changed - (GENERATED if generated else set())), "Unexpected tracked changes during build")
    require(not git(root, "ls-files", "--others", "--exclude-standard"), "Untracked source files during build")


def safe_member(name):
    path = PurePosixPath(name)
    require("\\" not in name and not path.is_absolute() and ".." not in path.parts and
            path.parts and path.parts[0] == "AudioBridge-Test", "Unsafe package member")


def check_zip(path, version):
    with zipfile.ZipFile(path) as package:
        entries = package.infolist()
        require(0 < len(entries) <= 4096 and sum(e.file_size for e in entries) <= 1024 ** 3, "Unexpected ZIP bounds")
        require(len({e.filename for e in entries}) == len(entries), "Duplicate ZIP members")
        for entry in entries:
            safe_member(entry.filename)
            require((entry.external_attr >> 16) & 0o170000 != 0o120000, "ZIP symlinks are forbidden")
        names = {e.filename for e in entries}
        for name in ("AudioBridge-Test.exe", "runtime/bin/java.exe", "wfas.cmd", "app/AudioBridge-Test.cfg"):
            require("AudioBridge-Test/" + name in names, "Incomplete Windows image: " + name)
        mains = [e for e in entries if re.fullmatch(r"AudioBridge-Test/app/AudioBridge-[^/]+\.jar", e.filename)]
        require(len(mains) == 1 and mains[0].file_size <= 32 * 1024 ** 2, "Unexpected main JAR")
        with zipfile.ZipFile(io.BytesIO(package.read(mains[0]))) as jar:
            properties = dict(line.split("=", 1) for line in jar.read("version.properties").decode("utf-8").splitlines()
                              if "=" in line and not line.startswith("#"))
            require(properties.get("app.display.version") == version and properties.get("app.channel") == "preview",
                    "Packaged version/channel differs")
            for name in ("MainKt.class", "DesktopPeerEndpoint.class", "DesktopJitterAudioPlayer.class",
                         "native/windows/x86_64/audio_engine.dll"):
                require(name in jar.namelist(), "Missing PC implementation: " + name)
            require(version in jar.read("man/wfas.1").decode("utf-8"), "Packaged manual has stale version")
        return {entry.filename: entry.file_size for entry in entries if not entry.is_dir()}


def check_tar(path, expected_files=None):
    with tarfile.open(path, "r:gz") as package:
        count = 0
        total = 0
        files = {}
        for count, entry in enumerate(package, 1):
            total += entry.size
            require(count <= 4096 and total <= 1024 ** 3, "Unexpected TAR bounds")
            safe_member(entry.name)
            require(entry.isfile() or entry.isdir(), "TAR links/special files are forbidden")
            if entry.isfile():
                require(entry.name not in files, "Duplicate TAR file")
                files[entry.name] = entry.size
        require(count > 0, "Empty TAR image")
        if expected_files is not None:
            require(files == expected_files, "TAR and ZIP images differ")


def bundle(root, output, source):
    data = request(root)
    check_source(root, source, generated=True)
    require(not output.exists(), "Keep an existing release bundle intact")
    version = data["version"]
    stem = "AudioBridge-" + version + "-windows-x86_64"
    archives = [root / "desktop/build/packages" / (stem + suffix) for suffix in (".zip", ".tar.gz")]
    for archive in archives:
        require(archive.is_file(), "Missing generated package: " + archive.name)
    check_tar(archives[1], check_zip(archives[0], version))
    output.mkdir(parents=True)
    for archive in archives:
        shutil.copyfile(archive, output / archive.name)
    (output / "SOURCE_COMMIT.txt").write_text(source + "\n", encoding="utf-8")
    shutil.copyfile(root / "docs/releases" / (version + ".md"), output / "release-notes.md")
    provenance = {"version": version, "channel": "preview", "sourceCommit": source,
                  "desktopTree": git(root, "rev-parse", "HEAD:desktop"),
                  "runId": os.environ.get("GITHUB_RUN_ID"), "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
                  "assets": {p.name: {"bytes": p.stat().st_size, "sha256": checksum(p)} for p in archives},
                  "acceptance": "CI checks/build/identity only; cold-start cropping and physical listening remain open"}
    (output / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
    sums = "".join(checksum(p) + "  " + p.name + "\n" for p in sorted(output.iterdir()))
    (output / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
    verify(output, version, source)


def verify(output, version, source):
    require(VERSION.fullmatch(version) and SOURCE.fullmatch(source), "Invalid expected preview identity")
    stem = "AudioBridge-" + version + "-windows-x86_64"
    expected = {stem + ".zip", stem + ".tar.gz", "SOURCE_COMMIT.txt", "provenance.json", "release-notes.md"}
    require({p.name for p in output.iterdir()} == expected | {"SHA256SUMS.txt"}, "Unexpected release files")
    require(all(p.is_file() and not p.is_symlink() for p in output.iterdir()), "Release contains a link or directory")
    sums = (output / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines()
    parsed = {}
    for line in sums:
        digest, name = line.split("  ", 1)
        require(re.fullmatch(r"[0-9a-f]{64}", digest) and name in expected and name not in parsed, "Invalid checksum row")
        require(checksum(output / name) == digest, "Release checksum mismatch: " + name)
        parsed[name] = digest
    require(set(parsed) == expected, "Incomplete release checksums")
    require((output / "SOURCE_COMMIT.txt").read_text(encoding="utf-8").strip() == source, "Wrong SOURCE_COMMIT")
    provenance = json.loads((output / "provenance.json").read_text(encoding="utf-8"))
    require(provenance["version"] == version and provenance["sourceCommit"] == source and
            provenance["channel"] == "preview", "Wrong provenance")
    for name in (stem + ".zip", stem + ".tar.gz"):
        require(provenance["assets"][name] == {"bytes": (output / name).stat().st_size, "sha256": parsed[name]},
                "Archive provenance differs")
    check_tar(output / (stem + ".tar.gz"), check_zip(output / (stem + ".zip"), version))


def gh(*args):
    return subprocess.run(["gh", *args], text=True, encoding="utf-8", capture_output=True, timeout=300)


def gh_ok(*args):
    result = gh(*args)
    require(result.returncode == 0, "GitHub operation failed: " + " ".join(args[:3]) + ": " + result.stderr.strip())
    return result.stdout.strip()


def api(endpoint, optional=False, fields=None):
    args = ["api", endpoint]
    if fields:
        args += ["--method", "POST"]
        for key, value in fields.items():
            args += ["-f", key + "=" + value]
    result = gh(*args)
    try:
        data = json.loads(result.stdout)
    except ValueError as error:
        raise ValueError("GitHub returned a non-JSON API response: " + endpoint) from error
    if optional and result.returncode != 0 and isinstance(data, dict) and str(data.get("status")) == "404":
        return None
    require(result.returncode == 0, "GitHub API failed: " + endpoint + ": " + result.stderr.strip())
    return data


def publish(root, output, source):
    data = request(root)
    version = data["version"]
    require(data["publish"] and os.environ.get("GITHUB_ACTIONS") == "true", "Publication requires an explicit CI request")
    repository = os.environ.get("GITHUB_REPOSITORY", "")
    require(repository == "mu23XR/AudioBridge" and os.environ.get("GH_TOKEN"), "Wrong repository or unavailable CI token")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        require(event["pull_request"]["head"]["repo"]["full_name"] == repository and
                event["pull_request"]["head"]["sha"] == source, "Fork or changed PR source cannot publish")
    else:
        require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch" and
                str(event.get("inputs", {}).get("publish")).lower() == "true", "Manual publication was not selected")
    check_source(root, source)
    verify(output, version, source)
    tag = "v" + version
    prefix = "repos/" + repository
    reference = api(prefix + "/git/ref/tags/" + tag, optional=True)
    if reference is None:
        api(prefix + "/git/refs", fields={"ref": "refs/tags/" + tag, "sha": source})
    else:
        require(reference["object"]["type"] == "commit" and reference["object"]["sha"] == source,
                "Existing tag points to another source; refusing replacement")
    release = api(prefix + "/releases/tags/" + tag, optional=True)
    if release is None:
        gh_ok("release", "create", tag, "--repo", repository, "--verify-tag", "--target", source,
              "--title", "AudioBridge PC " + version, "--notes-file", str(output / "release-notes.md"),
              "--draft", "--prerelease", "--latest=false")
        release = api(prefix + "/releases/tags/" + tag)
    require(release["prerelease"] and release["target_commitish"] == source, "Existing release identity differs")
    files = {p.name: p for p in output.iterdir()}
    assets = {asset["name"]: asset for asset in release["assets"]}
    require(set(assets) <= set(files), "Existing release has unexpected assets")
    missing = set(files) - set(assets)
    require(release["draft"] or not missing, "Published release is incomplete; refusing mutation")
    if missing:
        gh_ok("release", "upload", tag, "--repo", repository, *(str(files[name]) for name in sorted(missing)))
    # Verify the uploaded bytes before the draft is made public. Never clobber an asset.
    with tempfile.TemporaryDirectory(prefix="pc-release-verify-", dir=output.parent) as directory:
        gh_ok("release", "download", tag, "--repo", repository, "--dir", directory)
        for name, path in files.items():
            require(checksum(Path(directory) / name) == checksum(path), "Uploaded asset differs: " + name)
    if release["draft"]:
        gh_ok("release", "edit", tag, "--repo", repository, "--draft=false", "--prerelease", "--latest=false")
    final = api(prefix + "/releases/tags/" + tag)
    require(not final["draft"] and final["prerelease"], "Release was not published as a preview")
    print(final["html_url"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "bundle", "verify", "publish"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--output", type=Path, default=Path("out/desktop-release"))
    parser.add_argument("--source")
    parser.add_argument("--version")
    parser.add_argument("--allow-publish", choices=("true", "false"), default="false")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "prepare":
        data = request(root)
        source = git(root, "rev-parse", "HEAD")
        check_source(root, source)
        result = {"source": source, "version": data["version"],
                  "publish": str(data["publish"] and args.allow_publish == "true").lower()}
        if os.environ.get("GITHUB_OUTPUT"):
            with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
                stream.write("".join(key + "=" + value + "\n" for key, value in result.items()))
            with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as stream:
                stream.write("WFAS_VERSION_NAME=" + data["version"] + "\n")
        print(json.dumps(result))
    elif args.command == "bundle":
        bundle(root, args.output.resolve(), args.source)
    elif args.command == "verify":
        verify(args.output.resolve(), args.version, args.source)
    else:
        publish(root, args.output.resolve(), args.source)


if __name__ == "__main__":
    main()
