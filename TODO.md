# Xenon Mobile 收尾 TODO

- [x] 统一 Hub 与 clone Bridge 的 Profile IPC 字段为 `uuid` / `name`。
- [x] 让 clone 在已有游戏 Activity 顶部时也能处理 JOIN 的 `onNewIntent`。
- [x] 补齐 Hub 端 Bridge 的 status、diagnostics、gracefulExit、reset 便捷方法。
- [x] 为 Bridge 的 SAF ZIP URI 传递补充跨包读写授权。
- [x] 运行 overlay、Bridge 编译、Gradle 校验和 debug 测试。
- [x] 做发布 workflow shell 语法与旧用户可见品牌/地址扫描。

## 上游跟进

- [x] 下载链路：镜像优先，GitHub 源站作为最后一层回退（catalog、APK/JAR、服务器列表、启动器更新元数据）。
- [x] 镜像允许通过直连 IP 访问（`network_security_config.xml` 放开明文流量，并可用 `-Pmindustry_mirror=<url>` 指定）。
- [x] 新增 `scripts/xenon-mobile/check-upstream-readiness.py`，用 `gh` 复核上游版本与发布仓库是否准备完整。
- [x] 新增 `scripts/xenon-mobile/update-upstream-pins.py`，一次性同步 `game-source-lock.json`、`build.gradle.kts`、`.github/workflows/release_ci.yml` 的源码锁。
- [x] 源码锁已同步到上游最新：Anuken/Mindustry v160.2、TinyLake/MindustryX v2026.09.X37、MindustryServerList HEAD（`game-source-lock.json`、`build.gradle.kts`、`release_ci.yml` 三处一致）。
- [x] `.github/workflows/upstream_sync_ci.yml` 每天 03:00 UTC 用 `gh` 自动同步上游源码锁并推送到 `main`。
- [x] 自动发版：`scripts/xenon-mobile/tag-release.py` 在产物落后于源码锁时自动打 `v0.0.0-ci-<date>-rN` tag，并用 `gh workflow run --ref <tag>` 显式派发 release（`GITHUB_TOKEN` 推送不会触发 workflow）。
- [x] `release_ci.yml` 增加 `workflow_dispatch` 与 `guard` 作业：非 `v*` tag 的 ref 直接拒绝，避免误发 main 分支版本。
- [ ] Xenon Mobile 最新 release（`v0.0.0-ci-20260802-r18`）仍是旧源码构建：等下一次同步作业跑到打 tag 步骤（或手动 `python scripts/xenon-mobile/tag-release.py --write --dispatch`）即可发布基于 v160.2 的产物。

## 镜像站

- [x] 确认镜像本体：`http://121.199.60.4/github`（HTTP/80）catalog、产物、服务器列表全部 200，产物 SHA-256 与 catalog 完全一致，支持 Range 与 immutable 缓存。
- [x] 定位域名故障：`play.mindustry.men` / `main.mindustry.men` 被主机商 ICP 备案拦截（返回 403 `Non-compliance ICP Filing` 页面），与客户端网络无关。
- [x] App 默认镜像改为直连 IP `http://121.199.60.4/github`，`play.mindustry.men` 降为第二候选，GitHub 仍是最后一层回退。

