# Xenon Mobile

[![构建](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml/badge.svg)](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml)

[English](README.md) | [繁體中文](README_ZH_TW.md)

Xenon Mobile 是面向 Android 的 Mindustry Hub，管理源码构建的 Vanilla、Bleeding Edge 和 MindustryX JAR 实例，以及固定的 arm64 clone APK 槽位、Mindustry Profile、隔离数据、备份和服务器镜像目录。

Hub 当前读取：

```text
http://121.199.60.4/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

catalog、游戏产物和服务器列表优先通过 Xenon 镜像访问，默认使用镜像的直连 IP `http://121.199.60.4/github`——因为 `mindustry.men` 域名会被主机商的 ICP 备案拦截页挡住，而同一台机器的 IP 访问正常。构建时可用 `-Pmindustry_mirror=<url>` 指定其它部署；应用允许明文流量，所以纯 HTTP 端点可用。旧域名作为第二候选，GitHub Releases 仍作为发布后端，并且仅在所有镜像都失败后作为最后一层下载回退。APK 使用 Android 系统确认页安装，并在启动前校验包名、版本、ABI、签名、文件大小和 SHA-256。

## 下载来源

所有 Mindustry 资源按以下顺序下载：

1. Xenon 镜像，默认 `http://121.199.60.4/github`（构建时可用 `-Pmindustry_mirror=<url>` 覆盖），其次是 `play.mindustry.men` 域名；
2. 仅当所有镜像都失败时，回退到 GitHub 源站：
   - catalog：`https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json`，
   - 发布资产：`https://github.com/DeterMination-Wind/Xenon-Mobile/releases/download/<tag>/<file>`，
   - 服务器列表：`https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json` 与 `servers_be.json`。

下载完成后始终校验文件大小与 SHA-256，因此 GitHub 回退不会改变最终产物。

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
