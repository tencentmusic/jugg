# Jugg 单元测试覆盖现状与 TDD 差距报告（静态盘点）

> **历史盘点**（2026-03-01）。现行测试策略见 [`06_testing.md`](../ai_knowledge/06_testing.md)。

> 日期：2026-03-01  
> 口径：**未运行任何测试**，仅基于代码与测试目录做静态分析。  
> 一致性：文档与代码冲突时，以代码为准。

---

## 1. 覆盖现状快照

### 1.1 模块级统计（src/main vs src/test）

| 模块 | 生产代码文件数（.kt/.java） | 测试源码文件数（.kt/.java） | 测试类文件数（*Test） | `@Test` 方法数 |
|---|---:|---:|---:|---:|
| `main` | 259 | 52 | 34 | 249 |
| `idea` | 57 | 75 | 43 | 209 |
| `cmd_line` | 14 | 3 | 1 | 5 |
| `jvmti_agent` | 14 | 1 | 1 | 1 |
| `custom_compilers` | 4 | 0 | 0 | 0 |
| `deploy_compat/interface` | 5 | 0 | 0 | 0 |
| `deploy_compat/v_*`（8个版本模块） | 10 | 0 | 0 | 0 |
| `platform_compat/base_api` | 21 | 0 | 0 | 0 |
| `aapt2-inclink` | 0 | 0 | 0 | 0 |

### 1.2 核心域分布（main 模块）

`main` 的测试几乎全部集中在以下目录：
- `compiler`（148 个 `@Test`）
- `deploy`（41 个 `@Test`）
- `mcp`（59 个 `@Test`）
- `tools`（1 个 `@Test`）

`main` 生产域中以下目录**没有同模块直接单测目录**：
- `gradle`
- `project`
- `apk`
- `aapt2`
- `git`
- `server`
- `logger`
- `platform`
- `ide`

### 1.3 核心域分布（idea 模块）

`idea` 的测试集中在：
- `compile`（97 个 `@Test`）
- `compiler`（28 个 `@Test`）
- `manager`（25 个 `@Test`）
- `project`（15 个 `@Test`）
- `gradle`（11 个 `@Test`）

`idea` 生产入口层关键类（如 `JuggRunningTask`、`JuggCompilerHelper`、`JuggDeployer`）未发现直接命名级单测（见第 2.2）。

---

## 2. 以 TDD 标准衡量的主要差距

## 2.1 测试金字塔失衡：集成测试偏重，单元层不足

现有测试中大量依赖工程样例、文件系统和外部环境（设备/IDE），而纯函数级、决策级单测不足。  
这会导致：
- Red 阶段定位慢
- 失败原因不稳定
- Refactor 时回归半径过大

## 2.2 高风险核心逻辑缺少“直接单测”

以下关键类未检索到测试源码中的直接引用（`src/test` 中 class-name match 为 0），属于 TDD 的优先补齐项：

- `IncrementalCompilerHelper`（编译循环、重试、fallback 决策）
- `GradleProjectInfoReader`（Gradle 反射读取与构建信息解析）
- `GradleDependencyDiffer`
- `McpRequestValidator`（MCP 参数校验主逻辑）
- `JuggRunningTask`（Run 主链路编排）
- `JuggDeployer`
- `ClassMinifyCompiler`
- `DexMinifyCompiler`

说明：
- 并不代表这些类 100% 未被间接覆盖；表示“缺少可追溯、可读、可维护的直接单元测试”。

## 2.3 测试可重复性问题（违背 TDD 的快速稳定反馈）

### 2.3.1 设备依赖测试未隔离到专用测试任务

存在 `@RequiresDevice` 测试，但未发现任务级过滤/分层机制：
- `idea/.../DeployTargetManagerTest.kt`
- `idea/.../TopLevelFlowTest.kt`
- `idea/.../TopLevelFlowWithGitTest.kt`
- `main/.../JuggJvmtiAgentManagerTest.kt`

### 2.3.2 本地路径耦合测试

`idea/src/test/java/local/idea/LocalTest.kt` 包含多处硬编码绝对路径（`/Users/...`），难以在 CI 或多人环境复现。

### 2.3.3 测试产物写入源码目录

`cmd_line` 测试输出目录指向 `cmd_line/src/test/build`，会污染测试源码树并干扰静态统计与审查。

## 2.4 断言质量问题

至少两个测试方法存在“仅执行不验证”的情况：
- `idea/.../server/JuggServerTest.kt`
- `idea/.../deploy/DeployTargetManagerTest.kt`

## 2.5 过程与门禁缺失（TDD 落地能力不足）

未发现以下工程化能力：
- 覆盖率门禁（Jacoco/Kover）
- 变更影响覆盖阈值（diff coverage）
- 失败类型分层（unit / integration / device）

---

## 3. 优先级 TODO（面向“达到可执行 TDD”）

## P0（必须先做，建立 TDD 基线）

1. 建立测试分层与任务拆分（`unitTest` / `integrationTest` / `deviceTest`）  
验收：
- `unitTest` 不依赖设备、不依赖外部项目路径、可在 CI 直接运行。
- `@RequiresDevice` 测试默认不进入 `unitTest`。

2. 为关键决策类补齐直接单测（先从最影响主链路的 4 个类开始）  
建议顺序：
- `IncrementalCompilerHelper`
- `JuggRunningTask`
- `JuggCompilerHelper`
- `McpRequestValidator`
验收：
- 每个类至少覆盖：成功路径、失败路径、fallback/重试路径、取消/异常路径。

3. 修复“无断言测试”  
验收：
- `JuggServerTest`、`DeployTargetManagerTest` 增加可验证断言，或下沉到手工/集成测试集。

4. 清理非可移植测试样例  
验收：
- `LocalTest` 移出默认测试集（或改造成参数化、本地可配置路径并默认禁用）。

5. 调整测试输出目录  
验收：
- `cmd_line` 测试产物移至模块 `build/` 目录，不再写入 `src/test`。

## P1（提高回归质量）

1. 为 `GradleProjectInfoReader` / `GradleDependencyDiffer` 补“最小可控输入”的单测夹具  
目标：覆盖 Gradle 反射解析失败、空配置、多变体、插件差异分支。

2. 为部署与混淆关键类补边界测试  
目标类：
- `ClassMinifyCompiler`
- `DexMinifyCompiler`
- `JuggDeployer`

3. 统一测试命名规范  
目标：
- `@Test` 所在类统一以 `*Test` 结尾，减少漏扫与维护成本。

## P2（TDD 工程化闭环）

1. 引入覆盖率工具与门禁  
建议：
- 模块维度阈值 + 变更集阈值（diff coverage）。

2. 新增 PR 模板约束  
目标：
- 每个功能改动必须附“先写失败测试 -> 再实现 -> 再重构”证据（至少测试提交顺序或说明）。

3. 建立“高风险类清单”的守护规则  
目标：
- 对关键类修改必须伴随对应测试更新，否则阻断合入。

---

## 4. 结论

当前项目的测试总量并不低，但从 TDD 的“快速、稳定、可演进”标准看，主要短板是：
- 高风险核心决策类的直接单测不足
- 测试分层不足导致环境依赖和不稳定因素混入默认测试集
- 缺少覆盖率与流程门禁，无法形成持续的 Red-Green-Refactor 闭环

优先完成 P0 后，项目即可从“有较多测试”升级为“具备可执行 TDD 能力”。

