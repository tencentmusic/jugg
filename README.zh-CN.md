<p align="left">
  <a href="./README.md">English</a> | <strong>简体中文</strong>
</p>

# Jugg

> 大规模 Android 工程，3 秒看到修改效果。

**Life is short, Jugg it! 人生苦短，Jugg 一下。**

Jugg 是腾讯音乐技术团队开源的 Android Studio 插件。它复用最近一次 Gradle 构建，只编译本轮变化及其影响范围，再将结果部署到设备，少量修改通常可以在 3 秒内看到效果。

Jugg 仅需安装 IDE 插件，不修改 Gradle 脚本，也不要求工程接入 SDK。Jugg Run Configuration 与原生 App Run Configuration 同时保留：选择 Jugg 配置时使用增量编译与部署；需要原来的 Android Studio 构建流程时，随时切回原生 App 配置即可，两者完全独立运行。当工程变化超出增量路径的处理范围时，Jugg 自身也会回退 Gradle 构建并重新建立基线。

- [下载最新稳定版](https://github.com/tencentmusic/jugg/releases/latest)
- [Jugg Wiki](https://tencentmusic.github.io/jugg/zh/)
- [观看演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)

## 社区交流

加入 Jugg 微信交流群，交流使用经验和问题排查。

<p align="center">
  <img src="./docs/images/wechat-group.jpg" alt="Jugg 微信交流群二维码" width="360">
</p>

## 快速开始

1. 从 [Releases](https://github.com/tencentmusic/jugg/releases/latest) 安装插件。
2. 打开 Android 工程，创建或选择 Jugg Run Configuration。
3. 首次 Run 建立 Gradle 基线；之后修改源码或资源，再次 Run 即可。

完整步骤见 [开始接入](https://tencentmusic.github.io/jugg/zh/onboarding/)。

## Jugg 方案介绍

Jugg 保留 Gradle 作为可信构建产物来源，同时跳过与日常修改无关的 Gradle 工作：

1. **建立基线**：复用最近一次完整 Gradle 构建。
2. **识别变化与影响**：分析源码、资源、依赖和类关系。
3. **只编译必要内容**：调用 Java、Kotlin、D8 和 Jugg 增量资源工具链。
4. **安全部署**：选择热重载、热修复、增量 APK 或重新安装，必要时回退 Gradle。

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

详细要求和行为边界见 [兼容范围](https://tencentmusic.github.io/jugg/zh/reference/compatibility)，其他版本也可能可用，但尚未完整验证。

## AI Agent Skill 与 CLI

`jugg-android-dev-loop` Skill 引导 AI Agent 完成 **修改 -> 编译 -> 部署 -> 验证**；`jugg` CLI 则让终端和脚本调用同一套插件能力。打开目标工程后，在 Search Everywhere 中执行 `Install Jugg Skills` 即可安装。

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

## 隐私

标准插件默认离线运行，开源后不再采集工程使用统计。问题报告只包含明确列出且已脱敏的诊断文件，上传前会展示目标地址，也可以仅保存到本地。

## 开发

- `./gradlew buildPlugin`：构建插件，产物位于 `idea/build/distributions`。
- `./gradlew runIde`：启动用于开发和调试的 IDE。

## License

Jugg 使用 [MIT License](LICENSE) 开源。
