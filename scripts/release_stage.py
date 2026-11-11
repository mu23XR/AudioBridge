"""Run-bound draft Release transport; never stores packages as Actions artifacts."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
import desktop_preview as github


REPOSITORY = "mu23XR/AudioBridge"
SCOPES = {"stable", "preview", "desktop", "native", "smoke"}
NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]{0,180}")
NUMBER = re.compile(r"[1-9][0-9]{0,19}")
SCHEMA = "audiobridge-release-stage-v1"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def context(root, scope, source):
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GH_TOKEN"),
            "Release staging requires the CI token")
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY and scope in SCOPES,
            "Unexpected staging repository or scope")
    require(github.SOURCE.fullmatch(source or "") and github.git(root, "rev-parse", "HEAD") == source,
            "Staging source differs from checkout")
    run = os.environ.get("GITHUB_RUN_ID", "")
    attempt = os.environ.get("GITHUB_RUN_ATTEMPT", "")
    require(NUMBER.fullmatch(run) and NUMBER.fullmatch(attempt), "Invalid CI run identity")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        head = event["pull_request"]["head"]
        require(head["repo"]["full_name"] == REPOSITORY and head["sha"] == source,
                "Fork or changed PR source cannot write Release staging")
    else:
        require(os.environ.get("GITHUB_EVENT_NAME") in {"push", "workflow_dispatch"},
                "Unsupported staging event")
    return {"schema": SCHEMA, "repository": REPOSITORY, "scope": scope, "source": source, "run": run}


def tag(identity):
    return "ci-stage-" + identity["scope"] + "-" + identity["run"]


def validate_release(release, identity):
    require(release["tag_name"] == tag(identity) and release["draft"] and release["prerelease"]
            and release["target_commitish"] == identity["source"], "Staging is published or has another source")
    require(json.loads(release.get("body") or "null") == identity, "Staging ownership differs")
    require(type(release["id"]) is int and release["id"] > 0, "Invalid staging release ID")
    assets = release.get("assets", [])
    require(len({a["name"] for a in assets}) == len(assets), "Duplicate staging asset names")
    return release


def find(identity, missing_ok=False):
    release = github.find_release(REPOSITORY, tag(identity))
    require(release is not None or missing_ok, "Run staging is missing")
    return validate_release(release, identity) if release is not None else None


def prepare(identity):
    release = find(identity, missing_ok=True)
    if release is None:
        github.gh_ok("release", "create", tag(identity), "--repo", REPOSITORY,
                     "--target", identity["source"], "--title", "CI " + identity["scope"] + " " + identity["run"],
                     "--notes", json.dumps(identity, sort_keys=True), "--draft", "--prerelease", "--latest=false")
        release = find(identity)
    return release


def emit(name, value):
    require("\n" not in value and "\r" not in value, "Multiline CI output is forbidden")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
        stream.write(name + "=" + value + "\n")


def upload(identity, group, directory):
    require(NAME.fullmatch(group or ""), "Invalid stage group")
    require(directory.is_dir() and not directory.is_symlink(), "Missing stage directory")
    files = sorted(directory.iterdir())
    require(0 < len(files) <= 32 and all(p.is_file() and not p.is_symlink() and NAME.fullmatch(p.name) for p in files),
            "Stage payload must contain bounded regular files with safe names")
    require(not any(p.suffix.lower() in {".jks", ".keystore", ".p12", ".pfx", ".pem"} for p in files),
            "Signing material must never enter Release staging")
    require(all(0 < p.stat().st_size < 2 * 1024 ** 3 for p in files), "Stage file exceeds Release limits")
    release = find(identity)
    attempt = os.environ["GITHUB_RUN_ATTEMPT"]
    entries = {p.name: {"asset": group + "-attempt-" + attempt + "-" + p.name,
                        "bytes": p.stat().st_size, "sha256": github.checksum(p)} for p in files}
    existing = {a["name"]: a for a in release["assets"]}
    # Attempt-qualified names allow failed-job retries without clobbering another
    # job's successful output. The consumer gets this job's exact manifest.
    with tempfile.TemporaryDirectory(prefix="stage-upload-", dir=directory.parent) as temporary:
        pending = []
        for name, record in entries.items():
            if record["asset"] in existing:
                asset = existing[record["asset"]]
                require(asset["size"] == record["bytes"] and asset.get("digest") == "sha256:" + record["sha256"],
                        "Existing stage asset differs; refusing replacement")
            else:
                renamed = Path(temporary) / record["asset"]
                shutil.copyfile(directory / name, renamed)
                pending.append(str(renamed))
        if pending:
            github.gh_ok("release", "upload", tag(identity), "--repo", REPOSITORY, *pending)
    uploaded = {a["name"]: a for a in find(identity)["assets"]}
    for record in entries.values():
        asset = uploaded.get(record["asset"])
        require(asset is not None and asset["size"] == record["bytes"] and asset.get("state") == "uploaded",
                "Stage upload did not complete")
        require(asset.get("digest") == "sha256:" + record["sha256"], "Stage server checksum differs")
    manifest = {**identity, "group": group, "attempt": attempt, "files": entries}
    encoded = json.dumps(manifest, separators=(",", ":"), sort_keys=True)
    require(len(encoded) < 64 * 1024, "Stage manifest exceeds output bounds")
    emit("manifest", encoded)
    return manifest


def download(identity, encoded, output):
    require(len(encoded) < 64 * 1024, "Oversized stage manifest")
    manifest = json.loads(encoded)
    require(isinstance(manifest, dict) and set(manifest) == set(identity) | {"group", "attempt", "files"},
            "Unexpected stage manifest fields")
    require(all(manifest.get(k) == v for k, v in identity.items()), "Stage manifest source/run differs")
    group, attempt, files = manifest["group"], manifest["attempt"], manifest["files"]
    require(isinstance(group, str) and NAME.fullmatch(group) and isinstance(attempt, str) and NUMBER.fullmatch(attempt),
            "Invalid stage manifest group/attempt")
    require(isinstance(files, dict) and 0 < len(files) <= 32, "Invalid stage manifest files")
    require(not output.is_symlink() and (not output.exists() or output.is_dir()), "Unsafe stage output directory")
    assets = {a["name"]: a for a in find(identity)["assets"]}
    for name, record in files.items():
        require(NAME.fullmatch(name) and isinstance(record, dict) and set(record) == {"asset", "bytes", "sha256"},
                "Unsafe stage manifest file")
        require(record["asset"] == group + "-attempt-" + attempt + "-" + name
                and type(record["bytes"]) is int and 0 < record["bytes"] < 2 * 1024 ** 3
                and re.fullmatch(r"[0-9a-f]{64}", record["sha256"] or ""), "Invalid stage file identity")
        asset = assets.get(record["asset"])
        require(asset is not None and asset["size"] == record["bytes"] and asset.get("digest") == "sha256:" + record["sha256"],
                "Stage file metadata differs")
        require(not (output / name).exists() and not (output / name).is_symlink(), "Stage output collision")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="stage-download-", dir=output.parent) as temporary:
        for name, record in files.items():
            target = Path(temporary) / name
            github.gh_ok("release", "download", tag(identity), "--repo", REPOSITORY,
                         "--pattern", record["asset"], "--output", str(target))
            require(target.is_file() and not target.is_symlink() and target.stat().st_size == record["bytes"]
                    and github.checksum(target) == record["sha256"], "Downloaded stage bytes differ")
        # No output is installed until all files in this group passed hashing.
        output.mkdir(parents=True, exist_ok=True)
        for name in files:
            shutil.move(str(Path(temporary) / name), str(output / name))


def remove(identity):
    release = find(identity, missing_ok=True)
    prefix = "repos/" + REPOSITORY
    reference = github.api(prefix + "/git/ref/tags/" + tag(identity), optional=True)
    if reference is not None:
        require(reference["object"]["type"] == "commit" and reference["object"]["sha"] == identity["source"],
                "Staging tag points to another source")
    if release is not None:
        github.gh_ok("api", "--method", "DELETE", prefix + "/releases/" + str(release["id"]))
    if reference is not None:
        github.gh_ok("api", "--method", "DELETE", prefix + "/git/refs/tags/" + tag(identity))
    require(github.find_release(REPOSITORY, tag(identity)) is None, "Staging release cleanup failed")
    require(github.api(prefix + "/git/ref/tags/" + tag(identity), optional=True) is None, "Staging tag cleanup failed")


def prune_expired():
    """Bound abandoned package staging to one day; retain diagnostics for 14."""
    now = datetime.now(timezone.utc)
    releases = []
    for page in range(1, 101):
        batch = github.api("repos/" + REPOSITORY + "/releases?per_page=100&page=" + str(page))
        releases.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError("Release pagination exceeded diagnostic retention bounds")
    for release in releases:
        if not (release.get("draft") and release.get("prerelease") and release.get("tag_name", "").startswith("ci-stage-")):
            continue
        identity = json.loads(release.get("body") or "null")
        require(isinstance(identity, dict) and set(identity) == {"schema", "repository", "scope", "source", "run"}
                and identity["schema"] == SCHEMA and identity["repository"] == REPOSITORY and identity["scope"] in SCOPES
                and github.SOURCE.fullmatch(identity["source"]) and NUMBER.fullmatch(identity["run"]),
                "Staging retention ownership differs")
        validate_release(release, identity)
        run = github.api("repos/" + REPOSITORY + "/actions/runs/" + identity["run"])
        require(run["repository"]["full_name"] == REPOSITORY
                and (run["event"] == "workflow_dispatch" or run["head_sha"] == identity["source"]),
                "Expired staging run/source differs")
        # Release.created_at can describe the target commit, not the workflow's
        # storage lifetime. Retention is measured from the authoritative run.
        days = 14 if identity["scope"] == "native" else 1
        if datetime.fromisoformat(run["created_at"].replace("Z", "+00:00")) >= now - timedelta(days=days):
            continue
        if run["status"] == "completed":
            remove(identity)


def native_report(identity, directory):
    require(identity["scope"] == "native", "Diagnostics require their own stage scope")
    require(not directory.is_symlink(), "Native diagnostic root must not be a link")
    if not directory.is_dir():
        print("::notice::No native diagnostic files were produced; see the failed setup step.")
        return
    files = sorted(p for p in directory.rglob("*") if p.is_file())
    require(files and len(files) <= 4096 and sum(p.stat().st_size for p in files) <= 128 * 1024 ** 2,
            "Unexpected native diagnostic bounds")
    require(not any(p.is_symlink() or p.suffix.lower() in {".apk", ".aab", ".jks", ".keystore"} for p in files),
            "Native diagnostic export must not include packages, keys or links")
    release = prepare(identity)
    with tempfile.TemporaryDirectory(prefix="native-report-", dir=directory.parent) as temporary:
        archive = Path(temporary) / "native-diagnostics.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as package:
            for path in files:
                package.write(path, path.relative_to(directory).as_posix())
        upload(identity, "diagnostics", Path(temporary))
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
        summary.write("\nNative diagnostics (retained 14 days): [run-bound draft Release](" + release["html_url"] + ").\n")


def smoke(identity, root):
    require(identity["scope"] == "smoke", "Smoke check requires a separate scope")
    prepare(identity)
    try:
        (root / "out").mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="stage-smoke-", dir=root / "out") as temporary:
            directory = Path(temporary)
            payload = directory / "payload"
            payload.mkdir()
            (payload / "source.txt").write_text(identity["source"] + "\n", encoding="utf-8")
            manifest = upload(identity, "probe", payload)
            target = directory / "verified"
            download(identity, json.dumps(manifest), target)
            require((target / "source.txt").read_bytes() == (payload / "source.txt").read_bytes(), "Storage smoke differs")
        print("Release storage upload/download/checksum smoke passed; no Actions artifact used.")
    finally:
        remove(identity)


def finish(identity, outcome):
    require(outcome in {"success", "failure", "cancelled", "skipped"}, "Unknown workflow outcome")
    if outcome == "success":
        remove(identity)
    elif find(identity, missing_ok=True) is not None:
        # Preserve exact successful job outputs for GitHub's failed-job retry.
        # The next prepare/report prunes this draft after one day, only once its
        # owning run has ended. Published releases can never enter this path.
        print("::notice::Failed-run draft staging retained for retry (one-day retention): " + tag(identity))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("prepare", "upload", "download", "cleanup", "finish", "native-report", "smoke"))
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--scope", required=True, choices=sorted(SCOPES))
    parser.add_argument("--source", required=True)
    parser.add_argument("--group")
    parser.add_argument("--directory", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--manifest", default=os.environ.get("STAGE_MANIFEST", ""))
    parser.add_argument("--outcome", default=os.environ.get("STAGE_OUTCOME", ""))
    args = parser.parse_args()
    identity = context(args.root, args.scope, args.source)
    if args.command in {"prepare", "native-report", "smoke"}:
        prune_expired()
    if args.command == "prepare":
        prepare(identity)
    elif args.command == "upload":
        upload(identity, args.group, args.directory)
    elif args.command == "download":
        download(identity, args.manifest, args.output)
    elif args.command == "cleanup":
        remove(identity)
    elif args.command == "finish":
        finish(identity, args.outcome)
    elif args.command == "native-report":
        native_report(identity, args.directory)
    else:
        smoke(identity, args.root)


if __name__ == "__main__":
    main()
