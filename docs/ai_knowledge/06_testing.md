# 测试与验证策略（权威细则）

> 最后核对：2026-07-29
> 一致性规则：文档与代码冲突时，以代码为准。
> **与 AGENTS.md / CLAUDE.md 关系**：顶层规则只保留不可绕过的约束；**本页是验证证据、测试价值、分层、TDD、落点和存量治理的唯一权威细则**。其他 `docs/task/*` 若与本页冲突，以本页为准。

---

## 0. 文档定位

本页按以下固定顺序回答测试与验证问题：

1. 本次变更需要什么验证证据。
2. 是否值得新增自动化测试。
3. 值得测试时由哪个 behavior owner 负责。
4. 测试落在 L1、L2 还是 L3。
5. 无法形成有价值自动化断言时，使用什么替代验证。

**禁止跳过测试价值判断，直接因为变更类型、覆盖率或 TDD 要求创建测试。**

---

## 1. 统一决策顺序

### 1.1 顶层原则

- 所有开发任务都必须提供与风险匹配的**验证证据**。
- 自动化测试只是验证证据的一种，不是所有变更都必须新增自动化测试。
- 新增或保留自动化测试前，必须先通过**测试价值门禁**。
- 测试价值门禁高于 TDD 的形式要求、L0～L3 分层和“复用已有测试文件”等落点规则。
- TDD 只适用于已经通过价值门禁、能够形成稳定自动化断言的行为。
- 没有新增自动化测试不等于没有验证；必须明确失败证据、不能自动化的原因和替代验证。

### 1.2 决策流

```text
本次变更需要证明什么？
  -> 是否存在独立、稳定、可能被真实破坏的可观察行为？
     -> 否：不新增测试；执行编译、静态检查或其他必要验证
     -> 是：能否在不绑定私有实现、不增加测试专用 seam 的前提下稳定自动化？
        -> 是：定位 behavior owner -> 先写失败测试/确认已有回归 -> 选择 L1/L2/L3
        -> 否：禁止制造实现细节测试 -> 保存失败复现证据 -> 选择替代验证
```

### 1.3 验证证据类型

| 证据 | 适用场景 | 能证明什么 |
|------|----------|------------|
| 自动化测试 | 稳定、确定、可观察的行为 | 行为回归可被持续检测 |
| 定向编译 | API 接线、类型兼容、模块依赖 | 当前源码能按目标依赖完成编译 |
| 构建与产物检查 | 插件包、APK、DEX、生成脚本 | 最终产物存在且包含预期结构 |
| 字节码/API 检查 | Android Studio / Gradle 二进制兼容 | 编译结果实际链接到目标 API 形态 |
| 日志与稳定复现 | 外部 runtime、真机、IDE 内部异常 | 修复前问题真实存在且触发条件明确 |
| L3 Flow | Run → 编译 → 部署等主链路 | 用户可见流程端到端成立 |
| 手工回归矩阵 | 无稳定自动化环境的真机/IDE 版本组合 | 指定环境中的最终行为成立 |

替代验证应尽量接近真实失败边界。编译成功不能替代运行时行为；源码字符串检查不能替代用户行为。

---

## 2. 测试价值门禁

### 2.1 通过条件

有回归价值的测试通常同时满足：

1. 保护用户可见行为、业务规则、外部协议、命名约定、兼容性、恢复策略、并发状态或关键执行顺序等稳定契约。
2. 契约被真实破坏时，测试能够稳定失败，并通过确定性断言说明问题。
3. 有明确的 behavior owner；其他测试未以相同粒度重复证明同一责任。
4. 断言关注可观察结果，不绑定可以自由调整的私有结构。

Mockito、测试层级、被测类数量和覆盖率都不能单独证明测试有价值。

### 2.2 默认不测试

以下情况默认不新增或保留自动化测试：

- 类、字段、getter、常量、路径或普通数据载体存在。
- 参数被原样传递，或仅验证 mock 方法被调用，没有业务结果、状态变化或关键顺序。
- 为提高覆盖率、覆盖代码分支或固定当前实现形态。
- 通过反射验证私有字段、私有方法、内部签名或普通 UI 属性。
- 读取生产源码并用 `contains`、正则或字符串匹配锁定私有方法体、构造函数重载、具体调用、类名或委托对象。
- 普通日志或非契约文案的精确字符串。
- 没有可判定结果，仅打印内容或依赖人工观察。若“不抛异常”本身是契约，应使用 `assertDoesNotThrow` 等明确断言。
- benchmark 没有稳定基线、阈值或回归判定。

如果只能通过上述方式满足“先写测试”，说明自动化测试没有通过价值门禁，应改用替代验证。

### 2.3 源码与静态架构守卫

