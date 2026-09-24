<p align="left">
  <a href="./README.md">English</a> | <strong>简体中文</strong>
</p>

# Jugg: Android Studio 秒级增量编译与即时热重载插件

<p align="left">
  <a href="https://github.com/tencentmusic/jugg/releases/latest"><img src="https://img.shields.io/github/v/release/tencentmusic/jugg?label=Release&color=blue" alt="最新版本"></a>
  <a href="https://github.com/tencentmusic/jugg/stargazers"><img src="https://img.shields.io/github/stars/tencentmusic/jugg?style=flat&color=yellow" alt="GitHub Stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT"></a>
  <a href="#规模验证"><img src="https://img.shields.io/badge/Production-800k%2B_Builds-brightgreen.svg" alt="生产验证"></a>
  <a href="#隐私"><img src="https://img.shields.io/badge/Privacy-100%25_Offline_%2F_No_Telemetry-orange.svg" alt="隐私安全"></a>
  <a href="#核心特性"><img src="https://img.shields.io/badge/Config-Zero_Intrusion-blue.svg" alt="零配置侵入"></a>
</p>

> 大规模 Android 工程，3 秒看到修改效果。

**Life is short, Jugg it! 人生苦短，Jugg 一下。**

**Jugg** 是腾讯音乐技术团队开源的 **Android Studio 插件**，专为 **极速增量编译** 与 **即时热重载（Instant Hot Reload）** 设计。它复用最近一次 Gradle 构建基线，只编译本轮修改及其影响范围，并将结果快速部署到真机或模拟器上，日常小改动通常可以在 **3 秒内** 看到效果——免去日常迭代中漫长且重复的 Gradle 构建等待。

Jugg 仅需安装 IDE 插件，**不修改任何 Gradle 脚本，也不要求工程接入任何 SDK**。Jugg Run Configuration 与原生 App Run Configuration 同时保留：选择 Jugg 配置时享受秒级增量编译与部署；需要原生的 Android Studio 构建流程时，随时切回原生 App 配置即可，两者完全独立运行、互不影响。当工程改动超出增量处理范围时，Jugg 会自动安全回退到 Gradle 构建并重新建立基线。

