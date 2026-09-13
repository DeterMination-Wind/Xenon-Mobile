# Xenon Mobile

[![建置](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml/badge.svg)](https://github.com/DeterMination-Wind/Xenon-Mobile/actions/workflows/push_ci.yml)

[English](README.md) | [简体中文](README_ZH_CN.md)

Xenon Mobile 是面向 Android 的 Mindustry Hub，管理源码建置的 Vanilla、Bleeding Edge 与 MindustryX JAR 实例，以及固定的 arm64 clone APK 槽位、Mindustry Profile、隔离资料、备份和伺服器镜像目录。

Hub 目前读取：

```text
http://121.199.60.4/github/raw/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json
```

catalog、遊戲產物與伺服器列表優先透過 Xenon 镜像存取，預設使用镜像的直連 IP `http://121.199.60.4/github`——因為 `mindustry.men` 網域會被主機商的 ICP 備案攔截頁阻擋，而同一台機器的 IP 存取正常。建置時可用 `-Pmindustry_mirror=<url>` 指定其他部署；應用程式允許明文流量，因此純 HTTP 端點可用。舊網域作為第二候選，GitHub Releases 仍作為發布後端，且僅在所有镜像都失敗後作為最後一層下載回退。APK 使用 Android 系统确认页安装，并在启动前校验套件名称、版本、ABI、签名、档案大小和 SHA-256。

## 下載來源

所有 Mindustry 資源依下列順序下載：

1. Xenon 镜像，預設 `http://121.199.60.4/github`（建置時可用 `-Pmindustry_mirror=<url>` 覆寫），其次是 `play.mindustry.men` 網域；
2. 僅當所有镜像都失敗時，回退至 GitHub 來源：
   - catalog：`https://raw.githubusercontent.com/DeterMination-Wind/Xenon-Mobile/main/catalog/xenon-mobile-catalog.json`，
   - 發布資產：`https://github.com/DeterMination-Wind/Xenon-Mobile/releases/download/<tag>/<file>`，
   - 伺服器列表：`https://raw.githubusercontent.com/Anuken/MindustryServerList/main/servers_v8.json` 與 `servers_be.json`。

下載完成後一律校驗檔案大小與 SHA-256，因此 GitHub 回退不會改變最終產物。

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
