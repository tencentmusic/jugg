# 测试策略与 TDD（权威细则）

> 最后核对：2026-07-28
> 一致性规则：文档与代码冲突时，以代码为准。
> **与 AGENTS.md 关系**：`AGENTS.md` 只保留不可绕过的硬约束；**本页是测试价值判断、分层、选型、落点、TDD 和存量治理的唯一权威细则**。其他 `docs/task/*` 设计文档若与本页冲突，以本页为准。

---

## 0. 文档定位

本页是测试选型与 TDD 执行口径，不维护完整测试清单。查具体测试文件时，先按能力域 `rg` 当前 `main/src/test`、`idea/src/test`，再用本页判断该行为是否值得测试、由哪条用例负责，以及是否需要补 L3。

---

## 1. 测试分层

### 1.1 回归价值门禁（先于分层）

测试层级只决定测试落点，不能证明测试值得存在。新增或保留用例前，先确认它保护了一个**独立、稳定且可能被真实改坏的行为**。

有回归价值的测试通常满足：

1. 保护用户可见行为、业务规则、外部协议或命名约定、兼容性、恢复策略、并发状态、关键执行顺序等稳定契约。
2. 契约被真实破坏时，测试能够稳定失败，并通过确定性断言说明问题。
3. 有明确的主责用例（behavior owner）；其他测试未以相同粒度重复证明同一责任。
4. 断言关注可观察结果，不绑定可以自由调整的私有结构。

以下理由默认不足以新增或保留测试：

- 新增了类、字段、getter、常量、路径或数据载体。
- 参数被原样传递，或 mock 方法被调用，但没有业务结果、状态变化或关键顺序。
- 为提高覆盖率、覆盖代码分支或固定当前实现形态。
- 通过反射验证私有字段、私有方法、普通 UI 属性或非契约文案。
- 没有可判定结果，仅打印日志、依赖人工观察或访问真实设备后输出结果。若“不抛异常”本身是契约，应使用 `assertDoesNotThrow` 等方式明确表达。
- benchmark 没有稳定基线、阈值或回归判定。

例外必须说明契约来源。例如：用户可见文案、协议文本、生成脚本同步、IDE/Gradle 外部兼容约定可以精确断言；普通日志内容和内部方法签名不属于契约。

Mockito、被测生产类数量和方法行数都不是价值标准。单类测试若保护外部格式、关键顺序或复杂领域规则，可以是有效 L1；Mockito 测试若保护恢复、重试、状态转换、并发协调或版本兼容，可以是有效 L2。

### 1.2 分层模型

```
                    ┌─────────────────────────────┐
              L3    │  *FlowTest / 手测矩阵      │  真实 demo 编译 → 部署/运行
                    ├─────────────────────────────┤
              L2    │  多类协作 + Mockito 接口     │  恢复/重试/编排分支（快）
                    ├─────────────────────────────┤
              L1    │  域内单测 + 真实产物        │  算法、解析、影响分析、序列化
                    ├─────────────────────────────┤
              L0    │  不写                      │  data class / 路径常量 / 纯 getter
                    └─────────────────────────────┘
```

| 层级 | 典型位置 | 证明什么 |
|------|----------|----------|
| **L3** | `idea/src/test/.../manager/TopLevelFlowTest`、`TopLevelFlowWithGitTest`、`AndroidTestTopLevelFlowTest` | 用户可见主链路、重构前后等价 |
| **L2** | `idea/.../deploy/run/*Test`（Recover/Retry）、`JuggCompileHelperTest` | 分支决策、协作接线；**不能替代 L3** |
| **L1** | `main/.../DeployDataGeneratorTest`、`main/.../deploy/direct/*Test`、`InstrumentationOutputParserTest` | 确定性变换、复杂数据结构 |
| **L0** | — | 禁止为「测字段存在」单独建类 |

**数量关系（纠正旧口径）**：L1 可多于 L3，但 **L3 必须覆盖每条对外主链路**；手测矩阵在发布前补充，不替代自动化 L3。

---

## 2. 何时允许「单文件 / 单类」测试（L1）

先通过 §1.1 的回归价值门禁。若行为可以在单模块内确定性验证，且承载领域不变量、外部格式/命名约定、算法、原子性顺序或真实产物解析，可以新增或追加 **L1** 用例：

