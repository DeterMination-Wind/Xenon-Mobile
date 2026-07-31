#!/usr/bin/env python3
"""Apply the reproducible Xenon clone overlay to a pinned Mindustry checkout."""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label}, found {count}")
    return text.replace(old, new, 1)


def install_clone_bridge(source: Path, manifest: Path) -> None:
    template = Path(__file__).with_name("clone-bridge-service.java")
    if not template.is_file():
        raise RuntimeError(f"Clone Bridge template is missing: {template}")
    bridge_source = source / "android" / "src" / "xenon" / "mobile" / "bridge" / "MindustryBridgeService.java"
    bridge_source.parent.mkdir(parents=True, exist_ok=True)
    bridge_source.write_text(template.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")

    manifest_text = manifest.read_text(encoding="utf-8")
    if "com.xenon.mobile.permission.MINDUSTRY_BRIDGE" not in manifest_text:
        internet_permission = re.search(
            r"(?m)^\s*<uses-permission\s+android:name=\"android\.permission\.INTERNET\"\s*/>\s*\n?",
            manifest_text,
        )
        if internet_permission is None:
            raise RuntimeError("Clone manifest has no INTERNET permission")
        permission = (
            '    <permission android:name="com.xenon.mobile.permission.MINDUSTRY_BRIDGE" '
            'android:protectionLevel="signature" />\n'
        )
        manifest_text = (
            manifest_text[: internet_permission.end()]
            + permission
            + manifest_text[internet_permission.end() :]
        )
    if "xenon.mobile.bridge.MindustryBridgeService" not in manifest_text:
        service = (
            "\n        <service android:name=\"xenon.mobile.bridge.MindustryBridgeService\""
            " android:enabled=\"true\" android:exported=\"true\""
            " android:permission=\"com.xenon.mobile.permission.MINDUSTRY_BRIDGE\" />"
        )
        manifest_text = replace_once(
            manifest_text,
            "\n    </application>",
            service + "\n    </application>",
            "clone Bridge service",
        )
    manifest.write_text(manifest_text, encoding="utf-8", newline="\n")

    launcher = source / "android" / "src" / "mindustry" / "android" / "AndroidLauncher.java"
    if not launcher.is_file():
        raise RuntimeError(f"Mindustry Android launcher is missing: {launcher}")
    launcher_text = launcher.read_text(encoding="utf-8")
    launcher_text = replace_once(
        launcher_text,
        "        checkFiles(getIntent());\n",
        "        checkFiles(getIntent());\n        handleXenonBridgeIntent(getIntent());\n",
        "clone Bridge intent hook",
    )
    launcher_text = replace_once(
        launcher_text,
        "        super.onCreate(savedInstanceState);\n",
        "        super.onCreate(savedInstanceState);\n"
        "        xenon.mobile.bridge.MindustryBridgeService.registerLauncher(this);\n",
        "clone Bridge activity registration",
    )
    method_marker = "    @Override\n    public void onRequestPermissionsResult"
    method = (
        "    private void handleXenonBridgeIntent(Intent intent){\n"
        "        if(intent == null || !\"com.xenon.mobile.bridge.JOIN\".equals(intent.getAction())) return;\n"
        "        String host = intent.getStringExtra(\"host\");\n"
        "        int port = intent.getIntExtra(\"port\", 6567);\n"
        "        if(host == null || host.trim().isEmpty() || port < 1 || port > 65535) return;\n"
        "        Core.app.post(() -> ui.join.connect(host, port));\n"
        "    }\n\n"
        "    @Override\n"
        "    protected void onNewIntent(Intent intent){\n"
        "        super.onNewIntent(intent);\n"
        "        setIntent(intent);\n"
        "        handleXenonBridgeIntent(intent);\n"
        "    }\n\n"
        "    @Override\n"
        "    protected void onDestroy(){\n"
        "        xenon.mobile.bridge.MindustryBridgeService.unregisterLauncher(this);\n"
        "        super.onDestroy();\n"
        "    }\n\n"
    )
    launcher_text = replace_once(launcher_text, method_marker, method + method_marker, "clone Bridge join handler")
    launcher.write_text(launcher_text, encoding="utf-8", newline="\n")


def apply_patch_series(source: Path, patch_dir: Path | None) -> None:
    if patch_dir is None or not patch_dir.is_dir():
        return
    for patch in sorted(patch_dir.glob("*.patch")):
        subprocess.run(
            ["git", "apply", "--whitespace=nowarn", str(patch.resolve())],
            cwd=source,
            check=True,
        )


def apply_overlay(
    source: Path,
    variant: str,
    slot: int,
    package_name: str,
    version_code: int,
    version_name: str,
    patch_dir: Path | None,
    patch_log: Path | None,
) -> None:
    apply_patch_series(source, patch_dir)
    gradle = source / "android" / "build.gradle"
    manifest = source / "android" / "AndroidManifest.xml"
    if not gradle.is_file() or not manifest.is_file():
        raise RuntimeError(f"{source} is not a Mindustry Android checkout")

    gradle_text = gradle.read_text(encoding="utf-8")
    marker = '        def versionNameResult = "$versionNumber-$versionType-${getBuildVersion().replace(" ", "-")}"\n'
    insertion = marker + (
        "        def xenonPackageName = project.findProperty(\"xenonPackageName\") ?: \"io.anuke.mindustry\"\n"
        "        def xenonVersionName = project.findProperty(\"xenonVersionName\") ?: versionNameResult\n"
        "        def xenonVersionCode = (project.findProperty(\"xenonVersionCode\") ?: 1).toInteger()\n"
    )
    gradle_text = replace_once(gradle_text, marker, insertion, "version marker")
    application_id = re.search(r'(?m)^(\s*applicationId\s*(?:=\s*)?)(["\']).*?\2\s*$', gradle_text)
    if application_id is None:
        raise RuntimeError("Expected one application id")
    gradle_text = (
        gradle_text[: application_id.start()]
        + application_id.group(1)
        + "xenonPackageName"
        + gradle_text[application_id.end() :]
    )
    gradle_text = replace_once(
        gradle_text,
        "        versionName versionNameResult",
        "        versionName xenonVersionName",
        "version name",
    )
    version_code = re.search(r"(?m)^(\s*versionCode\s*(?:=\s*)?).+$", gradle_text)
    if version_code is None:
        raise RuntimeError("Expected one version code")
    gradle_text = (
        gradle_text[: version_code.start()]
        + version_code.group(1)
        + "xenonVersionCode"
        + gradle_text[version_code.end() :]
    )
    if 'abiFilters "arm64-v8a"' not in gradle_text:
        gradle_text = replace_once(
            gradle_text,
            "        multiDexEnabled = true",
            "        multiDexEnabled = true\n\n        ndk {\n            abiFilters \"arm64-v8a\"\n        }",
            "multiDex configuration",
        )
    gradle.write_text(gradle_text, encoding="utf-8", newline="\n")

    manifest_text = manifest.read_text(encoding="utf-8")
    if "xenon.variant" not in manifest_text:
        match = re.search(r"    <application\b[^>]*>", manifest_text, re.DOTALL)
        if not match:
            raise RuntimeError("Mindustry Android manifest has no application element")
        metadata = (
            "\n        <meta-data android:name=\"xenon.variant\" android:value=\""
            + variant
            + "\" />"
            "\n        <meta-data android:name=\"xenon.backend\" android:value=\"apk\" />"
            "\n        <meta-data android:name=\"xenon.slot\" android:value=\""
            + str(slot)
            + "\" />"
        )
        manifest_text = manifest_text[: match.end()] + metadata + manifest_text[match.end() :]
        manifest.write_text(manifest_text, encoding="utf-8", newline="\n")

    install_clone_bridge(source, manifest)

    if patch_log is not None:
        patch_log.parent.mkdir(parents=True, exist_ok=True)
        untracked = subprocess.run(
            ["git", "ls-files", "--others", "--exclude-standard", "-z"],
            cwd=source,
            check=True,
            capture_output=True,
        ).stdout.split(b"\0")
        untracked_paths = [path.decode("utf-8") for path in untracked if path]
        if untracked_paths:
            subprocess.run(
                ["git", "add", "--intent-to-add", "--", *untracked_paths],
                cwd=source,
                check=True,
            )
        diff = subprocess.run(
            ["git", "diff", "--binary", "--no-ext-diff"],
            cwd=source,
            check=True,
            capture_output=True,
        ).stdout
        patch_log.write_bytes(diff)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--variant", required=True)
    parser.add_argument("--slot", type=int, required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--version-code", type=int, required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--patch-dir", type=Path)
    parser.add_argument("--patch-log", type=Path)
    args = parser.parse_args()
    apply_overlay(
        args.source.resolve(),
        args.variant,
        args.slot,
        args.package_name,
        args.version_code,
        args.version_name,
        args.patch_dir.resolve() if args.patch_dir else None,
        args.patch_log.resolve() if args.patch_log else None,
    )


if __name__ == "__main__":
    main()