源码级检查不是普通行为测试，只允许保护**契约本身就是源码/依赖边界**的场景：

- 某模块禁止 import 或暴露特定 runtime 类型。
- 公共兼容接口禁止泄漏版本专属 API。
- 生成脚本、协议文本或镜像文件必须与唯一来源同步。
- 构建系统无法直接表达、但有明确架构文档支撑的 forbidden dependency。

源码级检查必须满足：

1. 测试名称描述架构契约，不描述私有方法实现。
2. 断言范围是模块、公共边界、生成产物或禁止依赖，不是具体 helper 方法体。
3. 优先使用编译、模块依赖或静态分析；只有这些方式无法表达时才扫描源码。
4. 不得把源码守卫作为 feature / bugfix 的 TDD 替代品。

例如，“IDE 主路径不得依赖旧 deployer runtime 类型”可以是架构守卫；“`createAdbClient` 必须调用某个三参数构造”不是。

---

## 3. 自动化测试分层

分层只回答“已经通过价值门禁的测试放在哪里”，不能决定是否应该写测试。

```text
                    ┌─────────────────────────────┐
              L3    │  *FlowTest / 发布回归矩阵   │  真实 demo 编译 → 部署/运行
                    ├─────────────────────────────┤
              L2    │  多类协作 + Mockito 接口     │  恢复/重试/编排/兼容分支
                    ├─────────────────────────────┤
              L1    │  域内单测 + 真实产物         │  算法、解析、序列化、生成物
                    ├─────────────────────────────┤
              L0    │  不写自动化测试              │  纯数据/透传/实现细节
                    └─────────────────────────────┘
```

| 层级 | 典型位置 | 证明什么 |
|------|----------|----------|
| **L3** | `idea/src/test/.../manager/TopLevelFlowTest`、`TopLevelFlowWithGitTest`、`AndroidTestTopLevelFlowTest` | 用户可见主链路、重构前后等价 |
| **L2** | `idea/.../deploy/run/*Test`、`JuggCompileHelperTest` | 恢复、重试、并发、兼容和 IDE 编排分支 |
| **L1** | `main/.../DeployDataGeneratorTest`、`main/.../deploy/direct/*Test`、解析/生成物测试 | 确定性变换、复杂数据结构、真实产物 |
| **L0** | — | 不创建测试；选择必要的替代验证 |

### 3.1 L1 允许范围

通过价值门禁后，以下行为通常适合 L1：

| 类型 | 示例 | 模块 |
|------|------|------|
| 影响分析 / 图传播 | `DeployDataGenerator`、`DeployDataDatabase` | main |
| 字节码 / Dex / APK 解析 | `ParsedDex`、`ApkInfoReader` | main |
| 协议 / 日志解析 | `InstrumentationOutputParser`、`AdbLogWrapper` | main |
| 时序 / 缓冲算法 | `AndroidTestLogAttributor`、`TestLauncher` logcat 归属 | idea |
| 纯函数派生 | `AndroidTestCommandDeriver`、`InstrumentCommandBuilder` | main |
| 外部命名 / 兼容约定 | IDE module 到 Gradle variant 的映射 | main / idea |
| Direct overlay 校验 | `DirectOverlayStateChecker`、`DirectOverlayWriter` | main |
| 序列化往返 | `ApkInfoSerializer`、`JuggDeploymentCacheStore` | main / idea |
| 生成产物 | Gradle init script、APT 输出、Manifest、R 文件 | main / idea |

不属于 L1：

- `JuggDeployerHelper`、`DeployStateRecover`、`DeployRetryHandler` 的协作分支属于 L2；用户主链路属于 L3。
- `DeployOptions` 字段、路径常量和简单 getter 属于 L0。
- 一行纯函数若只做语法级转换属于 L0；若编码外部协议或命名契约，则并入已有 owner。

---

## 4. Behavior owner 与文件落点

### 4.1 Owner 原则

- 每个稳定行为应有一个主要 owner。测试名称和断言描述行为责任，而不是复述生产方法名。
- 同一行为优先追加到已有 owner，不按生产类逐层新建 `*Test.kt`。
- L3 负责对外主链路；L2 保留 L3 不适合穷举的异常、恢复、兼容和并发分支；L1 保留确定性领域规则。
- 同一数据流不需要在 factory、数据载体、manager、action、HTTP 层重复验证透传。
- 某文件已有源码扫描测试，不表示新的源码字符串断言自动有价值；仍需重新经过价值门禁。

### 4.2 模块优先级

1. 无 IDE 依赖的确定性行为优先放 `main/src/test`。
2. IDE API、RunConfig、`JuggRunningTask`、deploy/run 编排放 `idea/src/test`。
3. 架构静态守卫独立命名为 `*ArchitectureTest` / `*ContractTest`，不混入行为 owner。
4. 只有新的独立行为且没有合适 owner 时才新建测试文件。