| 类型 | 示例 | 模块 |
|------|------|------|
| 影响分析 / 图传播 | `DeployDataGenerator`、`DeployDataDatabase` | main |
| 字节码 / Dex / APK 解析 | `ParsedDex`、`ApkInfoReader` | main |
| 协议 / 日志解析 | `InstrumentationOutputParser`、`AdbLogWrapper`（有解析逻辑时） | main |
| 时序 / 缓冲算法 | `AndroidTestLogAttributor`、`TestLauncher` logcat 归属 | idea |
| 纯函数派生 | `AndroidTestCommandDeriver`、`InstrumentCommandBuilder` | main |
| 外部命名 / 兼容约定 | IDE module 到 Gradle variant 的映射 | main / idea |
| Direct overlay 校验 | `DirectOverlayStateChecker`、`DirectOverlayWriter` | main |
| 序列化往返 | `ApkInfoSerializer`、`BaseBuildCmdRecord` | main |

**不属于 L1（不要新建单文件 Mockito 测编排）**：

- `JuggDeployerHelper`、`DeployStateRecover`、`DeployRetryHandler` 的「是否调用 recover」类分支 → **L2**；主链路 → **L3**
- `DeployOptions` 字段、`JuggDeploymentService` 路径常量 → **L0**
- 一行纯函数若只做语法级转换 → **L0**；若编码外部命名或协议契约 → 并入该行为的现有 owner，不按行数机械删除

---

## 3. 选型决策（新增测试前 30 秒）

1. 这个行为被破坏时，用户、外部兼容或关键流程会受到什么影响？说不清 → 不新增测试。
2. 当前由哪个测试负责？先搜索并扩展现有 owner，避免按生产类逐层重复建测试。
3. 是否影响 **Run → 编译 → 部署** 用户路径？→ **先**查/扩 `*FlowTest`（L3）。
4. 是否确定性领域规则、外部协议/命名、算法或真实 D8 产物？→ L1。
5. 是否恢复、重试、并发、兼容或 IDE-only 编排分支？→ L2；优先追加到已有能力测试。
6. 是否仅数据结构、简单透传或实现细节？→ 不测（L0）。

### 3.1 行为 owner 与重复覆盖

- 每个稳定行为应有一个主要 owner。测试名称和断言应说明它负责保护什么行为，而不是复述生产方法名。
- L3 负责对外主链路；L2 保留 L3 不适合穷举的异常、恢复、兼容和并发分支；L1 保留确定性领域规则。存在 L3 不代表可以机械删除所有 L1/L2。
- 同一数据流不需要在 factory、数据载体、manager、action、HTTP 层分别验证透传。各层只有在承担独立校验、转换、协议或恢复责任时才建立测试。
- 删除或合并重复测试前，必须标明保留哪条用例作为 owner，并确认没有丢失唯一分支。

### 3.2 存量用例删除与合并标准

满足以下任一情况时，优先删除或合并：

- 无可判定结果、只打印结果或依赖真实环境人工判断。
- 只验证字段存取、默认值、路径常量、简单 getter 或原样透传。
- 只锁定私有 UI 属性、私有方法、反射签名、普通日志或非契约文案。
- 与现有 owner 重复，没有新增异常、兼容、状态或边界分支。
- benchmark 没有阈值；应移至手工工具或建立可判定基线。
- 生产能力已不可达或未注册；若确认废弃，应连同生产代码一起退出，而不是只删除测试留下无人保护的代码。

典型判断：

| 用例 | 结论 | 原因 |
|------|------|------|
| `JuggDeployerInstallTest#install retries once after offline exception and succeeds` | 保留（L2） | 保护真实 ADB offline 恢复策略与重试次数 |
| `DeployStateManagerTest#waitForPendingFileProcessing...` | 保留（L2） | 保护超时和条件唤醒两个并发状态分支 |
| `DirectOverlayWriterTest#write should remove payload targets before unzip` | 保留（L1） | 保护原子替换所需的脚本执行顺序 |
| `ModulePathMergePolicyTest#selectIdeBuildVariant...` | 保留或表驱动合并（L1） | 保护 IDE module 到 Gradle variant 的外部命名约定 |
| `DeployTargetManagerTest#test` | 删除 | 访问真实设备、无断言，仅输出结果 |
| `JuggRunSettingsComponentTest` 中私有对齐属性和普通提示文案用例 | 删除或合并 | 保留最终布局结果与 settings round-trip owner 即可 |

---

## 4. 文件与模块优先级

