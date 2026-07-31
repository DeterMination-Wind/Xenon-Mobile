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


def inject_clone_intent_hook(text: str) -> str:
    check_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)checkFiles\([ \t]*getIntent\([ \t]*\)[ \t]*\);[ \t]*$"
    )
    check_matches = list(check_pattern.finditer(text))
    if len(check_matches) == 1:
        match = check_matches[0]
        indent = match.group("indent")
        replacement = (
            f"{indent}checkFiles(getIntent());\n"
            f"{indent}handleXenonBridgeIntent(getIntent());"
        )
        return text[: match.start()] + replacement + text[match.end() :]
    if len(check_matches) > 1:
        raise RuntimeError(f"Expected one clone Bridge intent hook, found {len(check_matches)}")

    super_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)super\.onCreate\([ \t]*savedInstanceState[ \t]*\);[ \t]*$"
    )
    super_matches = list(super_pattern.finditer(text))
    if len(super_matches) != 1:
        raise RuntimeError("Expected one AndroidLauncher onCreate super call")
    match = super_matches[0]
    indent = match.group("indent")
    replacement = (
        f"{indent}super.onCreate(savedInstanceState);\n"
        f"{indent}handleXenonBridgeIntent(getIntent());"
    )
    return text[: match.start()] + replacement + text[match.end() :]


def defer_android_native_resolution(text: str) -> str:
    old = """task copyAndroidNatives(){
    if(!localArc){
        configurations.natives.files.each{ jar ->
            copy{
                from zipTree(jar)
                into file(\"libs/\")
                include \"**\"
            }
        }
    }
}
"""
    new = """task copyAndroidNatives(){
    if(!localArc){
        doLast{
            configurations.natives.files.each{ jar ->
                copy{
                    from zipTree(jar)
                    into file(\"libs/\")
                    include \"**\"
                }
            }
        }
    }
}

def copyAndroidNativesTask = tasks.named("copyAndroidNatives")
tasks.matching{ task ->
    task.name.startsWith("merge") &&
        (task.name.endsWith("JniLibFolders") || task.name.endsWith("NativeLibs"))
}.configureEach{
    dependsOn copyAndroidNativesTask
}
"""
    if old not in text:
        return text
    return replace_once(text, old, new, "Android native dependency resolution")


def pin_android_gradle_wrapper(source: Path, variant: str) -> None:
    wrapper = source / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not wrapper.is_file():
        raise RuntimeError(f"Gradle wrapper properties are missing: {wrapper}")
    wrapper_text = wrapper.read_text(encoding="utf-8")
    pattern = re.compile(r"gradle-[0-9]+\.[0-9]+(?:\.[0-9]+)?-(?:bin|all)\.zip")
    matches = list(pattern.finditer(wrapper_text))
    if len(matches) != 1:
        raise RuntimeError(f"Expected one Gradle wrapper distribution, found {len(matches)}")
    match = matches[0]
    gradle_version = "9.3.1" if variant == "mindustryx" else "8.2.1"
    wrapper_text = (
        wrapper_text[: match.start()]
        + f"gradle-{gradle_version}-bin.zip"
        + wrapper_text[match.end() :]
    )
    wrapper.write_text(wrapper_text, encoding="utf-8", newline="\n")


def prepare_mindustryx_tools_pack(source: Path) -> None:
    build_file = source / "tools" / "build.gradle"
    if not build_file.is_file():
        raise RuntimeError(f"MindustryX tools build file is missing: {build_file}")
    text = build_file.read_text(encoding="utf-8")
    start = text.find("tasks.register('doPack')")
    if start < 0:
        start = text.find('tasks.register("doPack")')
    if start < 0:
        raise RuntimeError("MindustryX tools doPack task is missing")
    end = text.find("tasks.register('genSprites'", start)
    if end < 0:
        end = text.find('tasks.register("genSprites"', start)
    if end < 0:
        raise RuntimeError("MindustryX tools genSprites task is missing")

    block = text[start:end]
    if "project(\":core\").sourceSets.main.runtimeClasspath" not in block:
        block = replace_once(
            block,
            "classpath = sourceSets.main.runtimeClasspath",
            "classpath = files(sourceSets.main.runtimeClasspath, project(\":core\").sourceSets.main.runtimeClasspath)",
            "MindustryX ImagePacker classpath",
        )
    if 'dependsOn(":core:classes")' not in block:
        block = replace_once(
            block,
            "    dependsOn(classes)\n",
            "    dependsOn(classes)\n    dependsOn(\":core:classes\")\n",
            "MindustryX ImagePacker core dependency",
        )
    build_file.write_text(text[:start] + block + text[end:], encoding="utf-8", newline="\n")