### 4.3 存量治理

满足以下任一情况时，优先删除、迁移或合并：

- 无可判定结果，只打印内容或访问真实环境后人工判断。
- 只验证字段、默认值、路径、getter、原样透传或 mock 调用。
- 只锁定私有方法、具体委托类、构造方式、反射签名、普通日志或非契约文案。
- 行为测试和架构静态守卫混在同一个 owner 文件中。
- 与现有 owner 重复，没有新增异常、兼容、状态或边界分支。
- 生产能力已不可达或未注册。

典型判断：

| 用例 | 结论 | 原因 |
|------|------|------|
| `JuggDeployerInstallTest#install retries once after offline exception and succeeds` | 保留（L2） | 保护 ADB offline 恢复策略与重试次数 |
| `DeployStateManagerTest#waitForPendingFileProcessing...` | 保留（L2） | 保护超时和条件唤醒 |
| `DirectOverlayWriterTest#write should remove payload targets before unzip` | 保留（L1） | 保护原子替换顺序 |
| `DeployCompatArchitectureTest` 中禁止主路径泄漏旧 deployer 类型 | 保留（静态架构守卫） | 契约本身是模块边界 |
| 断言 Quail 私有 helper 使用哪个 `AdbClient` 构造函数 | 删除 / 不新增 | 锁定实现，不证明安装行为 |
| `DeployTargetManagerTest#test` | 删除 | 无断言，仅访问真实设备 |

---

## 5. 变更类型与执行工作流

### 5.1 Feature / Bugfix

1. 先取得描述缺失行为的**失败证据**：失败测试、稳定复现、异常日志、崩溃栈或外部 API 对比。
2. 执行测试价值门禁。
3. 若存在有价值自动化断言：定位 owner，先写并确认失败测试，再修改生产代码。
4. 若无法在不绑定实现细节、不增加测试专用 seam 的前提下自动化：不新增测试；记录原因和替代验证。
5. 修复后执行定向测试或替代验证，并对照失败证据确认问题消失。

### 5.2 Refactor / Optimize

- 先列出已有回归 owner，确认改动前通过。
- 仅在稳定行为缺少保护时补测试，不因内部结构变化新增实现细节测试。
- deploy / compile 编排变更必须包含 L3 或已有等价 Flow 回归。
- 性能优化需要稳定基线、阈值或明确的 L3 基准场景。

### 5.3 仅文档

- 不要求自动化测试。
- 执行 `git diff --check`、路径抽查、索引一致性或文档构建等与风险匹配的验证。

### 5.4 执行清单

开发任务应根据实际情况记录：

```text
- 失败证据：测试 / 日志 / 稳定复现 / N/A
- 自动化测试价值判断：新增 / 复用 / 不新增+原因
- 测试 owner 与层级：类#方法 (L1/L2/L3) / N/A
- 替代验证：编译 / 构建 / 产物 / 字节码 / 手工矩阵 / N/A
```

---

## 6. Testcase 类规范（L1 / L3 共用）

### 6.1 目录约定

```text
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/
└── <feature>/
    ├── TargetClass.kt
    └── InvokerClass.kt
```

- 每目录一个场景；类名体现角色（`Parent` / `Child` / `Invoker`）。
- 新增/修改 testcase 后删除 `~/.jugg/test_flag/skip_assemble` 或手动 assemble。

### 6.2 与 L3 关系

L3 Flow 依赖 `AssembleAndroidProjectOnce`；L1 `DeployDataGeneratorTest` 依赖同一 demo 产物。testcase 变更后两类测试均需回归。

---

## 7. 典型链路测试落点

### 7.1 编译 → 部署

| 目标 | 层级 | 文件 |
|------|------|------|
| 用户点击 Run 后真部署 | **L3** | `TopLevelFlowTest`、`TopLevelFlowWithGitTest` |
| androidTest 部署+instrument | **L3** | `AndroidTestTopLevelFlowTest` |
| dry deploy / recover / retry | L2 | `JuggDeployerHelperRecoverTest`、`DeployRetryHandlerTest` |
| Direct Overlay 虚拟设备全链 | L2 | `JuggDeployerHelperDeployFlowTest` + `VirtualDeployDevice` |
| deploy 早退 | L2 | `JuggDeployerHelperDeployTest` |
| install offline / retry / mode escalation | L2 | `JuggDeployerInstallTest` |
| deploy compat 源码依赖边界 | 静态架构守卫 | `DeployCompatArchitectureTest` |
| transport 窄脚本 | L1 | `DirectOverlaySwapTransportTest` |
| overlay 三路 / writer 算法 | L1 | `DirectOverlayStateCheckerTest`、`DirectOverlayWriterTest` |

