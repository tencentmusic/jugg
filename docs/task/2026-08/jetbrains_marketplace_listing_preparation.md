# JetBrains Marketplace 上架前准备

## 目标与范围

本方案面向 Jugg 首次上架 JetBrains Marketplace。Android Studio 插件通过同一 Marketplace 分发；不单独存在 Google Play 风格的 Android Studio 商店。

本次已完成的仓库内准备：

- 将 `plugin.xml` 的产品说明改为英文优先、HTML 格式，并明确核心价值与 Gradle 边界。
- 确认插件 ID 为 `com.sickworm.intellij.jugg`，当前版本为 `3.3.1`。
- 确认稳定版构建产物为 `idea/build/distributions/jugg-3.3.1-release.zip`，压缩包大小约 129 MB，低于 Marketplace 400 MB 上限。
- 确认 `:idea:buildPlugin` 已内置第三方许可证、NOTICE、修改声明、来源校验和和 SPDX SBOM，并会执行 `verifyThirdPartyCompliance`。
- 配置 `runPluginVerifier` 校验 Android Studio Bumblebee `2021.1.1.20`（211）和 Giraffe `2022.3.1.18`（223）；`until-build` 改为省略属性，避免空值被 Verifier 判为无效。
- 整理首次人工上传、审核和后续自动发布的操作步骤。

## 仍需在首次上传前解决的阻塞项

以下项目不能由 Marketplace 后台或当前构建替代，未关闭前不应将插件作为公开稳定版提交：

1. 完成公司/版权方对 Jugg 代码和发布资产的公开发行授权。现有开源准备检查将此列为 P0-01。
2. 远程 JAR 热更新和远程自定义编译器仍只在用户主动配置 Custom Server 后可用。配置前必须展示服务器 URL、能力和风险并要求确认；当前下载链路仍使用 MD5 校验，因此属于受信任服务器模型而非签名供应链。
3. 建立 public 发行包门禁：压缩包内网域名/凭据扫描、安装 smoke test、签名或来源证明。现有检查将此列为 P0-08。
4. 补充品牌图标和功能截图。仓库目前没有 `META-INF/pluginIcon.svg` 或 `pluginIcon_dark.svg`，插件包中也没有该图标。缺图标不会阻断技术上传，但不符合 Marketplace 的展示建议。
5. 修复 Plugin Verifier 发现的插件 ID 规则和旧版二进制兼容性。当前 ID `com.sickworm.intellij.jugg` 含有模板词 `intellij`，未静默时被 Verifier 直接判为无效；静默该规则后，Bumblebee `2021.1.1.20` 仍有 9 个兼容问题。将最低版本提高到 Chipmunk `2021.2.1.14` 后仍有 7 个兼容问题，包括 `PluginInstaller.installAfterRestart`、`GradleSyncListenerWithRoot`、`NotificationGroup`、`ContentFactory`、`DiffUserDataKeysEx` 等旧版中不可用的 API，以及缺失 Java 模块依赖。插件 ID 改动需要先确定既有安装的迁移策略。
6. 完成 Giraffe 及后续目标版本的 Verifier/真实安装矩阵。当前环境解析 Giraffe `2022.3.1.18` 下载地址时受到 Google CDN TLS 重定向阻断，尚未得到 223 结果；不能将此失败视为兼容通过。Java 11 字节码在 223 的 Java 17 运行时可执行，保持 Java 11 是支持 211 的必要条件，而非本项的修复目标。

这些阻塞项的事实、风险和完成标准以 `docs/task/2026-07/open_source_readiness_checklist.md` 的 P0-01、P0-07、P0-08 为准。

## 已准备的 Marketplace 文案

### 基础信息

| 字段 | 已知值 / 建议 |
| --- | --- |
| Plugin XML ID | `com.sickworm.intellij.jugg` |
| 名称 | `Jugg` |
| 发行模式 | Free，Stable channel |
| 许可证 | MIT，链接到 `https://github.com/sickworm/jugg/blob/main/LICENSE` |
| 源码 | `https://github.com/sickworm/jugg` |
| 文档 | 发布前填写公开可访问的 Wiki 地址；仓库内 Wiki 不能直接作为用户链接 |
| Issue tracker | `https://github.com/sickworm/jugg/issues` |
| Vendor | `sickworm`；当前 `plugin.xml` 已声明 `https://sickworm.com` 与 `ch.operation@gmail.com` |
| Ads | No；若页面或插件后续出现推广第三方服务的商业内容，需要重新声明 |

### 产品描述

`plugin.xml` 已包含如下英文主描述，并会随构建进入插件包：

