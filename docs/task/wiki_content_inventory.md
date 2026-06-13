# Wiki 内容全量梳理清单

> 目标：把 Jugg 面向用户需要上 Wiki 的内容一次性盘全，后续逐页写作时按本清单验收，避免遗漏功能。
> 口径：Wiki 面向插件使用者、Agent/CLI 使用者与排障人员；内部实现细节只在能帮助理解能力边界、操作入口或排障时上 Wiki。

> 维护口径：本文是 Wiki 初始目录建设阶段的功能覆盖审计记录，不作为长期同步源；长期以 `docs/wiki/**` 与 VitePress sidebar 为准，必要时通过阶段性审计重新生成或校验。

## 1. 盘点依据与覆盖规则

### 1.1 依据来源

| 来源 | 用途 |
|---|---|
| `docs/ai_knowledge/00_overview.md` | 项目定位、模块范围、核心运行链路 |
| `docs/ai_knowledge/99_index.md` | 专题文档目录、任务路由与能力域 |
| `docs/ai_knowledge/98_code_map.md` | 生产模块、入口类、已知核心功能 |
| `docs/ai_knowledge/*` 标题索引 | 编译、部署、测试、MCP、CLI、排障专题补全 |
| `docs/wiki/.vitepress/config.mts` 与现有 `docs/wiki/**` | 当前 VitePress 信息架构与缺口 |
| 生产代码包目录与类名索引 | 校对文档未显式展开的公开能力：AI skill、UI 工具、兼容层、JVMTI、命令行等 |

### 1.2 不遗漏规则

后续每个正式 Wiki 页面都应至少归入下列五类之一：

1. **Onboarding**：用户第一次安装、启用、跑通 Jugg。
2. **Guide**：用户执行某项任务的步骤。
3. **Concepts**：解释工作原理、策略、边界。
4. **Capabilities**：说明某项能力是否支持、入口、限制与典型场景。
5. **Troubleshooting / Reference**：现象排查、日志、参数、兼容矩阵、术语表。

若一个功能同时有“怎么用”和“边界限制”，必须同时进入 Guide 与 Capability；若该功能有高频失败场景，还必须进入 Troubleshooting。

### 1.3 当前 Wiki 骨架缺口

当前 `docs/wiki` 已有中英文双语骨架，但除首页外基本均为 `Coming soon.`。现有页面只覆盖了一级分类和少量能力占位；缺少以下维度：

- 安装、更新、首次运行、Run Configuration 配置、More Options 菜单入口。
- 编译子能力细分：资源、DataBinding、Manifest、Kotlin/Java/Dex、KSP/APT、常量引用、AabResGuard、远端 Gradle、自定义编译器。
- 部署子能力细分：安装、重启、recover/retry、Direct Overlay、JVMTI Agent、多 APK/多设备、部署缓存。
- Android Test 细节：gutter、sourcePath、library test APK、SM Test Runner、rerun failed、logcat 归因。
- Debug attach、断点不可用排查。
- MCP/CLI 全工具与 UI 验证能力，尤其 layout-dump / view-locate / view-inspect / tap / wait-logs / figma-layout-verify。
- Agent skill 安装、hooks、权限规则、CLI 自动更新、不同 Agent 支持。
- Android Studio 版本兼容矩阵与平台/命令行能力。

## 2. 推荐 Wiki 目录总览

> 说明：路径以英文 root locale 为准；中文页面在 `docs/wiki/zh/` 下镜像同名路径。已有占位页可复用，新增页需同步更新 `docs/wiki/.vitepress/config.mts`。

### 2.1 Onboarding

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `onboarding/index.md` | 已有占位 | 新用户路线图：安装插件、打开 Android 项目、首次 Gradle baseline、第一次 Jugg Run、如何判断成功 |
| `onboarding/installation.md` | 待新增 | 插件安装/更新、Android Studio 版本要求、项目要求、Jugg 数据目录简介 |
| `onboarding/first-run.md` | 待新增 | 从普通 Run 切换到 Jugg Run Configuration、首次 full build、增量编译/部署成功标志 |
| `onboarding/agent-setup.md` | 待新增 | 安装 Jugg CLI / skill / hooks，支持 Codex、Claude、CodeBuddy、Cursor、Gemini 的入口差异 |

