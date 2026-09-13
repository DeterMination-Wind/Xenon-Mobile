#!/usr/bin/env python3
"""Check whether the Xenon Mobile version-isolation repository is ready for the newest upstream.

The Xenon Mobile clone slots are built from the commits pinned in ``game-source-lock.json`` and
published as GitHub Release assets that the Hub then downloads through the mirror. This checker
uses the GitHub CLI (``gh``) to compare that chain with upstream:

* upstream Anuken/Mindustry and TinyLake/MindustryX releases versus the pinned commits;
* the pinned commits in ``game-source-lock.json``, ``build.gradle.kts`` and
  ``.github/workflows/release_ci.yml`` versus each other;
* the published Xenon Mobile release and ``catalog/xenon-mobile-catalog.json`` on ``main``;
* every catalog artifact versus the release asset name, size and SHA-256 digest;
* the source commit recorded on the published artifacts versus the current source lock;
* the mirror a device hits first versus the catalog and release assets on ``main``.

Exit codes: ``0`` everything matches, ``1`` a readiness gap was found, ``2`` gh data was
unavailable (missing CLI, offline, unauthenticated).

Usage:
    python scripts/xenon-mobile/check-upstream-readiness.py
    python scripts/xenon-mobile/check-upstream-readiness.py --json
"""

from __future__ import annotations

import argparse
import base64
import json
import shutil
import subprocess
import sys
from pathlib import Path


REMOTE_REPO = "DeterMination-Wind/Xenon-Mobile"
GRADLE_FILE = "build.gradle.kts"
DEFAULT_MIRROR = "http://121.199.60.4/github"
RELEASE_OWNER_REPO = "DeterMination-Wind/Xenon-Mobile"
WORKFLOW_FILE = ".github/workflows/release_ci.yml"
CATALOG_BRANCH = "main"
CATALOG_PATH = "catalog/xenon-mobile-catalog.json"
LOCK_PATH = "game-source-lock.json"
UPSTREAM_REPOS = {
    "vanilla": "Anuken/Mindustry",
    "be": "Anuken/Mindustry",
    "mindustryx": "TinyLake/MindustryX",
}
SERVER_LIST_REPO = "Anuken/MindustryServerList"
SERVER_LIST_FIXTURE = "serverList"

OK = "ok"
GAP = "gap"
STALE = "stale"
UNKNOWN = "unknown"


class GhError(RuntimeError):
    """Raised when the gh CLI cannot provide data."""


