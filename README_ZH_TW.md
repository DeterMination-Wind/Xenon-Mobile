# Xenon Mobile

[![建置](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml/badge.svg)](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml)

[English](README.md) | [简体中文](README_ZH_CN.md)

Xenon Mobile 是面向 Android 的 Mindustry Hub，管理源码建置的 Vanilla、Bleeding Edge 与 MindustryX JAR 实例，以及固定的 arm64 clone APK 槽位、Mindustry Profile、隔离资料、备份和伺服器镜像目录。

Hub 目前读取：

```text
http://play.mindustry.men/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

catalog 和发布档案统一通过 `play.mindustry.men` 的 Xenon 伺服器镜像访问。GitHub Releases 仅作为发布后端，Hub 运行时不再回退访问 GitHub。APK 使用 Android 系统确认页安装，并在启动前校验套件名称、版本、ABI、签名、档案大小和 SHA-256。

## 本地建置

环境需求：

- JDK 17
- Android SDK API 37
- Android build-tools 36.0.0
- Android NDK 25.2.9519653

Windows arm64 debug 建置：

```powershell
.\build-xenon-mobile-debug.bat --no-daemon
```

产物位于 `ZalithLauncher/build/outputs/apk/debug/`。本地 debug 签名通过环境变数或被忽略的密码档案注入，keystore 不进入发布源码。

验证命令：

```powershell
.\gradlew validateXenonMobileRelease
.\gradlew :ZalithLauncher:testDebugUnitTest --no-daemon
```

## 发布

推送 `v*` tag 会运行 `.github/workflows/release_ci.yml`，建置 Hub、11 个 arm64 clone APK、3 个源码锁定的 JAR，校验 artifact 元数据，生成 catalog，并上传源码、patch、锁定档、notice 和二进制资产。发布签名从 GitHub Actions Secrets 恢复。

详细合同见 [Catalog Publishing](docs/xenon-mobile-catalog-publishing.md)，固定游戏源码见 [game-source-lock.json](game-source-lock.json)。

## 授权

Xenon Mobile 使用 [GPL-3.0 license](LICENSE)。项目版权声明和第三方授权声明均予以保留，相关 notice 位于 [.github/notice](.github/notice)。
