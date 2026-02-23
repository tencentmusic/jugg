# Jugg 项目概览（AI 速读版）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页用于让 AI 在最短时间建立全局认知：
- Jugg 是什么
- 主要模块在哪里
- 典型任务应从哪个入口开始

不承载实现细节；细节请转到 `02/03/04/05/08` 专题。

---

## 2. 项目一句话

**Jugg** 是 Android Studio / IntelliJ 插件，核心目标是：在保留 Gradle 构建产物前提下，尽量走旁路增量编译与部署，减少完整 Gradle 构建频次。

---

## 3. 模块总览（按代码目录）

| 模块 | 目录 | 职责 |
|------|------|------|
| IDE 插件层 | `idea/src/main` + `idea/src/ide_entry` | 运行配置、任务编排、IDE 事件、UI 与 MCP runtime |
| 核心逻辑层 | `main/src/main/java/com/sickworm/intellij/jugg` | 编译、部署、项目模型、Gradle/远端编译、MCP 协议与工具 |
| Android Studio 兼容层 | `deploy_compat/*` | 多版本 deploy API 适配（chipmunk/giraffe/hedgehog/iguana/meerkat/narwhal 等） |
| 平台兼容桩 | `platform_compat/base_api` | IntelliJ/Android API mock，支撑 `main` 脱离 IDE 编译 |
| 命令行入口 | `cmd_line/src/main/java` | 无 IDE 场景的基础构建/增量构建命令 |
| 自定义编译器示例 | `custom_compilers/src/main/java` | `ICompilerCreator` SPI 扩展示例 |
| JVMTI Agent | `jvmti_agent/src/main/cpp` | 兼容部署场景下的 agent 能力 |
| AAPT2 增量链接二进制 | `aapt2-inclink/src/main/resources/tools` | 三平台（darwin/linux/windows）工具资源 |

---

## 4. 核心运行链路

1. IDE 侧通过 `JuggManager` 初始化项目上下文与运行能力。  
2. `JuggRunningTask` 统一编排“编译 -> 部署”。  
3. `JuggCompilerHelper` 决定增量或 Gradle 回退。  
4. 增量路径由 `JuggCompiler` 执行多阶段编译；Gradle 路径由 `LocalGradleCompileClient` / `RemoteGradleCompileClient` 执行。  
5. `JuggDeployerHelper` + `JuggDeployer` 完成 install / code swap / full swap。

---

## 5. 工作模式（实务视角）

- 增量编译 + 增量部署：默认优先路径。
- 兼容部署：当设备/JVMTI/结构变化不满足条件时切换策略。
- Gradle 回退：强制回退或自动回退时走完整 Gradle 构建。

---

## 6. 能力边界（避免误判）

- Jugg 旁路编译不等价于完整 Gradle pipeline。
- 涉及注解处理、字节码插桩、复杂构建脚本改动时，通常需要 Gradle 回退验证。
- MCP 工具能力以 `tools/list` 返回的 schema 与 `mcp/actions` 实现为准。

---

## 7. AI 任务入口建议

- 改编译流程：先看 `02_compile_core.md` + `98_code_map.md`。  
- 改部署行为：先看 `03_deploy_core.md` + `03_deploy_complete.md`。  
- 改项目/Gradle读取：先看 `04_engineering_project.md`。  
- 改 IDE 生命周期/运行配置：先看 `04_engineering_ide.md`。  
- 改 MCP 工具：先看 `08_mcp_usage.md` + `08_mcp_design.md`。

---

## 8. 延伸阅读

- 架构：`01_architecture.md`
- AI 检索入口：`97_ai_usage.md`
- 代码路径总表：`98_code_map.md`
- 总导航：`99_index.md`
