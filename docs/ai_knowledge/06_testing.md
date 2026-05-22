# 测试策略与 TDD（权威细则）

> 最后核对：2026-05-22  
> 一致性规则：文档与代码冲突时，以代码为准。  
> **与 AGENTS.md 关系**：`AGENTS.md` 规定测试分层硬性原则；**本页是落地细则**（选型、目录、示例、TDD 清单）。其他 `docs/task/*` 设计文档若与本页冲突，以本页为准。

---

## 1. 测试分层

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

## 2. 何时允许「单文件 / 单类」测试（L1 白名单）

仅在下列类型新增或追加 **L1** 用例；其余默认走 L2/L3：

| 类型 | 示例 | 模块 |
|------|------|------|
| 影响分析 / 图传播 | `DeployDataGenerator`、`DeployDataDatabase` | main |
| 字节码 / Dex / APK 解析 | `ParsedDex`、`ApkInfoReader` | main |
| 协议 / 日志解析 | `InstrumentationOutputParser`、`AdbLogWrapper`（有解析逻辑时） | main |
| 时序 / 缓冲算法 | `AndroidTestLogAttributor`、`TestLauncher` logcat 归属 | idea |
| 纯函数派生 | `AndroidTestCommandDeriver`、`InstrumentCommandBuilder` | main |
| Direct overlay 校验 | `DirectOverlayStateChecker`、`DirectOverlayWriter` | main |
| 序列化往返 | `ApkInfoSerializer`、`BaseBuildCmdRecord` | main |

**不属于 L1（不要新建单文件 Mockito 测编排）**：

- `JuggDeployerHelper`、`DeployStateRecover`、`DeployRetryHandler` 的「是否调用 recover」类分支 → **L2**；主链路 → **L3**
- `DeployOptions` 字段、`JuggDeploymentService` 路径常量 → **L0**
- `mergeOverlayIds` 等一行纯函数 → 并入已有 L2 文件，不单独开类

---

## 3. 选型决策（新增测试前 30 秒）

1. 改动是否影响 **Run → 编译 → 部署** 用户路径？→ **先** 查/扩 `*FlowTest`（L3）
2. 是否复杂算法 / 真实 D8 产物？→ `main` + testcase + `DeployDataGeneratorTest` 模式（L1）
3. 是否 IDE-only 编排分支？→ 复用已有 `*Test.kt` 追加（L2），**禁止**为同一 Helper 再建新文件
4. 是否仅数据结构？→ 不测（L0）

---

## 4. 文件与模块优先级

1. **【最高】复用已有文件**：同一被测能力只追加用例，不新建 `*Test.kt`（除非 L1 白名单且尚无文件）。
2. **【次优先】main 模块**：无 IDE 依赖时 L1 放 `main/src/test`。
3. **【必要时】idea 模块**：IDE API、RunConfig、`JuggRunningTask`、deploy/run 编排。

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
| **feature / bugfix** | 先写失败测试 → 清单列路径+层级 → 再改生产代码 |
| **refactor** | 清单列路径+层级；编排类 **L3 或 Flow 回归** + 可选 L2 |
| **optimize** | 同 refactor；性能断言需 L3 或基准场景 |
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
| 任务文档写「单测 >> 集成」 | 历史口径 | 以本页 §1 为准 |

---

## 14. 历史文档

`docs/task/TDD_UNIT_TEST_COVERAGE_GAP_REPORT_*.md`、`androidtest_support_design.md` §10 等若写「单元测试数量远多于集成」，指 **L1 域内测试** 或历史盘点，**不等同**于鼓励为编排类堆 Mockito 单测。修订任务文档时分层以本页为准。
