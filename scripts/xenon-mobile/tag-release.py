#!/usr/bin/env python3
"""Create the next Xenon Mobile release tag when a new upstream pin needs a release.

The daily upstream sync refreshes ``game-source-lock.json``. Publishing new clone APKs/JARs still
needs a ``v*`` tag, because that is what starts ``.github/workflows/release_ci.yml``. This script
closes that gap:

* it compares the source commits recorded on the published catalog artifacts with the current
  source lock, and only acts when some variant is genuinely stale;
* it derives the next ``v0.0.0-ci-<date>-r<N>`` name from the catalog and the remote tags;
* it refuses to tag while an earlier tag has not reached the catalog yet (release in flight or
  failed), so a run never publishes a duplicate;
* it is a dry run unless ``--write`` is passed.

Because events created with the default ``GITHUB_TOKEN`` do not start new workflow runs, the tag
push alone does not trigger ``release_ci.yml``. ``--dispatch`` therefore starts the release workflow
explicitly with ``gh workflow run --ref <tag>``; an existing run for the tag is never dispatched
twice.

Usage::

    python scripts/xenon-mobile/tag-release.py
    python scripts/xenon-mobile/tag-release.py --write
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


VARIANTS = ("vanilla", "be", "mindustryx")
CATALOG_PATH = "catalog/xenon-mobile-catalog.json"
LOCK_PATH = "game-source-lock.json"
TAG_PATTERN = re.compile(r"^v0\.0\.0-ci-(\d{8})-r(\d+)$")


def git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def remote_tags(remote: str) -> dict[str, int]:
    """Map every remote ``v0.0.0-ci-*`` tag name to its revision number."""
    tags: dict[str, int] = {}
    for line in git("ls-remote", "--tags", remote).splitlines():
        parts = line.split()
        if len(parts) != 2:
            continue
        name = parts[1].removeprefix("refs/tags/").removesuffix("^{}")
        match = TAG_PATTERN.match(name)
        if match:
            tags[name] = int(match.group(2))
    return tags


def release_runs_for(tag: str, limit: int = 20) -> bool:
    """True when a release workflow run already exists for [tag]."""
    result = subprocess.run(
        ["gh", "run", "list", "--workflow", "release_ci.yml", "--limit", str(limit), "--json", "headBranch"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        return False
    try:
        runs = json.loads(result.stdout or "[]")
    except json.JSONDecodeError:
        return False
    return any((item.get("headBranch") or "") == tag for item in runs)


def dispatch_release(tag: str) -> None:
    result = subprocess.run(
        ["gh", "workflow", "run", "release_ci.yml", "--ref", tag],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if result.returncode != 0:
        raise RuntimeError(f"gh workflow run failed: {(result.stderr or result.stdout).strip()}")


def stale_variants(catalog: dict, lock: dict) -> list[str]:
    """Return the variants whose published artifacts are built from an older pin."""
    defaults = lock.get("defaults") or {}
    artifacts = catalog.get("artifacts") or []
    stale: list[str] = []
    for variant in VARIANTS:
        pin = (defaults.get(variant) or {}).get("sourceCommit") or ""
        published = {item.get("sourceCommit") for item in artifacts if item.get("variant") == variant}
        if not pin or not published:
            continue
        if any(sha != pin for sha in published):
            stale.append(variant)
    return stale


def catalog_revision(catalog: dict) -> int:
    for artifact in catalog.get("artifacts") or []:
        match = TAG_PATTERN.match(artifact.get("releaseTag") or "")
        if match:
            return int(match.group(2))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=Path("."), help="repository root (default: current directory)")
    parser.add_argument("--remote", default="origin", help="git remote holding the release tags")
    parser.add_argument("--date", default=None, help="override the tag date (YYYYMMDD), mainly for tests")
    parser.add_argument("--force", action="store_true", help="tag even when the catalog is already current")
    parser.add_argument("--write", action="store_true", help="create and push the tag instead of printing it")
    parser.add_argument(
        "--dispatch",
        action="store_true",
        help="start release_ci.yml for the tag with gh workflow run (needed for a GITHUB_TOKEN push)",
    )
    args = parser.parse_args()

    root = args.root.resolve()
    catalog_path = root / CATALOG_PATH
    lock_path = root / LOCK_PATH
    if not catalog_path.is_file() or not lock_path.is_file():
        print(f"missing {CATALOG_PATH} or {LOCK_PATH} under {root}", file=sys.stderr)
        return 1

    try:
        catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        lock = json.loads(lock_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"cannot read the catalog or source lock: {error}", file=sys.stderr)
        return 1

    stale = stale_variants(catalog, lock)
    if not stale and not args.force:
        print("no release needed: published artifacts already match the pinned commits")
        return 0

    current = catalog_revision(catalog)
    try:
        tags = remote_tags(args.remote)
    except RuntimeError as error:
        print(f"cannot read remote tags: {error}", file=sys.stderr)
        return 1

    pending = [name for name, revision in tags.items() if revision > current]
    if pending and not args.force:
        newest = max(tags[name] for name in pending)
        print(
            f"skipping: tag r{newest} exists but the catalog is still r{current}; "
            "a release is in flight or failed"
        )
        return 0

    revision = max([current, *tags.values()]) + 1
    date = args.date or datetime.now(timezone.utc).strftime("%Y%m%d")
    tag = f"v0.0.0-ci-{date}-r{revision}"
    while tag in tags:
        revision += 1
        tag = f"v0.0.0-ci-{date}-r{revision}"

    print(f"stale variants: {', '.join(stale) if stale else 'forced'}")
    print(f"catalog revision: r{current}")
    print(f"release tag: {tag}")

    if not args.write:
        print("dry run; re-run with --write to create and push the tag")
        return 0

    status = git("status", "--porcelain").strip()
    if status:
        print("refusing to tag: the working tree has uncommitted changes", file=sys.stderr)
        print(status, file=sys.stderr)
        return 1

    git("tag", tag)
    git("push", args.remote, tag)
    print(f"pushed {tag}")

    if args.dispatch:
        if release_runs_for(tag):
            print(f"{tag} already has a release run; not dispatching again")
        else:
            try:
                dispatch_release(tag)
            except RuntimeError as error:
                print(f"tag pushed but the release dispatch failed: {error}", file=sys.stderr)
                return 1
            print(f"dispatched release_ci.yml for {tag}")
    else:
        print("release_ci.yml will build and publish it through the tag push event")
    return 0


if __name__ == "__main__":
    sys.exit(main())
