#!/usr/bin/env python3
"""Validate release artifacts and generate the current Xenon Mobile catalog."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from pathlib import Path


PRIMARY_MIRROR = "http://play.mindustry.men/github"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def apk_badging(path: Path, aapt2: str) -> tuple[str, int, str]:
    output = subprocess.run(
        [aapt2, "dump", "badging", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    match = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", output
    )
    if not match:
        raise RuntimeError(f"aapt2 could not read package metadata from {path}")
    return match.group(1), int(match.group(2)), match.group(3)


def apk_signers(path: Path, apksigner: str | None) -> list[str]:
    if not apksigner:
        return []
    output = subprocess.run(
        [apksigner, "verify", "--print-certs", str(path)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    return sorted(set(re.findall(r"SHA-256 digest: ([0-9A-Fa-f]{64})", output)))


def validate_apk(path: Path, expected_package: str, aapt2: str, apksigner: str | None) -> tuple[int, str, list[str]]:
    if not expected_package:
        raise RuntimeError(f"{path.name}: APK packageName is required")
    if not apksigner:
        raise RuntimeError(f"{path.name}: apksigner is required for signature validation")
    package_name, version_code, version_name = apk_badging(path, aapt2)
    if package_name != expected_package:
        raise RuntimeError(f"{path.name}: expected {expected_package}, got {package_name}")
    with zipfile.ZipFile(path) as archive:
        if not any(name.startswith("lib/arm64-v8a/") for name in archive.namelist()):
            raise RuntimeError(f"{path.name}: arm64-v8a native library is missing")
    signers = apk_signers(path, apksigner)
    if not signers:
        raise RuntimeError(f"{path.name}: no APK signer digest was reported")
    return version_code, version_name, signers


def write_metadata(args: argparse.Namespace) -> None:
    path = args.file.resolve()
    signers: list[str] = []
    version_code: int | None = None
    actual_version_name = args.version_name
    if args.backend == "apk":
        if args.version_code is None:
            raise RuntimeError("APK metadata requires --version-code")
        version_code, actual_version_name, signers = validate_apk(
            path, args.package_name, args.aapt2, args.apksigner
        )
        if version_code != args.version_code:
            raise RuntimeError(f"{path.name}: versionCode {version_code} != {args.version_code}")
        if actual_version_name != args.version_name:
            raise RuntimeError(f"{path.name}: versionName {actual_version_name} != {args.version_name}")
    else:
        with zipfile.ZipFile(path) as archive:
            if not archive.namelist():
                raise RuntimeError(f"{path.name}: JAR is empty")

    result = {
        "id": args.artifact_id,
        "filename": path.name,
        "variant": args.variant,
        "channel": args.channel,
        "backend": args.backend,
        "slot": args.slot,
        "versionName": args.version_name,
        "versionCode": version_code,
        "build": args.build,
        "buildType": args.build_type,
        "javaVersion": args.java_version,
        "sha256": sha256(path),
        "size": path.stat().st_size,
        "nativeProfile": "arm64-v8a",
        "mgVersion": args.mg_version,
        "minLauncherVersion": 1,
        "changelog": args.changelog,
        "packageName": args.package_name if args.backend == "apk" else None,
        "sourceRepo": args.source_repo,
        "sourceCommit": args.source_commit,
        "releaseTag": args.release_tag,
        "signatureSha256": signers,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def identity(artifact: dict) -> str:
    suffix = f":{artifact['slot']}" if artifact.get("backend") == "apk" else ""
    return f"{artifact['variant']}:{artifact['backend']}{suffix}"


def generate_catalog(args: argparse.Namespace) -> None:
    metadata = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(args.metadata_dir.glob("*.json"))]
    if not metadata:
        raise RuntimeError("No artifact metadata was generated")
    previous: dict[str, dict] = {}
    if args.previous and args.previous.is_file():
        previous_root = json.loads(args.previous.read_text(encoding="utf-8"))
        previous = {identity(item): item for item in previous_root.get("artifacts", [])}

    base = f"{PRIMARY_MIRROR}/repos/DeterMination-Wind/Xenon-Mobile/releases/download/{args.release_tag}"
    artifacts = []
    for item in metadata:
        key = identity(item)
        old = previous.get(key)
        if old:
            if item["backend"] == "apk" and int(item["versionCode"]) <= int(old.get("versionCode", 0)):
                raise RuntimeError(f"{key}: versionCode must increase beyond {old.get('versionCode')}")
            if item["backend"] == "jar" and int(item["build"]) <= int(old.get("build", 0)):
                raise RuntimeError(f"{key}: build must increase beyond {old.get('build')}")
        artifact = dict(item)
        artifact["urls"] = [f"{base}/{item['filename']}"]
        artifact.pop("filename", None)
        artifacts.append(artifact)

    catalog = {
        "schemaVersion": 1,
        "variants": ["vanilla", "be", "mindustryx"],
        "channels": ["stable", "be", "dev"],
        "mirrors": [{"id": "xenon-server", "baseUrl": PRIMARY_MIRROR, "priority": 0}],
        "artifacts": sorted(artifacts, key=lambda item: identity(item)),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    metadata = sub.add_parser("metadata")
    metadata.add_argument("--file", type=Path, required=True)
    metadata.add_argument("--output", type=Path, required=True)
    metadata.add_argument("--artifact-id", required=True)
    metadata.add_argument("--variant", required=True)
    metadata.add_argument("--channel", required=True)
    metadata.add_argument("--backend", choices=["apk", "jar"], required=True)
    metadata.add_argument("--slot", type=int)
    metadata.add_argument("--version-name", required=True)
    metadata.add_argument("--version-code", type=int)
    metadata.add_argument("--build", type=int, required=True)
    metadata.add_argument("--build-type", default="stable")
    metadata.add_argument("--java-version", type=int, default=17)
    metadata.add_argument("--mg-version", default=None)
    metadata.add_argument("--changelog", default=None)
    metadata.add_argument("--package-name", default=None)
    metadata.add_argument("--source-repo", required=True)
    metadata.add_argument("--source-commit", required=True)
    metadata.add_argument("--release-tag", required=True)
    metadata.add_argument("--aapt2", default="aapt2")
    metadata.add_argument("--apksigner")
    metadata.set_defaults(function=write_metadata)

    catalog = sub.add_parser("catalog")
    catalog.add_argument("--metadata-dir", type=Path, required=True)
    catalog.add_argument("--output", type=Path, required=True)
    catalog.add_argument("--release-tag", required=True)
    catalog.add_argument("--previous", type=Path)
    catalog.set_defaults(function=generate_catalog)
    args = parser.parse_args()
    args.function(args)


if __name__ == "__main__":
    main()