改动 `JuggDeployerHelper.deploy` 分派或 recover→deploy 顺序时，执行清单必须包含至少 1 个 L3，或说明复用哪条等价 Flow。

### 7.2 AndroidTest

`docs/task/androidtest_support_design.md` 的场景表可用于背景，但测试价值、验证策略和分层以本页为准。能力细节见 `06_android_test.md`。

### 7.3 现有 L2 owner

deploy/run 新增分支优先追加到：

- `DeployRetryHandlerTest` / `JuggDeployerHelperRecoverTest`
- `JuggDeployerInstallTest`
- `TestLauncherResultTest`
- `LibraryTestApkBackfillHelperTest`

不新增 `DeployOptions*Test`、路径常量测试或同类 L0 文件。

---

## 8. DeployDataGeneratorTest 模式（L1 示例）

依赖真实 D8 编译产物；勿手写 `MethodNode` 省略 `$r8$lambda$` 等细节。

### 8.1 从 APK 提取 ParsedDex

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

### 8.2 断言受影响源文件

```kotlin
val data = generator.buildDeployData(modifiedParsedDex, emptyList())
assertEquals(listOf("SubClass1.java", "SubClass2.java").sorted(), data.effectedSourceFileNames.sorted())
```

---

## 9. 测试基础设施

### 9.1 前置条件

```kotlin
fun clearBuild() {
    AssembleAndroidProjectOnce.ensure()
    buildDir.clearDir()
}
```

### 9.2 关键全局变量（`mock/Commons.kt`）

| 变量 | 含义 |
|------|------|
| `buildDir` | 临时编译输出 |
| `assetsAndroidDir` | `android_demo_project` 根目录 |
| `context` | `SimpleCompileContext` |
| `projectInfo` | APK 元信息 |

---

## 10. 运行测试与验证

禁止无 `--tests` 的全量 `:main:test` / `:idea:test`。

```bash
# L3
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"

# L2
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.DeployRetryHandlerTest"

# L1
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"

# 编译 / 构建类替代验证
./gradlew :idea:compileKotlin
./gradlew :idea:buildPlugin
```

---

## 11. 跳过 Assemble 加速

```bash
mkdir -p ~/.jugg/test_flag
touch ~/.jugg/test_flag/enabled
touch ~/.jugg/test_flag/skip_assemble
```

新增 testcase 后必须删除 `skip_assemble`。

---

## 12. 常见陷阱

| 问题 | 原因 | 解决 |
|------|------|------|
| 为满足 TDD 创建源码字符串测试 | 没先过价值门禁 | 删除测试，记录失败证据并选替代验证 |
| `readText().contains(...)` 锁私有 helper | 把实现当契约 | 只保留明确的模块/协议/生成物守卫 |
| 只有 L2 绿、线上仍坏 | 缺 L3 | 补 Flow 或发布回归矩阵 |
| `getParsedDex` 为空 | 未 assemble / 类名错 | 删除 `skip_assemble` |
| SQLite 与内存 DB 行为不一致 | 只测内存库 | 补 SQLite owner |
| 为 Helper 建第三个 `*Test` | 未复用 owner | 合并到现有 Recover/Retry/Flow |
| 每个中间类都有测试 | 按代码结构建测试 | 保留最终行为、异常或协议 owner |
| 测试只验证 mock 调用 | 没有业务结果或关键顺序 | 改断言可观察行为，无法表达则删除 |
| 把手工日志当自动化测试 | 没有持续判定机制 | 归类为替代验证并记录环境与结果 |

---

## 13. 排查入口

| 问题 | 优先入口 |
|------|----------|
| 不确定是否需要自动化测试 | §1 决策流 + §2 价值门禁 |
| 无法写失败测试但已有稳定复现 | §5.1 Feature / Bugfix |
| 不确定 L1/L2/L3 | §3 分层 + §4 owner |
| 不确定源码扫描是否合理 | §2.3 静态架构守卫 |
| deploy/run 分支只在 L2 复现 | §7.1，确认是否还需要 L3 |
| androidTest 测试落点不清 | `06_android_test.md` + §7.2 |
| 新增 testcase 后读取旧产物 | §6.1、§11 |

---

## 14. 历史文档

`docs/task/TDD_UNIT_TEST_COVERAGE_GAP_REPORT_*.md`、`androidtest_support_design.md` 等历史方案只提供场景背景。若其内容暗示“所有 bugfix 必须新增单测”“单元测试数量越多越好”或“单测优先于用户主链路”，均以本页的验证证据、价值门禁和 owner 规则为准。