- [下载最新稳定版](https://github.com/tencentmusic/jugg/releases/latest)
- [技术方案介绍](https://juejin.cn/post/7680996030843125796)
- [观看演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)
- [Jugg 官方文档 (Wiki)](https://tencentmusic.github.io/jugg/zh/)
- [常见问题与排查](https://tencentmusic.github.io/jugg/zh/troubleshooting/compile-failed)

## 核心特性

- ⚡ **3 秒极速增量部署**：日常改动秒级体验“改动代码 -> 手机呈现”，免去重复等待漫长的 Gradle 配置期与任务执行耗时。
- 🔥 **Apply Changes (JVMTI) 即时热重载**：基于 JVMTI 机制实现免重启热更新，将变更的代码和资源直接注入运行中的应用进程；当热更不适用时，自动安全回退至 Hot Fix（仅重启应用，无需重新安装 APK）。
- 🛡️ **规模验证与安全回退（Safe Fallback）**：在腾讯音乐内部经受 80 万+ 次生产环境构建验证。当复杂代码或依赖变更超出安全增量边界时，Jugg 自动平滑回退至标准 Gradle 构建，杜绝状态错乱与结果不一致。
- 🔌 **零工程侵入与双配置共存**：不修改项目 Gradle 脚本，不引入任何业务 SDK 依赖。Jugg 运行配置与原生配置无缝共存，随时一键切回原有构建流程。
- 🧩 **广泛的技术栈支持**：全面兼容 Kotlin、Java、Jetpack Compose、KMP（Compose Multiplatform）、DataBinding、ViewBinding、资源/Assets、AndroidManifest 以及 Native C/C++ 动态库。
- 🤖 **AI Agent 与 CLI 深度集成**：内置 MCP Server、独立 CLI 以及 Agent Skill（`jugg-android-dev-loop`），为 AI 辅助编程提供“修改 -> 编译 -> 部署 -> 验证”的全自动闭环。

## 社区交流

加入 Jugg 微信交流群（二群），实时交流使用经验和问题排查（扫描下方二维码）。

<p align="center">
  <img src="./docs/images/wechat-group.jpg" alt="Jugg 微信交流群二维码" width="360">
</p>

## 快速开始

1. 直接从 [GitHub Releases 最新发布页 (.zip)](https://github.com/tencentmusic/jugg/releases/latest) 下载插件安装包，然后在 Android Studio 中通过 **Plugins -> ⚙️ -> Install Plugin from Disk...** 安装（JetBrains 市场上架中）。
2. 打开 Android 工程， 等待 Jugg Run Configuration 自动创建。
3. 选择 Jugg Run Configuration，点击运行。首次 Run 需要建立 Gradle 基线；之后修改源码或资源，再次 Run 即进入秒级旁路增量编译。

> 你可以在任何时候切换 Jugg / Android Run Configuration 运行，他们之间完全独立，互不干扰。

更多信息见 Jugg Wiki [开始接入指南](https://tencentmusic.github.io/jugg/zh/onboarding/)。

## Jugg 方案介绍

Jugg 保留 Gradle 作为可信构建产物来源，同时跳过与日常修改无关的 Gradle 工作：

1. **建立基线**：复用最近一次完整 Gradle 构建。
2. **识别变化与影响**：分析源码、资源、依赖和类关系（[影响分析原理](https://tencentmusic.github.io/jugg/zh/concepts/deploy-data-and-impact)）。
3. **只编译必要内容**：调用 Java、Kotlin、D8 和 Jugg 增量资源工具链（[增量编译架构](https://tencentmusic.github.io/jugg/zh/concepts/incremental-compile/)）。
4. **安全部署**：选择热重载、热修复、增量 APK 或重新安装（[部署策略](https://tencentmusic.github.io/jugg/zh/concepts/deploy-strategy)），必要时回退 Gradle（[Gradle 回退机制](https://tencentmusic.github.io/jugg/zh/concepts/gradle-fallback-baseline)）。

## 能力与兼容范围

### 已支持能力

| 领域 | 已支持能力 |
|---|---|
| 源码与资源 | Java、Kotlin、Java/Kotlin 混编、Compose、KMP、Compose Multiplatform、res、assets、Manifest、native `.so` |
| Android 工程 | DataBinding、ViewBinding、已适配的注解处理器、依赖增量编译、Release 增量编译、AabResGuard、自定义编译器 |
| 部署 | 热重载、热修复、增量 APK、多 APK、多设备、Dynamic Feature、兼容部署与失败恢复 |
| Android Test | Application / Library Android Test、Test Results UI、Logcat 归因 |
| 自动化 | Jugg CLI、MCP、Agent Skills、构建部署、设备与运行时查询、UI 自动化、远端诊断 |

### 已验证兼容范围

| 环境 | 范围 |
|---|---|
| Android Studio | 2021（Bumblebee）至今 |
| IntelliJ IDEA | 2021.1.3 至 2025.1 Beta |
| Android Gradle Plugin | 3.4 至 9.1 |
| Gradle | 5.4.1 至 9.2.1 |
| Kotlin | 1.3 至 2.2 |
| Android | 8 至 16 |

详细要求和行为边界见 Jugg Wiki [能力总览](https://tencentmusic.github.io/jugg/zh/capabilities/) 与 [兼容范围](https://tencentmusic.github.io/jugg/zh/reference/compatibility)，其他未完整验证版本可能会有少量功能问题，如遇到请提 issue 修复。

## AI Agent Skill 与 CLI

The `jugg-android-dev-loop` Skill 引导 AI Agent 完成 **修改 -> 编译 -> 部署 -> 验证**；`jugg` CLI 则让终端和脚本调用同一套插件能力。

安装方式：打开任意工程，在 Search Everywhere 中搜索 `Install Jugg Skills` 即可安装。

```shell
jugg status
jugg compile
jugg deploy
```

支持的客户端、命令和工作流边界见 [CLI 使用指南](https://tencentmusic.github.io/jugg/zh/guide/cli) 与 [Agent Skills](https://tencentmusic.github.io/jugg/zh/capabilities/tools/agent-skills)。

## 规模验证

Jugg 于 2021 年开始研发，2023 年在腾讯音乐内部发布。开源前，同一套通用实现已用于全民 K 歌、QQ 音乐、JOOX、WeSing、酷狗音乐、酷狗直播、QQ 浏览器和央视频等大型 Android 工程。

- **10+** 个大型 Android 工程
- **80 万+** 次增量编译
- **3.6 万+** 小时编译等待节省

## Star 历史

如果 Jugg 帮您节省了宝贵的编译等待时间，请给这个项目点个 ⭐️ 支持我们，也让更多 Android 开发者发现它！

<p align="center">
  <a href="https://star-history.com/#tencentmusic/jugg&Date">
    <img src="https://api.star-history.com/svg?repos=tencentmusic/jugg&type=Date" alt="Jugg Star 历史趋势图" width="700">
  </a>
</p>

## 隐私

### 1. 数据采集
默认情况下，本插件不会采集、跟踪或传输任何个人数据、使用指标或源代码信息。

### 2. 可选功能与数据传输
- **自定义后台服务**：用户可选择配置自定义后台服务，用于远程构建缓存或管理（见 [Jugg 后台指南](https://tencentmusic.github.io/jugg/zh/guide/jugg-backend/)）。该过程中传输的数据仅发往用户自行指定的服务器，并由用户基础设施策略管理。
- **手动日志上报**：如遇问题，可自愿反馈并手动上传诊断日志（见 [问题反馈](https://tencentmusic.github.io/jugg/zh/guide/report-issue)）。
  - 仅在用户明确触发时才会上传日志。
  - 上传的诊断日志仅用于排查与调试。
  - 所有已上传的日志数据将在 **7 天** 后自动删除。

### 3. 联系与支持
如对隐私有任何疑问或顾虑，请在我们的 [GitHub 仓库](https://github.com/tencentmusic/jugg/issues) 提交 Issue。

## 安全

若发现 Jugg 的安全漏洞，请按 [SECURITY.zh-CN.md](SECURITY.zh-CN.md) 私下报告，不要提交公开 Issue。

## 开发

- `./gradlew buildPlugin`：构建插件，产物位于 `idea/build/distributions`。
- `./gradlew runIde`：启动用于开发和调试的 IDE。

如何反馈问题、搭建环境和提交 Pull Request，见 [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)。

## License

Jugg 使用 [MIT License](LICENSE) 开源。