### 2.2 Guide

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `guide/compile.md` | 已有占位 | 一次增量编译、强制 Gradle build、查看编译结果、何时需要 full build |
| `guide/deploy.md` | 已有占位 | 编译并部署、clean reinstall、restart、alwaysRestartApp、设备选择、多设备结果解读 |
| `guide/android-test.md` | 已有占位 | gutter 运行、RunConfig 运行、class/method 级运行、runner/extras、rerun failed |
| `guide/debug.md` | 已有占位 | Jugg + Debug 按钮、部署后 attach、断点生效条件与失败处理 |
| `guide/cli.md` | 待新增 | CLI 安装、全局参数、输出模式、常用命令组合、异步任务轮询与 Ctrl-C |
| `guide/mcp.md` | 待新增 | MCP 连接、`projectDir`、工具调用基本流程、异步 `jobId` 与 `get-compile-status` |
| `guide/ui-inspection.md` | 待新增 | layout-dump、view-locate、view-inspect、tap、activity-stack、wait-logs 的组合工作流 |
| `guide/remote-gradle.md` | 待新增 | 远端 Gradle 编译、SSH 信息申请、rsync/远端产物同步、失败时如何回退 |
| `guide/custom-compiler.md` | 待新增 | 自定义编译器 SPI 配置、jar 下载/装载、编译交互确认、适用场景 |

### 2.3 Concepts

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `concepts/how-jugg-works.md` | 已有占位 | IDE 插件层、核心逻辑层、兼容层、JVMTI Agent、MCP/CLI 的整体关系 |
| `concepts/incremental-compile.md` | 已有占位 | 增量编译基线、文件变更、阶段顺序、重试、Gradle 回退边界 |
| `concepts/deploy-strategy.md` | 已有占位 | install / code swap / full swap / hot reload / clean reinstall 的选择逻辑 |
| `concepts/fallback-and-limits.md` | 已有占位 | 旁路编译非完整 Gradle pipeline、注解处理/字节码插桩/复杂脚本等边界 |
| `concepts/project-model.md` | 待新增 | `JuggProjectInfo`、`ModuleInfo`、APK 归属、路径与构建产物快照 |
| `concepts/compile-pipeline.md` | 待新增 | Resource -> DataBinding -> Source -> Dex -> Deploy data 的产物流转 |
| `concepts/deploy-data-and-impact.md` | 待新增 | 类结构影响分析、`EffectedType`、常量引用、release/minify 补偿、inline method |
| `concepts/jvmti-agent.md` | 待新增 | Runtime agent、hotfix loader、资源/类加载修补、ViewHierarchy Server 的职责边界 |
| `concepts/android-test-flow.md` | 待新增 | AndroidTest target、test APK 选择、instrumentation、日志归因、SM Test Runner |
| `concepts/mcp-and-cli.md` | 待新增 | MCP JSON-RPC 服务、CLI 映射、异步编译模型、产物与日志输出 |
| `concepts/compatibility-layer.md` | 待新增 | Android Studio deploy API 版本迁移、`deploy_compat` 版本层级、平台兼容桩 |

### 2.4 Capabilities

#### 2.4.1 Compile

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `capabilities/compile/index.md` | 已有占位 | 编译能力总览与能力矩阵 |
| `capabilities/compile/incremental-compile.md` | 待新增 | Java/Kotlin/Dex 增量编译、生成源码、Kotlin metadata、KSP/APT 相关能力 |
| `capabilities/compile/dependency-incremental.md` | 已有占位 | 依赖库变更检测、library module 增量、依赖 diff 与回退场景 |
| `capabilities/compile/resource-compile.md` | 待新增 | res/assets overlay、aapt2 daemon、R/RDEx、styleable、arsc、资源包修补 |
| `capabilities/compile/databinding-viewbinding.md` | 待新增 | DataBinding/ViewBinding base classes、mapper、BR、layout include、增量限制 |
| `capabilities/compile/manifest.md` | 待新增 | Manifest diff/merge、activity 信息、manifest 相关失败场景 |
| `capabilities/compile/release-compile.md` | 已有占位 | R8 mapping、usage.txt、minify、`_jugg_fix`、release 增量 crash 风险 |
| `capabilities/compile/const-ref.md` | 待新增 | 编译期常量定义/引用追踪、DB 缓存、worktree 共享缓存、受影响源码查询 |
| `capabilities/compile/aab-resguard.md` | 待新增 | AabResGuard/ResGuard mapping、资源名/包名处理、release 资源限制 |
| `capabilities/compile/gradle-fallback.md` | 待新增 | 自动/手动 Gradle 回退、本地/远端 Gradle 客户端、失败详情摘要 |
| `capabilities/compile/custom-compiler.md` | 待新增 | `ICompilerCreator` SPI、远端 jar、编译交互 UI、示例能力 |