1. **复用已有 owner**：同一行为优先追加用例，不按生产类逐一新建 `*Test.kt`；只有新的独立行为且没有合适 owner 时才新建文件。
2. **main 模块优先**：无 IDE 依赖时 L1 放 `main/src/test`。
3. **idea 模块承载 IDE 编排**：IDE API、RunConfig、`JuggRunningTask`、deploy/run 编排。

> androidTest / instrumentation：见 [`06_android_test.md`](06_android_test.md)。

---

## 5. 查找已有测试

```bash
rg -n "DeployDataGenerator|JuggDeployerHelper|TopLevelFlow" main/src/test idea/src/test
rg --files main/src/test idea/src/test | rg "Deploy|Flow|Compiler|McpTool"
rg --files android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase
```

| 场景 | 优先目录 | 层级 |
|------|----------|------|
| 影响分析 / const ref | `main/.../deploy/data/` | L1 |
| Direct overlay / writer | `main/.../deploy/direct/` | L1 |
| 部署编排 / recover / retry | `idea/.../deploy/run/` 追加 + `idea/.../manager/*Flow*` | L2 + **L3** |
| MCP action | `main/.../ai/mcp/` | L1/L2 |
| 完整编译+部署 | `idea/.../manager/TopLevelFlowTest` 等 | L3 |

---

## 6. testcase 类规范（L1 / L3 共用）

### 6.1 目录约定

```text
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
└── <feature>/
    ├── TargetClass.kt
    └── InvokerClass.kt
```

- 每目录**一个场景**；类名体现角色（`Parent` / `Child` / `Invoker`）。
- 新增/修改 testcase 后删除 `~/.jugg/test_flag/skip_assemble` 或手动 assemble。

### 6.2 与 L3 关系

L3 Flow 依赖 `AssembleAndroidProjectOnce`；L1 `DeployDataGeneratorTest` 依赖同一 demo 产物。testcase 变更后两类测试均需回归。

---

## 7. 典型链路测试落点

### 7.1 编译 → 部署（JuggDeployerHelper 重构口径）

| 目标 | 层级 | 文件 |
|------|------|------|
| 用户点击 Run 后真部署 | **L3** | `TopLevelFlowTest`、`TopLevelFlowWithGitTest` |
| androidTest 部署+instrument | **L3** | `AndroidTestTopLevelFlowTest` |
| dry deploy / recover / retry 分支 | L2 | `JuggDeployerHelperRecoverTest`、`DeployRetryHandlerTest`（**追加**，不新建第三类 Helper 测试） |
| DF-L2-001～007 direct overlay 全链 | L2 Virtual Device | `JuggDeployerHelperDeployFlowTest` + `VirtualDeployDevice`（契约：`docs/task/jugg_deploy_flow_virtual_device.md`） |
| deploy 早退 | L2 | `JuggDeployerHelperDeployTest` |
| transport 窄脚本 | L1 | `idea/.../DirectOverlaySwapTransportTest` |
| overlay 三路 / writer 算法 | L1 | `main/.../DirectOverlayStateCheckerTest`、`DirectOverlayWriterTest` |
| 真机全链路 | L3 | `TopLevelFlowTest` |

**硬性**：改动 `JuggDeployerHelper.deploy` 分派或 recover→deploy 顺序时，执行清单必须包含 **至少 1 个 L3** 或说明复用哪条 Flow 场景。

### 7.2 androidTest（阶段设计对齐）

`docs/task/androidtest_support_design.md` §10 的组件表仍有效，但分层口径以**本页 §1** 为准：§10.2 列为 **L1 域内**，§10.3 为 **L2**，手测矩阵为 **L3 补充**。

### 7.3 现有 L2 存量（idea/deploy/run）

目录内 Mockito 单测视为 **L2 补充网**，新增用例优先**追加**到：

- `DeployRetryHandlerTest` / `JuggDeployerHelperRecoverTest`
- `TestLauncherResultTest`（L1 算法 + L2 会话）
- `LibraryTestApkBackfillHelperTest`（偏 L2 协作）

不新增 `DeployOptions*Test`、`JuggGlobalStoragePathTest` 同类 L0 文件。

---

## 8. TDD 与变更类型

| 变更类型 | 测试要求 |
|----------|----------|
| **feature / bugfix** | 定位已有行为 owner → 写描述缺失行为的失败测试 → 清单列路径+层级 → 再改生产代码 |
| **refactor** | 先列已有回归 owner；仅在稳定行为缺少保护时补测试；编排类 **L3 或 Flow 回归** + 可选 L2 |
| **optimize** | 同 refactor；不因内部结构变化补测试；性能断言需稳定基线、阈值或 L3 基准场景 |
| **仅 docs** | 无 |

