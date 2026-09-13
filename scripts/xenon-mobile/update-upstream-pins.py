#!/usr/bin/env python3
"""Prepare the version-isolation pins for the newest upstream Mindustry releases.

``game-source-lock.json`` records the exact commits the Xenon Mobile clone slots are built
from, and ``build.gradle.kts`` asserts the same commits. ``release_ci.yml`` reads the commit
from the lock at run time, so this helper never rewrites a workflow file (a push made with the
default ``GITHUB_TOKEN`` may not modify workflows).

This helper resolves the newest upstream commits with the GitHub CLI (``gh``) and rewrites the
lock plus ``build.gradle.kts``. It is a dry run unless ``--write`` is passed::

    python scripts/xenon-mobile/update-upstream-pins.py
    python scripts/xenon-mobile/update-upstream-pins.py --write

After ``--write`` commit the pins, push a ``v*`` tag and let
``.github/workflows/release_ci.yml`` build and publish the new release; the workflow then
commits the refreshed catalog back to ``main``.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path


MINDUSTRY_REPO = "Anuken/Mindustry"
MINDUSTRY_X_REPO = "TinyLake/MindustryX"
SERVER_LIST_REPO = "Anuken/MindustryServerList"
PINS = {
    "vanilla": MINDUSTRY_REPO,
    "be": MINDUSTRY_REPO,
    "mindustryx": MINDUSTRY_X_REPO,
}


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


def latest_release_commit(repo: str) -> tuple[str, str]:
    release = gh_json(f"repos/{repo}/releases/latest")
    tag = (release or {}).get("tag_name")
    if not tag:
        raise GhError(f"{repo} has no published release")
    commit = gh_json(f"repos/{repo}/commits/{tag}") or {}
    sha = commit.get("sha")
    if not sha:
        raise GhError(f"{repo}@{tag} has no commit")
    return tag, sha


def default_branch_commit(repo: str) -> str:
    commit = gh_json(f"repos/{repo}/commits/HEAD") or {}
    sha = commit.get("sha")
    if not sha:
        raise GhError(f"{repo} has no default branch commit")
    return sha


def plan_replacements(sha_by_repo: dict[str, str]) -> dict[Path, list[tuple[str, str, str]]]:
    """Return {file: [(pattern, old_sha, new_sha), ...]} for every pin written outside the lock."""
    changes: dict[Path, list[tuple[str, str, str]]] = {}

    def collect(path: Path, patterns: list[tuple[str, str | None]]) -> None:
        if not path.is_file():
            return
        text = path.read_text(encoding="utf-8")
        edits: list[tuple[str, str, str]] = []
        for pattern, new_sha in patterns:
            if not new_sha:
                continue
            for match in re.finditer(pattern, text):
                old_sha = match.group(2)
                if old_sha != new_sha:
                    edits.append((pattern, old_sha, new_sha))
        if edits:
            changes[path] = list(dict.fromkeys(edits))

    built_repos = sorted(set(PINS.values()))
    collect(
        Path("build.gradle.kts"),
        [(rf'("{re.escape(repo)}" to ")([0-9a-f]{{40}})"', sha_by_repo.get(repo)) for repo in built_repos]
        + [(r'(fixture\["sourceCommit"\] != ")([0-9a-f]{40})"', sha_by_repo.get(SERVER_LIST_REPO))],
    )
    # release_ci.yml deliberately reads the commit from game-source-lock.json, because a push made
    # with the default GITHUB_TOKEN must not modify workflow files.
    return changes


def apply_pins(sha_by_repo: dict[str, str], lock_path: Path, write: bool) -> list[str]:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    messages: list[str] = []

    defaults = lock.setdefault("defaults", {})
    for variant, repo in PINS.items():
        entry = defaults.setdefault(variant, {})
        new_sha = sha_by_repo.get(repo)
        if not new_sha:
            continue
        if entry.get("sourceCommit") != new_sha:
            messages.append(f"{lock_path}: defaults.{variant}.sourceCommit -> {new_sha}")
            entry["sourceCommit"] = new_sha
        entry.setdefault("sourceRepo", repo)

    fixtures = lock.setdefault("fixtures", {})
    server_fixture = fixtures.setdefault("serverList", {})
    new_server_sha = sha_by_repo.get(SERVER_LIST_REPO)
    if new_server_sha and server_fixture.get("sourceCommit") != new_server_sha:
        messages.append(f"{lock_path}: fixtures.serverList.sourceCommit -> {new_server_sha}")
        server_fixture["sourceCommit"] = new_server_sha

    if write:
        lock_path.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    return messages


def apply_text_edits(edits: dict[Path, list[tuple[str, str, str]]], write: bool) -> list[str]:
    messages: list[str] = []
    for path, replacements in edits.items():
        text = path.read_text(encoding="utf-8")
        for pattern, old_sha, new_sha in replacements:
            text, count = re.subn(
                pattern,
                lambda match: match.group(0).replace(match.group(2), new_sha),
                text,
            )
            if count:
                messages.append(f"{path}: {old_sha[:12]} -> {new_sha[:12]} ({count}x)")
        if write:
            path.write_text(text, encoding="utf-8")
    return messages


def current_pins(lock_path: Path) -> dict[str, str]:
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    defaults = lock.get("defaults") or {}
    fixture = ((lock.get("fixtures") or {}).get("serverList")) or {}
    pins = {repo: (defaults.get(variant) or {}).get("sourceCommit") for variant, repo in PINS.items()}
    pins[SERVER_LIST_REPO] = fixture.get("sourceCommit")
    return {repo: sha for repo, sha in pins.items() if sha}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root (default: current directory)")
    parser.add_argument("--write", action="store_true", help="apply the changes instead of printing them")
    args = parser.parse_args()

    root = args.root.resolve()
    lock_path = root / "game-source-lock.json"
    if not lock_path.is_file():
        print(f"missing {lock_path}", file=sys.stderr)
        return 2

    try:
        tag, mindustry_sha = latest_release_commit(MINDUSTRY_REPO)
        tag_x, mindustry_x_sha = latest_release_commit(MINDUSTRY_X_REPO)
        server_sha = default_branch_commit(SERVER_LIST_REPO)
    except GhError as error:
        print(f"gh data unavailable: {error}", file=sys.stderr)
        return 2

    sha_by_repo = {
        MINDUSTRY_REPO: mindustry_sha,
        MINDUSTRY_X_REPO: mindustry_x_sha,
        SERVER_LIST_REPO: server_sha,
    }
    print(f"upstream {MINDUSTRY_REPO}: {tag} -> {mindustry_sha}")
    print(f"upstream {MINDUSTRY_X_REPO}: {tag_x} -> {mindustry_x_sha}")
    print(f"upstream {SERVER_LIST_REPO}: HEAD -> {server_sha}")

    old_pins = current_pins(lock_path)
    print("current pins: " + ", ".join(f"{repo}@{sha[:12]}" for repo, sha in sorted(old_pins.items())))

    import os

    previous = Path.cwd()
    os.chdir(root)
    try:
        changes = plan_replacements(sha_by_repo)
        messages = apply_pins(sha_by_repo, Path("game-source-lock.json"), args.write)
        messages += apply_text_edits(changes, args.write)
    finally:
        os.chdir(previous)

    if not messages:
        print("pins are already up to date")
        return 0

    for message in messages:
        print(f"  {'applied' if args.write else 'would update'}: {message}")
    if not args.write:
        print("\ndry run; re-run with --write to apply, then validate with:")
        print("  ./gradlew validateXenonMobileRelease")
    return 0


if __name__ == "__main__":
    sys.exit(main())