#### 2.4.2 Deploy

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `capabilities/deploy/index.md` | 已有占位 | 部署能力总览与策略矩阵 |
| `capabilities/deploy/clean-reinstall.md` | 已有占位 | 卸载、清数据、重装、启动、适用场景 |
| `capabilities/deploy/code-swap.md` | 已有占位 | 代码热修、结构不变类变更、restart 策略、失败回退 |
| `capabilities/deploy/full-swap.md` | 已有占位 | class/resource/dex 全量替换、触发条件、用户可见影响 |
| `capabilities/deploy/hot-reload.md` | 待新增 | `alwaysRestartApp=false`、HOT RELOAD 条件、不能热更的变更类型 |
| `capabilities/deploy/restart.md` | 待新增 | restart 工具、wait app ready、App ready 判定边界 |
| `capabilities/deploy/direct-overlay.md` | 待新增 | Direct Overlay 旁路、startup agent push、dirty 状态、fallback 限制 |
| `capabilities/deploy/recover-and-retry.md` | 待新增 | deploy state recover、dry deploy、ADB transient offline、retry 语义 |
| `capabilities/deploy/multi-apk.md` | 待新增 | base APK、test APK、library self-targeting Test APK、`targetApkPaths` 分流 |
| `capabilities/deploy/multi-device.md` | 待新增 | 多设备部署汇总、单设备失败处理、selected device 展示 |
| `capabilities/deploy/deploy-history-cache.md` | 待新增 | deploy history、deployment cache、full build info、状态不一致处理 |
| `capabilities/deploy/jvmti-runtime.md` | 待新增 | agent 协同、JVMTI 支持矩阵、native agent 构建与启动边界 |

#### 2.4.3 Test

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `capabilities/test/index.md` | 已有占位 | 测试能力总览 |
| `capabilities/test/android-test.md` | 已有占位 | app androidTest 支持范围、baseline、sourcePath、class/method、extras、runner |
| `capabilities/test/library-test-apk.md` | 待新增 | library test APK lazy backfill、build history、module belongs、多 APK test 归属 |
| `capabilities/test/test-results-ui.md` | 待新增 | SM Test Runner、Test Results 树、source navigation、rerun failed |
| `capabilities/test/logcat-attribution.md` | 待新增 | instrumentation 输出解析、按 method 归档 logcat、失败诊断 |

#### 2.4.4 Tools / AI / Automation

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `capabilities/tools/index.md` | 已有占位 | 工具能力总览 |
| `capabilities/tools/cli.md` | 已有占位 | 16 个公开子命令、全局参数、输出模式、端口缓存、并发策略 |
| `capabilities/tools/mcp.md` | 已有占位 | 18 个注册工具、协议、返回模型、错误码、异步编译 |
| `capabilities/tools/mcp-tool-reference.md` | 待新增 | `version`、`list-projects`、`compile`、`deploy`、`clean-reinstall`、`gradle-build`、`instrument`、`get-compile-status`、`status`、`restart`、`devices`、`ssh-info`、`layout-dump`、`view-locate`、`view-inspect`、`tap`、`activity-stack`、`wait-logs` |
| `capabilities/tools/ui-automation.md` | 待新增 | ViewHierarchy LocalSocket、HTML dump、元素定位、属性读取、点击、Activity 栈、日志等待 |
| `capabilities/tools/layout-verify.md` | 待新增 | 无 Figma UI 证据链、有 Figma layout verify、元素匹配、spacing/alignment 校验、容差与局限 |
| `capabilities/tools/agent-skills.md` | 待新增 | skill 安装、hooks、permission rules、CLI 自动更新、agent setup 文档导出 |
| `capabilities/tools/remote-diagnosis.md` | 待新增 | SSH info 申请、用户确认、远端排障边界 |

