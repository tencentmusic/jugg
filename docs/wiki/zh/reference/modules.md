---
title: 模块
description: 汇总 Jugg 仓库主要模块、源码目录和职责边界。
status: active
tags:
  - reference
  - modules
---

# 模块

Jugg 仓库按 IDE 插件层、核心逻辑层、Android Studio 兼容层、命令行入口和辅助模块组织。理解模块边界有助于定位日志中的类名、判断问题属于编译、部署还是工具层。

## 顶层模块

| 模块 / 目录 | 职责 |
|---|---|
| `idea/src/main` | IDE 插件运行时逻辑：项目初始化、任务编排、部署、MCP runtime、UI 协作。 |
| `idea/src/ide_entry` | 插件入口和运行配置：loader、initializer、Run Configuration、line marker。 |
| `main/src/main/java/com/sickworm/intellij/jugg` | 核心逻辑：编译、部署数据、项目模型、Gradle 集成、MCP action、工具模块。 |
| `deploy_compat/*` | Android Studio deployer API 多版本适配。 |
| `platform_compat/base_api` | IntelliJ / Android API 编译期桩，支撑 `main` 独立编译和测试。 |
| `cmd_line/src/main/java` | 无 IDE 场景的命令行入口。 |
| `custom_compilers/src/main/java` | 自定义编译器 SPI 示例。 |
| `jvmti_agent/src/main/cpp` | JVMTI native agent。 |
| `jvmti_agent/src/main/java` | App 内 ViewHierarchy LocalSocket server 等运行时能力。 |
| `aapt2-inclink/src/main/resources/tools` | 三平台 aapt2 增量链接工具资源。 |
| `docs/wiki` | 用户 Wiki。 |
| `docs/skills/jugg-android-dev-loop` | 面向 Agent 的 Jugg CLI / skill 封装。 |

## 核心源码目录

| 领域 | 目录 | 说明 |
|---|---|---|
| 编译总控 | `main/.../compiler/core` | 增量编译主流程、阶段顺序和重编译循环。 |
| 源码编译 | `main/.../compiler/source` | Java/Kotlin、APT/KSP/KAPT、dex 编译。 |
| 资源编译 | `main/.../compiler/overlay`、`main/.../aapt2` | 资源、overlay、aapt2 link。 |
| DataBinding | `main/.../compiler/databinding` | DataBinding / ViewBinding 增量处理。 |
| Manifest | `main/.../compiler/manifest` | Manifest 差异和合并。 |
| 混淆 | `main/.../compiler/obfuscation` | release 混淆映射、usage、dex/class 重混淆。 |
| 常量引用 | `main/.../compiler/constref` | 常量定义/引用扫描和影响分析。 |
| 部署核心 | `main/.../deploy/core` | 部署文件准备、历史、状态和 facade。 |
| 部署数据 | `main/.../deploy/data` | 类结构变化传播和 deploy data 生成。 |
| Android Test | `main/.../deploy/instrument` | instrumentation 参数、解析、输出渲染和 Test Results 映射。 |
| 项目模型 | `main/.../project`、`main/.../project/data` | 路径、模块快照、APK 归属、全局目录。 |
| Gradle 集成 | `main/.../gradle` | Gradle project info 读取、本地/远端构建、APK 查找。 |
| MCP | `main/.../ai/mcp` | MCP 协议、工具注册、action、ViewHierarchy 通信。 |
| 公共工具 | `main/.../apk`、`main/.../git`、`main/.../logger`、`main/.../server` | APK 修改、Git、日志、远端服务等公共能力。 |

## IDE 层入口

| 入口 | 文件 |
|---|---|
| IDE 总管理器 | `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt` |
| 运行任务编排 | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt` |
| 编译入口 | `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt` |
| 部署入口 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` |
| 核心部署器 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployer.kt` |
| 部署状态 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/DeployStateManager.kt` |
| Debug attach | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggDebugSessionManager.kt` |
| Run Configuration | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfiguration.kt` |
| Android Test Run Configuration | `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt` |

## 兼容层

`deploy_compat/interface` 定义中立接口和 wrapper，各 `deploy_compat/v_*` 模块实现对应 Android Studio 版本 API。IDE 主路径不应直接依赖旧 deployer runtime 类型，必须通过兼容接口访问 install、swap、deployment cache 和 Debug attach 能力。

## 文档和工具模块

| 目录 | 说明 |
|---|---|
| `docs/ai_knowledge` | 面向维护者和 AI 的内部知识库。 |
| `docs/wiki` | 面向用户的 VitePress Wiki。 |
| `docs/task` | 任务方案和专项分析文档。 |
| `docs/skills/jugg-android-dev-loop/scripts` | `jugg` CLI 脚本和子命令封装。 |

## 相关页面

- [Jugg 工作原理](../concepts/how-jugg-works.md)
- [编译流水线](../concepts/compile-pipeline.md)
- [部署数据与影响分析](../concepts/deploy-data-and-impact.md)
- [兼容层](../concepts/compatibility-layer.md)