def disable_android_minification(text: str) -> str:
    text = re.sub(
        r"(?m)^(?P<indent>[ \t]*)minifyEnabled\s*=\s*true\s*$",
        r"\g<indent>minifyEnabled = false",
        text,
    )
    return re.sub(
        r"(?m)^(?P<indent>[ \t]*)shrinkResources\s*=\s*true\s*$",
        r"\g<indent>shrinkResources = false",
        text,
    )


def inject_clone_new_intent_hook(text: str, method_marker: str) -> str:
    super_pattern = re.compile(
        r"(?m)^(?P<indent>[ \t]*)super\.onNewIntent\([ \t]*intent[ \t]*\);[ \t]*$"
    )
    super_matches = list(super_pattern.finditer(text))
    if len(super_matches) == 1:
        match = super_matches[0]
        indent = match.group("indent")
        replacement = (
            f"{indent}super.onNewIntent(intent);\n"
            f"{indent}setIntent(intent);\n"
            f"{indent}handleXenonBridgeIntent(intent);"
        )
        return text[: match.start()] + replacement + text[match.end() :]
    if len(super_matches) > 1:
        raise RuntimeError(f"Expected one AndroidLauncher onNewIntent super call, found {len(super_matches)}")

    declaration_pattern = re.compile(r"(?m)^[ \t]*(?:protected|public) void onNewIntent\(")
    if declaration_pattern.search(text):
        raise RuntimeError("AndroidLauncher onNewIntent has no recognizable super call")
    method = (
        "    @Override\n"
        "    protected void onNewIntent(Intent intent){\n"
        "        super.onNewIntent(intent);\n"
        "        setIntent(intent);\n"
        "        handleXenonBridgeIntent(intent);\n"
        "    }\n\n"
    )
    return replace_once(text, method_marker, method + method_marker, "clone Bridge join handler")


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
    launcher_text = inject_clone_intent_hook(launcher_text)
    launcher_text = replace_once(
        launcher_text,
        "        super.onCreate(savedInstanceState);\n",
        "        super.onCreate(savedInstanceState);\n"
        "        xenon.mobile.bridge.MindustryBridgeService.registerLauncher(this);\n",
        "clone Bridge activity registration",
    )
    method_marker = "    @Override\n    public void onRequestPermissionsResult"
    bridge_method = (
        "    private void handleXenonBridgeIntent(Intent intent){\n"
        "        if(intent == null || !\"com.xenon.mobile.bridge.JOIN\".equals(intent.getAction())) return;\n"
        "        String host = intent.getStringExtra(\"host\");\n"
        "        int port = intent.getIntExtra(\"port\", 6567);\n"
        "        if(host == null || host.trim().isEmpty() || port < 1 || port > 65535) return;\n"
        "        Core.app.post(() -> ui.join.connect(host, port));\n"
        "    }\n\n"
    )
    destroy_method = (
        "    @Override\n"
        "    protected void onDestroy(){\n"
        "        xenon.mobile.bridge.MindustryBridgeService.unregisterLauncher(this);\n"
        "        super.onDestroy();\n"
        "    }\n\n"
    )
    launcher_text = replace_once(launcher_text, method_marker, bridge_method + method_marker, "clone Bridge join handler")
    launcher_text = inject_clone_new_intent_hook(launcher_text, method_marker)
    launcher_text = replace_once(launcher_text, method_marker, destroy_method + method_marker, "clone Bridge lifecycle cleanup")
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
    if variant == "mindustryx":
        prepare_mindustryx_tools_pack(source)
    pin_android_gradle_wrapper(source, variant)
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
    gradle_text = defer_android_native_resolution(gradle_text)
    gradle_text = disable_android_minification(gradle_text)
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
    parser.add_argument("--slot", type=int)
    parser.add_argument("--package-name")
    parser.add_argument("--version-code", type=int)
    parser.add_argument("--version-name")
    parser.add_argument("--patch-dir", type=Path)
    parser.add_argument("--patch-log", type=Path)
    parser.add_argument("--prepare-tools-pack", action="store_true")
    args = parser.parse_args()
    if args.prepare_tools_pack:
        if args.variant != "mindustryx":
            parser.error("--prepare-tools-pack is only valid for the mindustryx variant")
        prepare_mindustryx_tools_pack(args.source.resolve())
        return
    missing = [
        name
        for name, value in (
            ("--slot", args.slot),
            ("--package-name", args.package_name),
            ("--version-code", args.version_code),
            ("--version-name", args.version_name),
        )
        if value is None
    ]
    if missing:
        parser.error("missing required arguments: " + ", ".join(missing))
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