### 2.5 Troubleshooting

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `troubleshooting/index.md` | 已有占位 | 按现象进入：编译失败、部署失败、运行时 crash、Debug、AndroidTest、MCP/UI 工具 |
| `troubleshooting/logs.md` | 已有占位 | `build/jugg/log/compile_latest.log`、Jugg 日志目录、MCP fetch 产物、日志等级含义 |
| `troubleshooting/compile.md` | 已有占位 | 每次都 Gradle 回退、Kotlin 编译错误、JDK 25+ Kotlin INTERNAL_ERROR、R/资源/Manifest/DataBinding 失败 |
| `troubleshooting/deploy.md` | 已有占位 | install/code swap/full swap 失败、ADB offline、recover/retry、direct overlay dirty、设备/缓存状态不一致 |
| `troubleshooting/runtime.md` | 已有占位 | release 增量 crash：NoClassDefFoundError、IllegalAccessError、AbstractMethodError、NoSuchMethodError、注解类型不匹配 |
| `troubleshooting/android-test.md` | 待新增 | gutter 不出现、sourcePath 解析失败、baseline 未开启、instrumentation 失败、Test Results/rerun failed 异常 |
| `troubleshooting/debug.md` | 待新增 | Debug attach 失败、断点不可用、AS XDebugger API 差异、单设备限制 |
| `troubleshooting/mcp-cli.md` | 待新增 | MCP 端口不通、projectDir 解析失败、异步 job 丢失、CLI JSON/rich/plain 输出误判 |
| `troubleshooting/ui-tools.md` | 待新增 | layout socket 连接失败、ViewHierarchy Server 未就绪、元素多命中、坐标 dp/px 误判、tap 不生效 |
| `troubleshooting/performance.md` | 待新增 | IDE 卡顿、启动期卡死、APK 数据库初始化慢、ConstRef 扫描慢 |

### 2.6 Reference

| 页面 | 状态 | 必须覆盖内容 |
|---|---|---|
| `reference/index.md` | 已有占位 | Reference 总入口 |
| `reference/compatibility.md` | 已有占位 | Android Studio 版本：chipmunk/giraffe/hedgehog/iguana/meerkat/narwhal/narwhal_feature/otter/panda/quail；平台兼容层 |
| `reference/glossary.md` | 已有占位 | 增量编译、Gradle fallback、code swap、full swap、clean reinstall、JVMTI、MCP、BuildTarget 等术语 |
| `reference/cli-commands.md` | 待新增 | 16 个 CLI 子命令参数表与示例 |
| `reference/mcp-tools.md` | 待新增 | 18 个 MCP tool schema、返回字段、错误码 |
| `reference/configuration.md` | 待新增 | Run Configuration、Android Test toggle、全局设置、环境变量、缓存路径 |
| `reference/log-files.md` | 待新增 | 日志文件、产物目录、cache、deploy history、MCP fetch 目录 |
| `reference/modules.md` | 待新增 | 项目模块与职责：main、idea、deploy_compat、cmd_line、jvmti_agent、custom_compilers、aapt2-inclink、platform_compat |
| `reference/limits.md` | 待新增 | 增量编译/部署限制、UI 工具限制、AndroidTest 限制、release/minify 限制 |

## 3. 功能域完整清单

### 3.1 IDE 插件与基础工作流

必须上 Wiki 的功能：

- 插件初始化：项目打开、`JuggManager` 初始化、项目上下文恢复。
- Gradle Sync 监听：Sync 后重新读取项目信息、依赖变更与上下文重建。
- Run Configuration：Jugg App Run Configuration、配置项、与普通 Android Run 的区别。
- 编译部署主链路：`JuggRunningTask` 串联 compile -> deploy。
- More Options 菜单：Gradle compile、restart、安装 skill、检查更新、报告问题等用户入口。
- 插件更新/热更新：Jugg loader、hot update downloader、版本检查入口。
- 用户确认 UI：构建变更确认、错误弹窗、远端编译应用确认、账号密码输入等。

建议页面归属：`onboarding/*`、`guide/compile.md`、`guide/deploy.md`、`reference/configuration.md`、`troubleshooting/logs.md`。

### 3.2 项目模型与 Gradle 集成

