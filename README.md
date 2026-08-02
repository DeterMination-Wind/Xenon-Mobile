# Xenon Mobile

[![Build](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml/badge.svg)](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml)

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md)

Xenon Mobile is an Android Hub for source-built Mindustry variants. It manages Vanilla, Bleeding Edge, and MindustryX JAR instances, fixed arm64 clone APK slots, Mindustry Profiles, isolated data, backups, and the mirror-backed server catalog.

The Hub reads the current artifact catalog from:

```text
https://play.mindustry.men/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

The catalog and release downloads are served through the Xenon mirror at `play.mindustry.men`. GitHub Releases remains the publishing backend, but the Hub does not use GitHub as a runtime download fallback. APK installation uses the Android system confirmation flow and verifies package identity, version, ABI, signature, size, and SHA-256 before launch.

## Development Build

Requirements:

- JDK 17
- Android SDK API 37
- Android build-tools 36.0.0
- Android NDK 25.2.9519653

On Windows, the local arm64 debug build is:

```powershell
.\build-xenon-mobile-debug.bat --no-daemon
```

The output is written below `ZalithLauncher/build/outputs/apk/debug/`. Local debug signing uses environment variables or ignored password files; keystores are not part of the release source tree.

Run the project checks with:

```powershell
.\gradlew validateXenonMobileRelease
.\gradlew :ZalithLauncher:testDebugUnitTest --no-daemon
```

## Release

Pushing a `v*` tag runs `.github/workflows/release_ci.yml`. It builds the Hub, 11 arm64 clone APKs, three source-locked JARs, validates artifact metadata, generates the catalog, and uploads source, patch, lock, notice, and binary assets. Release signing is restored from GitHub Actions Secrets.

The source and catalog contracts are documented in [Catalog Publishing](docs/xenon-mobile-catalog-publishing.md). The exact game commits are recorded in [game-source-lock.json](game-source-lock.json).

## License

Xenon Mobile is distributed under the [GPL-3.0 license](LICENSE). Copyright notices and third-party license notices are preserved. See [the notice directory](.github/notice) for project notices and [the catalog publishing guide](docs/xenon-mobile-catalog-publishing.md) for release details.
