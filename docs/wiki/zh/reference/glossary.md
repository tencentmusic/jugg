---
title: 术语表
description: 解释 Jugg Wiki 和工具输出中常见的编译、部署、测试、MCP 与缓存术语。
status: active
tags:
  - reference
  - glossary
---

# 术语表

本页只解释 Jugg Wiki、CLI/MCP 输出和日志里会反复出现的稳定术语。中文术语用于正文表达，英文术语用于对应代码、日志、界面或英文文档。它不展开机制细节；如果一个词只在单篇文章里出现，优先在原文就地解释。

## 编译

| 中文术语 | 英文术语 | 含义 |
|---|---|---|
| 增量编译 | Incremental compilation | 只处理本轮变更影响到的源码、资源、Manifest、DEX 或其他产物。 |
| Gradle 回退 | Gradle fallback | Jugg 判断增量链路不可靠或用户强制要求时，转为完整 Gradle 构建。 |
| 完整构建基线 | Full build baseline | Jugg 通过一次完整构建建立的工程、类路径（classpath）、APK 和部署历史基线。 |
| 构建目标 | Build target | 当前构建目标，例如普通 App 或 Android Test。 |
| 源码编译 | Source compilation | 处理 Java/Kotlin 源码、注解处理和 DEX 输出的编译阶段。 |
| 资源编译 | Resource compilation | 处理 `res/`、`assets/`、资源表和资源 APK 的编译阶段。 |
| Manifest 编译 | Manifest compilation | 处理 AndroidManifest 合并、差异和安装包声明的阶段。 |
| 常量引用分析 | Const-ref | 编译期常量定义和引用影响分析，用于判断常量变更需要重编译哪些源码。 |
| 重编译 | Recompilation | 声明变化影响未修改源码时，把受影响源码追加到本轮编译。 |
| 扩散编译 | Recompilation propagation | 根据引用、继承、常量或生成源码影响继续扩大重编译范围，直到没有新的受影响源码。 |

## 部署

| 中文术语 | 英文术语 | 含义 |
|---|---|---|
| 部署 | Deploy | 把编译产物安装或更新到设备，并按策略启动、重启或热更新 App。 |
| 增量部署 | Incremental deployment | 只把本轮变化需要的代码、资源或覆盖层（overlay）更新到设备。 |
| 清理重装 | Clean Reinstall | 清除数据并重新安装 APK，用于恢复不一致状态。 |
| 代码替换 | Code Swap | 只下发可热替换的代码变化，尽量避免重启 App。 |
| 完整替换 | Full Swap | 下发更完整的变更集合，通常比 Code Swap 更重，可能需要重启。 |
| 热重载 | Hot Reload | 部署后不重启 App 的快速路径，要求变更满足运行时热更新条件。 |
| 热修复 | Hot Fix | 更保守的部署路径，通常会强制重启 App。 |
| 应用变更 | Apply Changes | Android Studio 提供的运行时代码替换和资源更新机制。 |
| 直接覆盖部署 | Direct Overlay | 不依赖 App 进程在线的 overlay 部署快捷路径。 |
| 兼容部署 | Compatibility deployment | 设备或系统不适合默认部署路径时使用的兼容模式部署。 |
| 部署状态恢复 | Deployment state recovery | 设备状态、部署历史或缓存不一致时，重新建立可信部署状态。 |
| 部署自愈 | Deployment self-healing | 部署失败后根据已知失败条件选择有限重试、扩大恢复范围或重新安装。 |
| 部署历史 | Deploy History | Jugg 记录的已部署 APK、overlay、DEX 和设备状态历史。 |

## 项目和缓存

| 中文术语 | 英文术语 | 含义 |
|---|---|---|
| 工程信息 | Project info | Jugg 从 IDE 和 Gradle 读取的模块、源码集（source set）、构建变体（variant）、依赖和 APK 信息快照。 |
| 工程信息刷新 | Project info refresh | 重新读取或恢复工程快照，使后续编译使用当前模块、构建变体、依赖和产物信息。 |
| 编译上下文 | Compile context | 增量编译使用的模块、类路径（classpath）、依赖和构建目标上下文。 |
| 暂存目录 | Staging | 本轮增量编译输出的临时部署目录，位于 `build/jugg/build/staging/`。 |
| 类路径备份 | Classpath backup | Jugg 保存的类路径、APK、依赖库备份和内嵌 APK 缓存。 |
| 包含构建 | Included build | Gradle 复合构建（composite build）中被合并进主工程模型的外部构建。 |

## androidTest

| 中文术语 | 英文术语 | 含义 |
|---|---|---|
| 插桩测试 | Instrumentation | Android `am instrument` 测试运行方式。 |
| 测试 APK | Test APK | 包含 androidTest 代码和测试运行器的 APK。 |
| 库测试 APK | Library Test APK | 库模块自测时生成的测试 APK。 |
| 合成测试模块 | Synthetic module | Jugg 为 androidTest 源码集构造的测试模块视图。 |
| 重新运行失败项 | Rerun failed | 只重新运行上次失败测试项的能力。 |

## MCP / CLI

| 中文术语 | 英文术语 | 含义 |
|---|---|---|
| 模型上下文协议 | MCP（Model Context Protocol） | Agent 调用 Jugg 的本地工具协议。 |
| 工具 | Tool | MCP 中可调用的能力，例如 `deploy`、`layout-dump`、`wait-logs`。 |
| 结构化内容 | Structured content | MCP 工具返回的结构化 JSON 结果。 |
| 产物 | Artifact | MCP 工具生成的文件产物，例如 UI HTML、日志窗口或转储文件（dump）。 |
| 工程目录参数 | `projectDir` | MCP 和 CLI 用来定位 IDE 中 Jugg 项目的绝对路径。 |
| 异步未完成状态 | `isFinal=false` | 编译类工具已启动异步任务，客户端需要继续轮询 `get-compile-status`。 |