必须上 Wiki 的功能：

- Gradle 项目信息读取：模块、variant、classpath、manifest、sourceSets、build path。
- `JuggProjectInfo` / `ModuleInfo` / `ModuleBuildPathInfo` 快照。
- APK 归属：base APK、app test APK、library self-targeting Test APK。
- 依赖变更检测：Gradle diff 与 Sync diff。
- 本地 Gradle 编译客户端：full build / fallback / required APK 查找。
- 远端 Gradle 编译客户端：SSH、rsync、远端命令、产物拉取、auth retry。
- Configuration cache 兼容、init script 注入与 manifest XML 辅助。
- 项目路径、全局路径、缓存目录和持久化数据。

建议页面归属：`concepts/project-model.md`、`guide/remote-gradle.md`、`capabilities/compile/gradle-fallback.md`、`reference/configuration.md`。

### 3.3 编译系统

必须上 Wiki 的功能：

- 增量编译总控：阶段顺序、compile task、重试、fallback checker。
- Java 编译、Kotlin 编译、Kotlin 编译宿主兼容、Kotlin metadata 合并。
- APT/KSP：`JuggAptCompiler`、processor 注册、Kuikly page processor、生成源码改写。
- Dex 编译：dex file maker、dex merger、R8 file maker。
- 资源编译：resource overlay、asset overlay、aapt2 daemon、arsc、R/RDEx、styleable、包名重写。
- DataBinding/ViewBinding：base classes、mapper、BR、layout include、stripped XML、日志合并。
- Manifest：diff、merge、activity 信息、二进制 XML 读取。
- Release/minify：R8 mapping、usage.txt、class/dex obfuscator、`_jugg_fix` compatibility stub。
- 常量引用分析：Java/Kotlin const parser、DB/cache、repo/worktree 共享 fingerprint、受影响源码解析。
- 自定义编译器：SPI、远端 jar、示例 compiler、编译交互 UI。
- 增量限制与 Gradle 回退：依赖变化、注解处理、字节码插桩、复杂脚本、release 风险。

建议页面归属：`concepts/incremental-compile.md`、`concepts/compile-pipeline.md`、`capabilities/compile/*`、`troubleshooting/compile.md`、`troubleshooting/runtime.md`。

### 3.4 部署系统

必须上 Wiki 的功能：

- 部署策略：install、code swap、full swap、clean reinstall、restart、hot reload。
- `JuggDeployData`、`DeployItem`、`LaunchResult`、`targetApkPaths`。
- 部署文件准备：deploy file manager、state tracker、planner、class file lookup、APK install order。
- 影响分析与部署数据生成：类结构变化、父子类传播、`EffectedType`、inline method、release/minify 补偿。
- 常量引用对部署影响：const ref effected source 与编译前影响查询。
- deploy history 与 deployment cache：full build info、snapshot、设备状态一致性。
- recover/retry：recover deploy state、dry deploy、ADB transport recovery、transient offline、retry host。
- Direct Overlay：writer、state checker、startup agent push、swap transport、dirty 语义。
- 多 APK/AndroidTest 部署：app APK、test APK、library Test APK、安装顺序。
- 多设备：设备选择、结果汇总、单设备失败口径。

建议页面归属：`concepts/deploy-strategy.md`、`concepts/deploy-data-and-impact.md`、`capabilities/deploy/*`、`troubleshooting/deploy.md`。

### 3.5 JVMTI / Runtime Agent / ViewHierarchy

必须上 Wiki 的功能：

- JVMTI agent 与部署协同、兼容检测、native agent 启动。
- hotfix loader：dex patch、resources patch、class loader 修补、bootstrap application/app component factory。
- Android 15 apply changes fixer、instrumentation hooks、breadcrumb。
- ViewHierarchy LocalSocket Server：View tree dump、元素定位、点击、表达式读取。
- Compose tree / Kuikly view 文本提取与局限。
- Runtime 失败与 release 增量 crash 排查。

建议页面归属：`concepts/jvmti-agent.md`、`capabilities/deploy/jvmti-runtime.md`、`capabilities/tools/ui-automation.md`、`troubleshooting/runtime.md`、`troubleshooting/ui-tools.md`。

### 3.6 Android Test

必须上 Wiki 的功能：

