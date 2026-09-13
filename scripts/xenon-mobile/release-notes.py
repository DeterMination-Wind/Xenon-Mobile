#!/usr/bin/env python3
"""Render the GitHub Release body for a Xenon Mobile release.

Players could not tell which asset to install from the auto-generated notes, so the body starts
with the one file an Android player needs and then explains every other file.

Usage:
    python scripts/xenon-mobile/release-notes.py --tag v0.0.0-ci-20260913-r19 \
        --catalog catalog/xenon-mobile-catalog.json --output release-body.md
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = "DeterMination-Wind/Xenon-Mobile"
DEFAULT_MIRROR = "http://121.199.60.4/github"
VARIANT_NAMES = {"vanilla": "原版 Vanilla", "be": "Bleeding Edge", "mindustryx": "MindustryX"}


def asset_name(url: str) -> str:
    return url.split("?", 1)[0].rsplit("/", 1)[-1]


def pin_of(artifacts: list[dict], variant: str) -> str:
    commits = sorted({item.get("sourceCommit") or "" for item in artifacts if item.get("variant") == variant})
    return ", ".join(commit for commit in commits if commit)


def render(tag: str, catalog: dict, previous_catalog: dict | None) -> str:
    artifacts = catalog.get("artifacts") or []
    mirror = (catalog.get("mirrors") or [{}])[0].get("baseUrl") or DEFAULT_MIRROR
    hub = f"xenon-mobile-hub-{tag}-arm64.apk"

    apks = [item for item in artifacts if item.get("backend") == "apk"]
    jars = [item for item in artifacts if item.get("backend") == "jar"]

    lines: list[str] = []
    add = lines.append

    add(f"> **安卓玩家只需要下载一个文件：`{hub}`**（下面第 1 个文件）")
    add(f"> **Android players: download only `{hub}`** (the first file below).")
    add("")
    add("## 1. 启动器本体（安卓，必下）/ The launcher (Android, required)")
    add("")
    add("| 文件 / File | 说明 / Description |")
    add("|---|---|")
    add(f"| **`{hub}`** | Xenon Mobile 启动器（Hub），arm64-v8a，64 位 ARM 手机/平板。装好它以后可以在 Hub 里一键下载并安装下面的各个游戏版本。 |")
    add(f"| | Xenon Mobile launcher (Hub) for arm64-v8a devices. Install this first; the Hub can then download and install every game build below. |")
    add("")

    if apks:
        add("## 2. 版本隔离的游戏实例（可选）/ Isolated game instances (optional)")
        add("")
        add("这些是已经做好版本隔离（独立包名、独立数据）的 Mindustry 客户端。Hub 里可以一键安装；也可以在这里单独下载、手动安装，它们会和原版 Mindustry 共存。")
        add("")
        add("These builds already have isolated package names and data. Install them from the Hub, or download them here to install manually; they coexist with other Mindustry installs.")
        add("")
        add("| 文件 / File | 变体 / Variant | 包名 / Package |")
        add("|---|---|---|")
        for item in sorted(apks, key=lambda entry: (entry.get("variant") or "", entry.get("slot") or 0)):
            name = asset_name((item.get("urls") or [""])[0])
            variant = item.get("variant") or ""
            add(f"| `{name}` | {VARIANT_NAMES.get(variant, variant)} slot {item.get('slot')} | `{item.get('packageName')}` |")
        add("")

    if jars:
        add("## 3. 桌面 / JVM 游戏本体（不是安卓安装包）/ Desktop & JVM (not an Android APK)")
        add("")
        add("| 文件 / File | 说明 / Description |")
        add("|---|---|")
        for item in sorted(jars, key=lambda entry: entry.get("variant") or ""):
            name = asset_name((item.get("urls") or [""])[0])
            add(f"| `{name}` | {VARIANT_NAMES.get(item.get('variant') or '', '')} 的桌面 JAR，用于 PC / 服务端，不能直接装到手机上。 |")
        add("")

    add("## 4. 玩家不需要的文件 / Files players do not need")
    add("")
    add("`*.patch`、`xenon-mobile-source-*.zip`、`*-notices-*.zip`、`*-native-build-scripts-*.zip`、")
    add("`*-game-source-lock-*.json`、`xenon-mobile-catalog.json` —— 这些是可复现构建与审计材料，应用会自行读取目录文件。")
    add("")
    add("Those are reproducible-build and audit materials; the app fetches the catalog by itself.")
    add("")

    add("## 安装与下载 / Install & download")
    add("")
    add(f"- 下载优先走 Xenon 镜像 `{mirror}`，镜像未缓存或不可用时自动回退到本 Release（GitHub）。")
    add("- APK 使用 Android 系统安装确认；Hub 会校验包名、版本、ABI（arm64-v8a）、签名与 SHA-256 后再启动。")
    add("- Downloads use the Xenon mirror first and fall back to this GitHub Release automatically.")
    add("- APKs install through the Android confirmation flow; the Hub verifies package name, version, ABI and SHA-256 before launch.")
    add("")

    add("## 构建来源 / Built from")
    add("")
    mindustry = pin_of(artifacts, "vanilla") or pin_of(artifacts, "be")
    mindustry_x = pin_of(artifacts, "mindustryx")
    if mindustry:
        add(f"- Anuken/Mindustry `{mindustry}`（vanilla / be）")
    if mindustry_x:
        add(f"- TinyLake/MindustryX `{mindustry_x}`（mindustryx）")
    if previous_catalog:
        previous_tags = sorted({item.get("releaseTag") or "" for item in previous_catalog.get("artifacts") or []})
        previous = next((value for value in reversed(previous_tags) if value), "")
        if previous and previous != tag:
            add("")
            add(f"上次发布 / Previous release: [`{previous}`](https://github.com/{REPO}/releases/tag/{previous}) · "
                f"[改动对比 / compare](https://github.com/{REPO}/compare/{previous}...{tag})")
    add("")

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--tag", required=True, help="release tag, for example v0.0.0-ci-20260913-r19")
    parser.add_argument("--catalog", type=Path, required=True, help="catalog asset generated for this release")
    parser.add_argument("--previous", type=Path, help="catalog of the previous release (optional)")
    parser.add_argument("--output", type=Path, help="write the markdown here instead of stdout")
    args = parser.parse_args()

    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous and args.previous.is_file() else None
    body = render(args.tag, catalog, previous)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(body, encoding="utf-8", newline="\n")
    else:
        sys.stdout.write(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
