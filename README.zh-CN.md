<p align="right">
  <a href="./README.md">English</a> | <strong>简体中文</strong>
</p>

# Jugg

> 大规模 Android 工程，3 秒看到修改效果。

**Life is short, Jugg it! 人生苦短，Jugg 一下。**

Jugg 是腾讯音乐技术团队开源的 Android 秒级增量编译与部署工具，以 Android Studio / IntelliJ IDEA 插件形式提供。它复用最近一次 Gradle 构建留下的可信产物，只编译本轮变化及其影响范围，再将代码和资源快速部署到设备。少量日常修改通常可以在 3 秒内看到效果。

Jugg 仅需安装 IDE 插件，不修改 Gradle 脚本，也不要求工程接入 SDK。日常开发仍然使用熟悉的 Run 入口；当工程变化超出增量路径的处理范围时，Jugg 会回退 Gradle 构建并重新建立基线。

- [下载最新稳定版](https://github.com/tencentmusic/jugg/releases/latest)
- [Jugg Wiki](https://tencentmusic.github.io/jugg/zh/)
- [观看演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)

## Jugg 方案介绍

Gradle 和 AGP 负责生成完整、可信的 Android 构建产物，但 Gradle 启动、Configuration 和 Task 编排的固定开销不会随着修改量减少。大型工程中，即使只修改一行代码或一个资源文件，也可能需要等待较长时间才能看到结果。

Jugg 不替代 Gradle，而是在一次完整构建之后复用 APK、Class、依赖和生成源码等产物，建立增量编译基线。后续 Run 会绕过与本轮修改无关的 Gradle 工作，直接完成变化识别、影响分析、增量编译和设备部署。修改构建脚本、依赖或其他影响基线的内容时，Jugg 会按需回到 Gradle 路径。

Jugg 于 2021 年开始研发，2023 年在腾讯音乐内部发布。开源前已用于全民 K 歌、QQ 音乐、JOOX、WeSing、酷狗音乐、酷狗直播、QQ 浏览器、央视频等大型 Android 工程，所有工程均使用同一套通用实现，没有业务定制逻辑。

开源发布前累计验证数据：

- **10+** 个大型 Android 工程
- **80 万+** 次增量编译
- **3.6 万+** 小时编译等待节省

> 开源后，Jugg 不再采集工程使用统计。

## 一次 Jugg Run 如何完成

1. **复用基线**：读取最近一次完整 Gradle 构建生成的 APK、Class、依赖和工程信息。
2. **识别变化**：结合 IDE 文件事件与 Git 状态，确定本轮真正变化的源码、资源和工程文件。
3. **增量编译**：直接调用 Java、Kotlin、D8 和定制 AAPT2 等工具，只生成本轮需要的增量产物。
4. **扩散影响**：分析调用、继承、常量内联和方法签名等影响，自动补充必须重新编译的源码。
5. **部署变更**：根据修改类型和设备状态，在热重载、热修复、增量 APK 更新或重新安装之间选择合适策略。
6. **保存状态**：部署成功后保存增量产物与变更记录，作为下一次 Run 的起点。

Jugg 的速度来自只处理必要输入；结果的可信度来自影响扩散、Gradle 回退和部署状态恢复。

## 能力范围

| 领域 | 已支持能力 |
|---|---|
| 源码与资源 | Java、Kotlin、Java/Kotlin 混编、Compose、KMP、Compose Multiplatform、res、assets、Manifest、native `.so` |
| Android 工程 | DataBinding、ViewBinding、已适配的注解处理器、依赖增量编译、Release 增量编译、AabResGuard、自定义编译器 |
| 部署 | 热重载、热修复、增量 APK、多 APK、多设备、Dynamic Feature、兼容部署与失败恢复 |
| Android Test | Application / Library Android Test、Test Results UI、Logcat 归因 |
| 自动化 | Jugg CLI、MCP、Agent Skills、构建部署、设备与运行时查询、UI 自动化、远端诊断 |

更完整的支持条件和行为边界请查看 [兼容范围](https://tencentmusic.github.io/jugg/zh/reference/compatibility)。

## 已验证兼容范围

| 环境 | 范围 |
|---|---|
| Android Studio | 2021（Bumblebee）至今 |
| IntelliJ IDEA | 2021.1.3 至 2025.1 Beta |
| Android Gradle Plugin | 3.4 至 9.1 |
| Gradle | 5.4.1 至 9.2.1 |
| Kotlin | 1.3 至 2.2 |
| Android | 8 至 16 |

未列出的版本不代表一定不可用，但可能存在尚未覆盖的兼容差异。遇到明确问题请提交 [Issue](https://github.com/tencentmusic/jugg/issues)。

## 快速开始

1. 从 [Releases](https://github.com/tencentmusic/jugg/releases/latest) 下载并安装插件。
2. 打开现有 Android 工程，创建或选择 Jugg Run Configuration。
3. 首次 Run 通过 Gradle 建立可信基线；之后修改源码或资源，再次点击 Run 查看增量结果。

详细步骤见 [开始接入](https://tencentmusic.github.io/jugg/zh/onboarding/)。

## 网络与诊断隐私

标准 `buildPlugin` 产物不包含预设的 Jugg 后端配置，默认离线运行。用户仍可自行配置 Custom Server。

问题报告仅从脱敏白名单中收集诊断文件；上传前会展示准确的目标地址和文件列表，也可以只保存到本地而不发起网络请求。

## 项目结构

| 模块 | 职责 |
|---|---|
| `idea` | IDE 插件入口、运行配置、任务编排和 UI |
| `main` | 增量编译、部署、项目模型、Gradle 与 MCP 核心逻辑 |
| `deploy_compat` | 不同 Android Studio 版本的部署 API 兼容层 |
| `platform_compat` | 供核心逻辑编译使用的平台 API 兼容桩 |
| `jvmti_agent` | JVMTI Agent 与 App 运行时能力 |
| `aapt2-inclink` | AAPT2 增量链接工具资源 |
| `custom_compilers` | 自定义编译器示例 |

## 构建项目

```shell
# 构建插件，产物位于 idea/build/distributions
./gradlew buildPlugin

# 启动用于开发和调试的 IDE
./gradlew runIde
```

## License

Jugg 使用 [MIT License](LICENSE) 开源。