- BuildTarget.ANDROID_TEST 与普通 app run 的区别。
- test APK 识别、sourcePath target 解析、synthetic ModuleInfo。
- full build baseline 与 `enabledAndroidTest` 状态。
- 增量编译进入目标 test APK。
- IDE gutter、临时 RunConfig、AndroidTest RunSpec。
- `am instrument` 命令构造、runner override、extras、class/method 过滤。
- app/test APK 部署策略、library Test APK lazy backfill、build history。
- instrumentation 输出解析、console rendering、SM Test Runner service message。
- Test Results 树、source navigation、rerun failed。
- logcat 按 method 归因。

建议页面归属：`guide/android-test.md`、`concepts/android-test-flow.md`、`capabilities/test/*`、`troubleshooting/android-test.md`。

### 3.7 Debug Attach

必须上 Wiki 的功能：

- Jugg Debug Program Runner 如何接管 Debug executor。
- 成功部署后的单设备 Java debugger attach。
- Android Studio XDebugger / deployer API 兼容边界。
- Debug 断点不可用的现象与排查入口。
- Debug 与普通 Jugg Run、AndroidTest 的差异。

建议页面归属：`guide/debug.md`、`troubleshooting/debug.md`、`concepts/compatibility-layer.md`。

### 3.8 MCP、CLI 与 Agent 能力

必须上 Wiki 的功能：

- MCP 服务：端口 `12320..12329`、`/jugg-mcp`、JSON-RPC、请求头、返回模型。
- MCP 注册工具：`version`、`list-projects`、`restart`、`compile`、`deploy`、`clean-reinstall`、`gradle-build`、`instrument`、`get-compile-status`、`ssh-info`、`devices`、`layout-dump`、`view-locate`、`view-inspect`、`activity-stack`、`tap`、`status`、`wait-logs`。
- MCP 异步编译：`jobId`、`waitTimeoutMs`、失败 detail、artifact。
- CLI 公开子命令：`version`、`compile`、`deploy`、`gradle-build`、`clean-reinstall`、`restart`、`instrument`、`status`、`layout-dump`、`view-locate`、`view-inspect`、`tap`、`devices`、`activity-stack`、`ssh-info`、`wait-logs`。
- CLI 全局行为：`projectDir` 自动解析、端口缓存、`plain/rich/json`、`--if-compiling wait|interrupt`、help 不连 MCP、Ctrl-C。
- UI 工具：layout dump HTML、元素定位、多命中、getter 白名单、dp 坐标、tap 坐标/百分比/元素模式。
- wait logs：marker/crash/timeout 判停、deploy/restart timestamp 作为日志起点。
- layout verify / figma-layout-verify：Figma JSON 解析、关系提取、元素匹配、容差、报告口径。
- Agent 安装：Codex、Claude、CodeBuddy、Cursor、Gemini；skill、hooks、permission rules、CLI symlink/PATH、setup doc 导出。

建议页面归属：`guide/cli.md`、`guide/mcp.md`、`guide/ui-inspection.md`、`capabilities/tools/*`、`reference/cli-commands.md`、`reference/mcp-tools.md`、`troubleshooting/mcp-cli.md`、`troubleshooting/ui-tools.md`。

### 3.9 兼容层、命令行模块与扩展模块

必须上 Wiki 的功能：

- Android Studio 版本兼容：chipmunk、giraffe、hedgehog、iguana、meerkat、narwhal、narwhal_feature、otter、panda、quail。
- `IAsDeployerCompat`、install session、overlay/cache wrapper、Java debugger attach 兼容。
- `platform_compat/base_api`：main 脱离 IDE 编译与测试的兼容桩。
- `cmd_line`：非 IDE 场景的基础 Gradle build / incremental build 命令。
- `custom_compilers`：SPI 示例。
- `aapt2-inclink`：三平台 aapt2 增量链接资源。
- `jvmti_agent`：native + Java runtime 能力。

建议页面归属：`reference/compatibility.md`、`reference/modules.md`、`capabilities/compile/custom-compiler.md`、`concepts/compatibility-layer.md`。

### 3.10 公共工具与诊断基础设施

必须上 Wiki 的功能：