执行清单示例：

```text
- 已验证测试用例：TopLevelFlowTest#deployAfterModify (L3)；DeployRetryHandlerTest (L2)
```

---

## 9. DeployDataGeneratorTest 模式（L1 示例）

依赖真实 D8 编译产物；勿手写 `MethodNode` 省略 `$r8$lambda$` 等细节。

### 9.1 从 APK 提取 ParsedDex

```kotlin
private fun getParsedDex(className: String): ParsedDex {
    val classSigName = className.classSigName
    return ParsedDex(
        parsedApk.classes.filter { it.key == classSigName }.map {
            ClassDeployItem(
                DeployItem(it.key, CompileOutput.Type.Dex, 0, byteArrayOf(), DeployItem.FLAG_CLASS),
                listOf(it.value),
            )
        },
        parsedApk.methodRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.fieldRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
        parsedApk.subclassRefs.filter { it.value.contains(classSigName) }.mapValues { listOf(classSigName) },
    )
}
```

### 9.2 断言受影响源文件

```kotlin
val data = generator.buildDeployData(modifiedParsedDex, emptyList())
assertEquals(listOf("SubClass1.java", "SubClass2.java").sorted(), data.effectedSourceFileNames.sorted())
```

---

## 10. 测试基础设施

### 10.1 前置条件

```kotlin
fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
    buildDir.clearDir()
}
```

### 10.2 关键全局变量（`mock/Commons.kt`）

| 变量 | 含义 |
|------|------|
| `buildDir` | 临时编译输出 |
| `assetsAndroidDir` | `android_demo_project` 根目录 |
| `context` | `SimpleCompileContext` |
| `projectInfo` | APK 元信息 |

---

## 11. 运行测试

禁止无 `--tests` 的全量 `:main:test` / `:idea:test`。

```bash
# L3
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"

# L2
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.DeployRetryHandlerTest"

# L1
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"

./gradlew :idea:compileKotlin
```

---

## 12. 跳过 Assemble 加速

```bash
mkdir -p ~/.jugg/test_flag && touch ~/.jugg/test_flag/enabled && touch ~/.jugg/test_flag/skip_assemble
```

新增 testcase 后必须删除 `skip_assemble`。

---

## 13. 常见陷阱

| 问题 | 原因 | 解决 |
|------|------|------|
| 只有 L2 绿、线上仍坏 | 缺 L3 | 补 Flow 或扩 TopLevelFlow |
| `getParsedDex` 为空 | 未 assemble / 类名错 | 删 skip_assemble |
| SQLite 与内存 DB 行为不一致 | 只测内存库 | 补 `DeployDataDatabaseSqLiteHelperTest` |
| 为 Helper 建第三个 `*Test` | 未复用文件 | 合并到 Recover/Retry |
| 每个中间类都有测试 | 按代码结构而非行为 owner 建测试 | 保留负责最终行为、异常或协议的 owner |
| 测试只验证 mock 调用 | 没有业务结果或关键顺序 | 改断言可观察行为，无法表达则删除 |
| 任务文档写「单测 >> 集成」 | 历史口径 | 以本页 §1 为准 |

---

## 14. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 不确定是否需要测试 | 本页 §1.1 回归价值门禁 + §3 选型决策 |
| 不确定该写 L1/L2/L3 | 本页 §1.2 分层模型 + §2 + §3 |
| deploy/run 分支只在 L2 复现 | 本页 §7.1，确认是否还需要 `TopLevelFlowTest` / `AndroidTestTopLevelFlowTest` |
| androidTest 相关测试落点不清 | `06_android_test.md` §6 + 本页 §7.2 |
| 新增 testcase 后测试读到旧产物 | 本页 §6.1、§12，删除 `~/.jugg/test_flag/skip_assemble` |
| 任务文档测试口径冲突 | 本页 §1 与 §15；任务文档只保留场景背景 |

---

## 15. 历史文档

`docs/task/TDD_UNIT_TEST_COVERAGE_GAP_REPORT_*.md`、`androidtest_support_design.md` §10 等若写「单元测试数量远多于集成」，指 **L1 域内测试** 或历史盘点，**不等同**于鼓励为编排类堆 Mockito 单测。修订任务文档时分层以本页为准。
