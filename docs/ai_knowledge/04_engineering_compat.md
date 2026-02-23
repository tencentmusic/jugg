# 工程化：兼容层与命令行模块

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖四类“工程边界”模块：
- Android Studio deploy 兼容层
- 平台 API mock
- 命令行入口
- 自定义编译器示例

---

## 2. deploy_compat（AS 版本兼容）

| 目录 | 说明 |
|------|------|
| `deploy_compat/interface` | 兼容接口与通用模型（`IAsDeployerCompat` 等） |
| `deploy_compat/v_chipmunk` | Chipmunk 兼容实现 |
| `deploy_compat/v_giraffe` | Giraffe 兼容实现 |
| `deploy_compat/v_hedgehog` | Hedgehog 兼容实现 |
| `deploy_compat/v_iguana` | Iguana 兼容实现 |
| `deploy_compat/v_meerkat` | Meerkat 兼容实现 |
| `deploy_compat/v_narwhal` | Narwhal 兼容实现 |
| `deploy_compat/v_narwhal_feature` | Narwhal Feature 兼容实现 |
| `deploy_compat/v_otter` | Otter Feature 兼容实现 |

IDE 侧统一通过 `AsDeployerCompat` 调用，业务层无需关心具体版本实现。

---

## 3. platform_compat（平台桩）

目录：`platform_compat/base_api/src/main/java`  
职责：提供 IntelliJ / Android 相关 API 的 mock/stub，使 `main` 可独立编译和测试。

---

## 4. cmd_line（命令行）

目录：`cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline`  
关键入口：
- `CmdLine.kt`
- `base/BuildGradleBaseCommand.kt`
- `incremental/BuildIncrementalApkCommand.kt`

用于无 IDE 场景执行基础构建与增量构建链路。

---

## 5. custom_compilers（扩展示例）

目录：`custom_compilers/src/main/java/com/sickworm/intellij/jugg/compiler/demo`  
示例类：
- `ExampleAssembleCustomCompiler`
- `ExampleHookInitCustomCompiler`
- `ExampleDelayCustomCompiler`

用于演示 `ICompilerCreator` SPI 接入方式。

---

## 6. 常见问题定位

- “某 IDE 版本部署 API 崩溃”：先看对应 `deploy_compat/v_*` 实现。  
- “main 模块调用 IDE API 失败”：检查 `platform_compat` 是否缺失桩。  
- “CLI 行为与 IDE 不一致”：对照 `CmdPlatformApi` 与 `IdeaPlatformApi` 差异。

---

## 7. 关联文档

- IDE 层：`04_engineering_ide.md`
- 项目模型：`04_engineering_project.md`
- 自定义编译器：`02_compile_custom_ui.md`