- APK 工具：APK 读取/修改、manifest activity 定位、资源 APK 修改。
- Git/worktree：变更文件匹配、worktree repository、依赖变更输入。
- Logger：`JuggLogger` 日志等级、文件日志、日志轮转、输出到用户/开发者的边界。
- Server：remote compile applier、Jugg server chooser、run configuration template。
- 路径与缓存：项目级路径、全局路径、MCP fetch 清理、deploy history DB、const ref DB。

建议页面归属：`troubleshooting/logs.md`、`reference/log-files.md`、`reference/modules.md`、`reference/configuration.md`。

## 4. 现有占位页到功能域映射

| 现有页面 | 已覆盖功能域 | 还缺的关键子页 |
|---|---|---|
| `guide/compile.md` | 编译操作入口 | CLI/MCP 编译、远端 Gradle、自定义编译器、full build baseline |
| `guide/deploy.md` | 部署操作入口 | Direct Overlay、recover/retry、多 APK、多设备、restart/hot reload |
| `guide/android-test.md` | AndroidTest 操作入口 | library Test APK、SM Test Runner、logcat 归因 |
| `guide/debug.md` | Debug 操作入口 | attach 生命周期、断点排查、兼容边界 |
| `concepts/incremental-compile.md` | 增量编译概念 | compile pipeline、const ref、release/minify、DataBinding/resource/manifest |
| `concepts/deploy-strategy.md` | 部署策略概念 | deploy data/impact、direct overlay、history/cache、JVMTI |
| `capabilities/tools/cli.md` | CLI 能力入口 | 完整命令 reference、全局行为、输出模式 |
| `capabilities/tools/mcp.md` | MCP 能力入口 | 18 工具 reference、UI automation、layout verify |
| `troubleshooting/compile.md` | 编译失败排查 | resource/DataBinding/Manifest/release/JDK/Kotlin/Gradle fallback 细分 |
| `troubleshooting/deploy.md` | 部署失败排查 | ADB、recover/retry、direct overlay、cache/device 状态 |
| `troubleshooting/runtime.md` | Runtime crash 排查 | release/minify crash 类型、JVMTI/hotfix loader |
| `reference/compatibility.md` | 兼容性入口 | AS 版本矩阵、platform/cmd_line/aapt2/jvmti |

## 5. 写作优先级建议

### P0：用户立即需要的主链路

1. `onboarding/first-run.md`
2. `guide/compile.md`
3. `guide/deploy.md`
4. `guide/android-test.md`
5. `guide/cli.md`
6. `guide/mcp.md`
7. `troubleshooting/logs.md`
8. `troubleshooting/compile.md`
9. `troubleshooting/deploy.md`
10. `reference/compatibility.md`

### P1：能力完整性与高级工作流

1. `capabilities/compile/*`
2. `capabilities/deploy/*`
3. `capabilities/test/*`
4. `capabilities/tools/mcp-tool-reference.md`
5. `capabilities/tools/ui-automation.md`
6. `concepts/compile-pipeline.md`
7. `concepts/deploy-data-and-impact.md`
8. `concepts/android-test-flow.md`
9. `troubleshooting/android-test.md`
10. `troubleshooting/mcp-cli.md`

### P2：参考与内部能力外显

1. `reference/cli-commands.md`
2. `reference/mcp-tools.md`
3. `reference/configuration.md`
4. `reference/log-files.md`
5. `reference/modules.md`
6. `reference/limits.md`
7. `concepts/compatibility-layer.md`
8. `capabilities/tools/agent-skills.md`
9. `guide/custom-compiler.md`
10. `guide/remote-gradle.md`

## 6. 后续验收清单

正式补全文档时，每轮都按以下清单验收：

- [ ] 新增英文页面时同步创建或更新中文镜像页面。
- [ ] 新增页面后同步 `docs/wiki/.vitepress/config.mts` nav/sidebar。
- [ ] 每个功能至少在一个 Capability 或 Reference 页面出现。
- [ ] 每个用户主链路功能至少有 Guide 页面。
- [ ] 每个高频失败场景至少有 Troubleshooting 入口。
- [ ] 每个工具命令都有参数、返回、限制或链接到 reference。
- [ ] `npm run wiki:build` 通过，无死链。
- [ ] 如果写入能力边界，先从 `docs/ai_knowledge` 或当前实现核实；不确定时标注“待核实”，不要写成确定事实。