> Fast incremental compilation and deployment for Android projects.
>
> Jugg compiles changed Android sources and resources outside the normal Gradle build, then deploys compatible changes to a running app.
>
> Highlights: incremental compilation for Kotlin, Java, Android resources, and manifests; code swap, resource deployment, and Gradle fallback; Android Studio and IntelliJ IDEA integration including Android test support and project-level controls.
>
> Important limitations: Jugg does not replace the complete Gradle pipeline. Changes that require Gradle processing, such as build-script changes or unsupported annotation and bytecode processing, require a Gradle build.

说明的首句为英文短摘要，满足 Marketplace 卡片使用描述前 40 个字符的要求。首次上传后，可在 Marketplace 后台增加简体中文补充说明，但英文必须保持在前。

### 标签和媒体

- 上传表单至少选择一个与 Android 开发和构建/部署相关的现有标签；只选择后台实际提供且准确匹配的标签，不为搜索量添加无关标签。
- 准备 3 张无个人信息的功能截图，建议至少 `1200 × 760`：增量 Run/Deploy 的成功结果、Control Panel、Gradle fallback 或 Android test 结果。截图只保留 IDE 窗口，不包含桌面、浏览器、用户名、项目路径、设备序列号、服务器地址或私有日志。
- 准备 `40 × 40` 的 `pluginIcon.svg`，四周至少保留 2 px 透明留白；若浅色和深色主题需要不同设计，同时提供 `pluginIcon_dark.svg`。文件应放在 `idea/src/ide_entry/resources/META-INF/`，并经重建确认位于插件主 JAR 的 `META-INF/`。

## 你需要执行的首次人工上架步骤

1. 关闭上节列出的 P0 阻塞项，使用 Marketplace Plugin Verifier 验证计划支持的 IDE build，并在每个计划上架的产品和版本组合中从磁盘安装发行包完成 smoke test。
2. 在受保护的 `main` 分支准备发布版本：更新版本号和双语 changelog，提交后创建与 `build.gradle` 完全一致的 tag，例如 `v3.3.2`。现有 Release CI 会校验 tag 与版本号一致，构建 zip 并发布 GitHub Release。
3. 使用 Release CI 的 zip，或本地执行 `./gradlew :idea:buildPlugin --no-daemon` 重新构建。上传前再次确认 zip 名称、版本、SHA-256、SBOM 和第三方合规校验均来自同一 commit。
4. 创建或登录 JetBrains Account，在 Marketplace 接受 Developer Agreement，并创建/选择 Vendor profile。
5. 打开 Marketplace 的 Upload plugin，选择 Stable channel、上传 zip、填写许可证链接、源码链接、文档和 issue tracker，选择准确标签并声明 `Ads: No`。
6. 上传前检查自动解析出的名称、供应商、版本、说明和兼容 IDE 是否正确；首个插件必须在网页人工上传，不能直接用 Gradle 或 API 创建。
7. 提交审核。审核状态超过两个工作日没有变化时，联系 `marketplace@jetbrains.com`。
8. 审核通过后，在 Marketplace 后台添加并检查截图、Getting started、联系信息和链接；从实际 Android Studio / IntelliJ IDEA 搜索并安装一次。

## 首次上传后的自动发布建议

首次插件创建成功后，再把 Marketplace Token 以 GitHub Actions Secret 保存，不写入仓库、`gradle.properties` 或命令历史。当前项目使用 Gradle IntelliJ Plugin 1.x，因此可在后续发布工作流中配置 `publishPlugin` 与 `signPlugin`：

- 发布 token 使用短期可轮换的 Marketplace permanent token。
- 私钥、证书链和密码仅通过 CI Secret 注入；私钥不能提交到 Git。
- 先对隐藏更新或 beta channel 验证，再发布 Stable。
- 同一版本号不能重复上传；每次 Marketplace 更新都要先递增 `build.gradle` 中的版本。

在自动化接入前，继续使用 Marketplace 后台的 `Upload Update` 上传每个新 zip。每次上传都运行 Marketplace Plugin Verifier，并只在通过目标 IDE 兼容性检查后提交。

## 验收与回滚

- 上传前：`buildPlugin` 成功、插件 zip 内第三方合规文件齐全、SHA-256 已记录、干净 IDE 安装并可加载。
- 审核后：Marketplace 页面名称、版本、描述、许可证、来源链接、截图和兼容 IDE 均正确；在 Android Studio 与 IntelliJ IDEA 各进行一次安装验证。
- 出现严重问题：先在 Marketplace 将该更新设为 Hidden 或删除可删除的版本，再撤回 GitHub Release；发布说明中提供恢复到上一稳定版的下载和版本号。

## 官方依据

- [Uploading a new plugin](https://plugins.jetbrains.com/docs/marketplace/uploading-a-new-plugin.html)
- [Best practices for listing your plugin](https://plugins.jetbrains.com/docs/marketplace/best-practices-for-listing.html)
- [Publishing a Plugin](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
- [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Plugin Logo](https://plugins.jetbrains.com/docs/intellij/plugin-icon-file.html)
