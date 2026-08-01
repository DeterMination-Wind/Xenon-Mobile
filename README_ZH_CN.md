# Xenon Mobile

[![构建](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml/badge.svg)](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml)

[English](README.md) | [繁體中文](README_ZH_TW.md)

Xenon Mobile 是面向 Android 的 Mindustry Hub，管理源码构建的 Vanilla、Bleeding Edge 和 MindustryX JAR 实例，以及固定的 arm64 clone APK 槽位、Mindustry Profile、隔离数据、备份和服务器镜像目录。

Hub 当前读取：

```text
http://121.199.60.4/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

第一条地址是 Xenon 服务器镜像，第二条 HTTPS 地址是回退源。发布文件位于 [GitHub Releases](https://github.com/DeterMination-Wind/Xenon-Mobile/releases)，应用会优先通过镜像下载。APK 使用 Android 系统确认页安装，并在启动前校验包名、版本、ABI、签名、文件大小和 SHA-256。

## 本地构建

环境要求：

- JDK 17
- Android SDK API 37
- Android build-tools 36.0.0
- Android NDK 25.2.9519653

Windows arm64 debug 构建：

```powershell
.\build-xenon-mobile-debug.bat --no-daemon
```

产物位于 `ZalithLauncher/build/outputs/apk/debug/`。本地 debug 签名通过环境变量或被忽略的密码文件注入，keystore 不进入发布源码。

验证命令：

```powershell
.\gradlew validateXenonMobileRelease
.\gradlew :ZalithLauncher:testDebugUnitTest --no-daemon
```

## 发布

推送 `v*` tag 会运行 `.github/workflows/release_ci.yml`，构建 Hub、11 个 arm64 clone APK、3 个源码锁定的 JAR，校验 artifact 元数据，生成 catalog，并上传源码、patch、锁文件、notice 和二进制资产。发布签名从 GitHub Actions Secrets 恢复。

详细合同见 [Catalog Publishing](docs/xenon-mobile-catalog-publishing.md)，固定游戏源码见 [game-source-lock.json](game-source-lock.json)。

## 许可证

Xenon Mobile 使用 [GPL-3.0 license](LICENSE)。项目版权声明和第三方许可证声明均予以保留，相关 notice 位于 [.github/notice](.github/notice)。