def gh_json(path: str):
    gh = shutil.which("gh")
    if gh is None:
        raise GhError("the GitHub CLI (gh) is not installed")
    result = subprocess.run(
        [gh, "api", path],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        message = (result.stderr or result.stdout).strip().splitlines()
        raise GhError(f"gh api {path} failed: {message[-1] if message else 'unknown error'}")
    text = result.stdout.strip()
    return json.loads(text) if text else None


def latest_release(repo: str) -> tuple[str, str]:
    release = gh_json(f"repos/{repo}/releases/latest")
    if not isinstance(release, dict) or not release.get("tag_name"):
        raise GhError(f"{repo} has no published release")
    return release["tag_name"], release.get("published_at") or ""


def commit_for_ref(repo: str, ref: str) -> str:
    data = gh_json(f"repos/{repo}/commits/{ref}")
    if not isinstance(data, dict) or not data.get("sha"):
        raise GhError(f"{repo}@{ref} has no commit")
    return data["sha"]


def compare_commits(repo: str, base: str, head: str) -> str:
    data = gh_json(f"repos/{repo}/compare/{base}...{head}")
    if not isinstance(data, dict):
        raise GhError(f"{repo} compare {base}...{head} returned no data")
    return data.get("status") or "unknown"


def load_catalog(repo: str, branch: str, path: str) -> dict:
    data = gh_json(f"repos/{repo}/contents/{path}?ref={branch}")
    if not isinstance(data, dict) or not data.get("content"):
        raise GhError(f"{repo}@{branch}:{path} is missing")
    return json.loads(base64.b64decode(data["content"]).decode("utf-8"))


def newest_release_tag(catalog: dict) -> str:
    tags = sorted({item.get("releaseTag") or "" for item in catalog.get("artifacts") or []})
    return next((tag for tag in reversed(tags) if tag), "")


def http_request(url: str, method: str = "GET", timeout: int = 25) -> tuple[int | None, bytes]:
    """Return (status, body); status is None when the host could not be reached."""
    import urllib.error
    import urllib.request

    request = urllib.request.Request(url, method=method, headers={"User-Agent": "xenon-readiness"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = b"" if method == "HEAD" else response.read(2 * 1024 * 1024)
            return response.status, body
    except urllib.error.HTTPError as error:
        return error.code, b""
    except Exception:
        return None, b""


def check_mirror(remote_catalog: dict | None, mirror: str) -> list[dict]:
    """Verify that the mirror a device hits first serves the newest catalog and release assets."""
    findings: list[dict] = []

    def add(status: str, message: str) -> None:
        findings.append({"status": status, "scope": "mirror", "message": message})

    if not remote_catalog:
        add(UNKNOWN, "the catalog on main could not be read, skipping the mirror check")
        return findings
    if not mirror:
        return findings

    base = mirror.rstrip("/")
    expected = newest_release_tag(remote_catalog)
    url = f"{base}/raw/{RELEASE_OWNER_REPO}/main/catalog/xenon-mobile-catalog.json"
    status, body = http_request(url)
    if status is None:
        add(UNKNOWN, f"{base} is unreachable")
        return findings
    if status != 200:
        add(STALE, f"{base} answers HTTP {status} for the catalog")
        return findings

    try:
        mirror_catalog = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        add(STALE, f"{base} returned an unreadable catalog: {error}")
        return findings

    served = newest_release_tag(mirror_catalog)
    if served != expected:
        add(
            STALE,
            f"{base} still serves {served or 'no release'}; main publishes {expected}. "
            "Refresh the mirror cache so devices get the newest artifacts.",
        )
        return findings

    artifact = next(
        (item for item in remote_catalog.get("artifacts") or [] if (item.get("releaseTag") or "") == expected),
        None,
    )
    if artifact:
        filename = (artifact.get("urls") or [""])[0].split("?", 1)[0].rsplit("/", 1)[-1]
        asset_url = f"{base}/repos/{RELEASE_OWNER_REPO}/releases/download/{expected}/{filename}"
        asset_status, _ = http_request(asset_url, method="HEAD")
        if asset_status is None:
            add(UNKNOWN, f"{base} is unreachable for {filename}")
        elif asset_status >= 400:
            add(STALE, f"{base} answers HTTP {asset_status} for {filename}; the release is not cached yet")
        else:
            add(OK, f"{base} serves {expected} and its release assets")

    return findings


def check_local_pins(root: Path, lock: dict) -> list[dict]:
    """Verify that the lock, build.gradle.kts and the release workflow pin the same commits."""
    import re

    findings: list[dict] = []

    def add(status: str, message: str) -> None:
        findings.append({"status": status, "scope": "pins", "message": message})

    defaults = lock.get("defaults") or {}
    fixture = ((lock.get("fixtures") or {}).get(SERVER_LIST_FIXTURE)) or {}
    wanted = {
        repo: {variant: (defaults.get(variant) or {}).get("sourceCommit") for variant in UPSTREAM_REPOS if UPSTREAM_REPOS[variant] == repo}
        for repo in set(UPSTREAM_REPOS.values())
    }

    missing = [f"{repo}:{variant}" for repo, variants in wanted.items() for variant, sha in variants.items() if not sha]
    if not fixture.get("sourceCommit"):
        missing.append(f"{SERVER_LIST_REPO}:{SERVER_LIST_FIXTURE}")
    if missing:
        add(GAP, f"the source lock has no commit for {', '.join(sorted(missing))}")
        return findings

    gradle_path = root / GRADLE_FILE
    workflow_path = root / WORKFLOW_FILE
    if not gradle_path.is_file() or not workflow_path.is_file():
        add(UNKNOWN, f"{GRADLE_FILE} or {WORKFLOW_FILE} is missing")
        return findings

    gradle = gradle_path.read_text(encoding="utf-8")
    workflow = workflow_path.read_text(encoding="utf-8")

    for repo, variants in wanted.items():
        expected = sorted(set(variants.values()))
        if len(expected) != 1:
            add(GAP, f"the source lock pins {repo} to more than one commit: {expected}")
            continue
        sha = expected[0]

        gradle_hits = {
            match.group(1)
            for match in re.finditer(rf'"{re.escape(repo)}" to "([0-9a-f]{{40}})"', gradle)
        }
        if not gradle_hits:
            add(GAP, f"{GRADLE_FILE} does not pin {repo}")
        elif gradle_hits != {sha}:
            add(GAP, f"{GRADLE_FILE} pins {repo} to {sorted(gradle_hits)} instead of {sha[:12]}")

        workflow_hits = {
            match.group(1)
            for match in re.finditer(rf"source_repo: {re.escape(repo)}, source_commit: ([0-9a-f]{{40}})", workflow)
        }
        if not workflow_hits:
            add(GAP, f"{WORKFLOW_FILE} does not build {repo}")
        elif workflow_hits != {sha}:
            add(GAP, f"{WORKFLOW_FILE} builds {repo} at {sorted(workflow_hits)} instead of {sha[:12]}")

    fixture_hits = {
        match.group(1)
        for match in re.finditer(r'fixture\["sourceCommit"\] != "([0-9a-f]{40})"', gradle)
    }
    fixture_sha = fixture.get("sourceCommit")
    if not fixture_hits:
        add(GAP, f"{GRADLE_FILE} does not validate the {SERVER_LIST_REPO} fixture pin")
    elif fixture_hits != {fixture_sha}:
        add(GAP, f"{GRADLE_FILE} validates fixture {sorted(fixture_hits)} instead of {fixture_sha[:12]}")

    if not any(item["status"] == GAP for item in findings):
        repos = ", ".join(sorted(list(wanted) + [SERVER_LIST_REPO]))
        add(OK, f"{GRADLE_FILE}, {WORKFLOW_FILE} and the source lock agree on {repos}")

    return findings


def build_report(catalog: dict, lock: dict, root: Path, mirror: str = DEFAULT_MIRROR) -> list[dict]:
    findings: list[dict] = []
    findings += check_local_pins(root, lock)

    def add(status: str, scope: str, message: str) -> None:
        findings.append({"status": status, "scope": scope, "message": message})

    # ---- upstream releases versus the source lock -------------------------
    defaults = (lock.get("defaults") or {})
    checked_repos: set[str] = set()
    for variant, repo in UPSTREAM_REPOS.items():
        pin = (defaults.get(variant) or {}).get("sourceCommit") or ""
        try:
            tag, published = latest_release(repo)
            tag_commit = commit_for_ref(repo, tag)
        except GhError as error:
            add(UNKNOWN, variant, f"{repo}: {error}")
            continue
        checked_repos.add(repo)
        if not pin:
            add(GAP, variant, f"{repo}: the source lock has no commit for {variant}")
            continue
        if pin == tag_commit:
            add(OK, variant, f"{repo} {tag} ({published}) is the pinned commit")
            continue
        try:
            status = compare_commits(repo, pin, tag)
        except GhError as error:
            add(UNKNOWN, variant, f"{repo}: {error}")
            continue
        if status in {"ahead", "diverged"}:
            add(
                GAP,
                variant,
                f"{repo} {tag} ({published}) is newer than the pinned {pin[:12]}; "
                f"build and publish a Xenon Mobile release for it",
            )
        else:
            add(OK, variant, f"{repo}: pinned {pin[:12]} is at or after {tag}")

    # ---- server list fixture ---------------------------------------------
    fixture_pin = ((lock.get("fixtures") or {}).get(SERVER_LIST_FIXTURE) or {}).get("sourceCommit") or ""
    try:
        head = commit_for_ref(SERVER_LIST_REPO, "HEAD")
        if not fixture_pin:
            add(GAP, SERVER_LIST_FIXTURE, f"{SERVER_LIST_REPO}: the source lock has no fixture commit")
        elif head == fixture_pin:
            add(OK, SERVER_LIST_FIXTURE, f"{SERVER_LIST_REPO} fixture matches the default branch")
        else:
            add(
                STALE,
                SERVER_LIST_FIXTURE,
                f"{SERVER_LIST_REPO} default branch moved to {head[:12]} (fixture pins {fixture_pin[:12]})",
            )
    except GhError as error:
        add(UNKNOWN, SERVER_LIST_FIXTURE, str(error))

    # ---- published Xenon Mobile release and catalog ----------------------
    catalog_tag = ""
    artifacts = catalog.get("artifacts") or []
    if artifacts:
        catalog_tag = ((artifacts[0].get("releaseTag")) or "")
    try:
        remote_catalog = load_catalog(REMOTE_REPO, CATALOG_BRANCH, CATALOG_PATH)
    except GhError as error:
        add(UNKNOWN, "catalog", f"{REMOTE_REPO}: {error}")
        remote_catalog = None

    if remote_catalog is not None:
        remote_ids = {item.get("id") for item in remote_catalog.get("artifacts") or []}
        local_ids = {item.get("id") for item in artifacts}
        if remote_ids == local_ids:
            add(OK, "catalog", f"{REMOTE_REPO}@{CATALOG_BRANCH} matches the local catalog ({len(local_ids)} artifacts)")
        else:
            missing = sorted(local_ids - remote_ids)
            add(GAP, "catalog", f"{REMOTE_REPO}@{CATALOG_BRANCH} is missing catalog artifacts: {missing}")

    releases: dict[str, dict] = {}
    for artifact in artifacts:
        tag = artifact.get("releaseTag") or ""
        if not tag:
            continue
        if tag not in releases:
            try:
                releases[tag] = gh_json(f"repos/{REMOTE_REPO}/releases/tags/{tag}") or {}
            except GhError as error:
                releases[tag] = {}
                add(GAP, "release", f"{REMOTE_REPO} has no release for {tag}: {error}")
        release = releases[tag]
        assets = {asset.get("name"): asset for asset in release.get("assets") or []}
        url = (artifact.get("urls") or [""])[0]
        filename = url.split("?", 1)[0].rsplit("/", 1)[-1]
        asset = assets.get(filename)
        if asset is None:
            add(GAP, "release", f"{tag} is missing asset {filename}")
            continue
        if int(asset.get("size") or -1) != int(artifact.get("size") or -2):
            add(GAP, "release", f"{filename} size differs from the catalog")
        digest = (asset.get("digest") or "").removeprefix("sha256:").lower()
        expected = (artifact.get("sha256") or "").lower()
        if digest and digest != expected:
            add(GAP, "release", f"{filename} digest differs from the catalog")
        elif not digest:
            add(UNKNOWN, "release", f"{filename} has no digest exposed by the API")

    # ---- published artifacts versus the current source lock ---------------
    defaults = lock.get("defaults") or {}
    if artifacts:
        for variant in UPSTREAM_REPOS:
            pin = (defaults.get(variant) or {}).get("sourceCommit") or ""
            if not pin:
                continue
            shas = sorted({item.get("sourceCommit") for item in artifacts if item.get("variant") == variant})
            if not shas:
                add(UNKNOWN, "published", f"{variant}: the catalog has no artifact")
                continue
            stale = [sha for sha in shas if sha != pin]
            if stale:
                add(
                    GAP,
                    "published",
                    f"{variant}: published artifacts were built from {', '.join(sha[:12] for sha in stale)} "
                    f"but the source lock pins {pin[:12]}; publish a new Xenon Mobile release",
                )
            else:
                add(OK, "published", f"{variant}: published artifacts match the pinned {pin[:12]}")

    findings += check_mirror(remote_catalog, mirror)

    if catalog_tag:
        try:
            latest_tag, published = latest_release(REMOTE_REPO)
            if latest_tag == catalog_tag:
                add(OK, "release", f"latest Xenon Mobile release {latest_tag} ({published}) matches the catalog")
            else:
                add(
                    GAP,
                    "release",
                    f"the newest Xenon Mobile release {latest_tag} does not match the catalog tag {catalog_tag}",
                )
        except GhError as error:
            add(UNKNOWN, "release", f"{REMOTE_REPO}: {error}")

        # The launcher updater reads the `versionCode:` marker out of the Release body; without it
        # every installed app reports a failed update check.
        import re

        try:
            release = gh_json(f"repos/{REMOTE_REPO}/releases/tags/{catalog_tag}") or {}
            body = release.get("body") or ""
            if re.search(r"(?im)\bversionCode\s*[:=]\s*\d+", body):
                add(OK, "release", f"{catalog_tag} carries the versionCode marker")
            else:
                add(GAP, "release", f"{catalog_tag} has no `versionCode:` marker; the launcher would report a failed update check")
        except GhError as error:
            add(UNKNOWN, "release", f"{REMOTE_REPO}: {error}")

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root (default: current directory)")
    parser.add_argument("--catalog", type=Path, default=Path("catalog/xenon-mobile-catalog.json"))
    parser.add_argument("--lock", type=Path, default=Path(LOCK_PATH))
    parser.add_argument("--json", action="store_true", help="print the findings as JSON")
    parser.add_argument("--mirror", default=DEFAULT_MIRROR, help="mirror base to verify (default: the shipped IP mirror)")
    parser.add_argument(
        "--ignore-scopes",
        default="",
        help="comma-separated scopes that must not affect the exit code (for example 'published')",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    catalog_path = args.catalog if args.catalog.is_absolute() else root / args.catalog
    lock_path = args.lock if args.lock.is_absolute() else root / args.lock

    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except OSError as error:
        print(f"cannot read the catalog or source lock: {error}", file=sys.stderr)
        return 2

    try:
        findings = build_report(catalog, lock, root, args.mirror)
    except GhError as error:
        print(f"gh data unavailable: {error}", file=sys.stderr)
        return 2

    ignored = {scope.strip() for scope in args.ignore_scopes.split(",") if scope.strip()}
    if ignored:
        findings = [item for item in findings if item["scope"] not in ignored]

    if args.json:
        print(json.dumps(findings, indent=2, ensure_ascii=False))
    else:
        for finding in findings:
            print(f"[{finding['status']:>7}] {finding['scope']:<14} {finding['message']}")

        gaps = [item for item in findings if item["status"] == GAP]
        unknowns = [item for item in findings if item["status"] == UNKNOWN]
        print()
        if gaps:
            print(f"NOT READY: {len(gaps)} gap(s) found")
        elif unknowns:
            print("READY (with unavailable upstream data)")
        else:
            print("READY: the version-isolation repository matches the newest upstream")

    if any(item["status"] == GAP for item in findings):
        return 1
    if any(item["status"] == UNKNOWN for item in findings):
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
