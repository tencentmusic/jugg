# IDEA 运行配置创建逻辑对齐方案

## 背景

Jugg Report `7ec4603f` 使用 Canary `4.0.4-canary.20260910.41`，对应 release build `a57ad336ac20`。现场 Gradle Sync 已成功，Android Studio 能提供 `app/debug` suggestion，但 Jugg 没有自动创建运行配置：

- 初始化日志出现 `No application module found in Gradle project info`。
- 现场 `project_infos.json` 包含 146 个模块，`moduleType` 均为 `Unknown`。
- Sync 后能够读取 `./gradlew :app:assembleDebug` 和 APK output suggestion。
- develop 的配置创建先调用 `CliRunConfigurationGenerator.generate(projectInfo)`，在检查 suggestion 前已经因没有 Application 模块失败。

该行为由 `c36ecfa00` 引入的共享 CLI Run Configuration 架构产生。后续 `9e1a2d89b` 和 `41774bfde` 恢复了部分 Android Studio suggestion 能力，但 suggestion 仍只是 ProjectInfo fallback 的补充，不能独立创建配置。

本方案让 develop 的 IDEA 侧创建、去重、Sync 恢复和重试行为对齐 main，同时保留 Standalone 所需的共享配置集合、稳定 ID 和 Gradle ProjectInfo fallback。

## 已确认决策

### 纳入范围

- Android Studio suggestion 成为 IDEA 侧可独立使用的配置发现来源。
- IDEA 按 main 的 suggestion 范围和 Gradle task 语义创建、去重运行配置。
- Sync 后恢复可达的有限重试。
- 已有 Jugg 配置不因 ProjectInfo 不完整而失效，共享配置导入采用 Best-effort。
- Sync 创建成功后恢复 Jugg Tool Window 可用状态。
- 共享 CLI 配置完整保存远程同步排除规则的自定义状态。

### 明确不包含

- 不处理 main 与 develop 共有的运行配置缺陷。
- 新生成配置继续不继承 `JuggSettings.defaultCompileSettings`，默认使用本地编译配置。
- 不改变 Active Build Variant 当前的 fail-closed 选择原则。
- 不改变 develop 的稳定 UUID、独立 JSON 配置集合和 current pointer。
- 不改变 Standalone 无 IDEA 场景下使用 Gradle ProjectInfo 初始化默认配置的能力。
- 不统一 main 与 develop 的配置显示名称。

## 当前问题

### IDEA suggestion 不是独立输入

当前初始化顺序为：

```text
读取 JuggProjectInfo
  -> 生成 ProjectInfo fallback
  -> 用唯一 suggestion 修正 fallback 的 Gradle path 和 APK output
```

因此 suggestion 只有在 ProjectInfo 已经能够确定 Application module 和 variant 时才生效。ProjectInfo 缺失 Application、模块身份不完整或 variant 过期时，Android Studio 的有效信息无法形成最小配置。

### Sync 后不会进入创建重试

`tryCreateRunConfigurations(isSyncFinished = false)` 只在初始化调用。Sync 改为直接执行 `reconcileActiveBuildVariants()`，没有调用传入 `isSyncFinished = true` 的创建入口，因此现有指数退避分支不可达。

Sync 后的延迟 ProjectInfo 刷新也只更新 Compile Context，不会再次触发运行配置创建。一次短暂失败可能持续到下次 Sync 或重启。

### IDEA 可用性与共享导入被绑定

已有 IDEA Jugg 配置在导入共享 Store 时调用 `resolveBuildIdentity(projectInfo, compileCommand)`。没有 Application 模块时，该过程抛出异常并让整个初始化或对账失败。

IDEA 配置是否可运行和该配置是否已经成功导出到共享 Store 是两个不同结果，不应互相阻塞。

### IDEA 创建范围偏离 main

develop 在 Sync 后遍历 ProjectInfo 中的全部 Application 模块；main 根据 Android Studio 普通 Android Run Configuration 生成 suggestion，只创建 IDE 当前能够确认的可运行目标。

ProjectInfo 驱动会同时产生两类偏差：

- ProjectInfo 漏掉或降级 Application 时不创建配置。
- ProjectInfo 包含 sample、benchmark 或内部 Application 时可能创建 Android Studio 未提供的配置。

### 共享配置丢失排除规则状态

`CliRunConfiguration` 保存了 `remoteSyncExcludePatterns`，但没有保存 `isRemoteSyncExcludePatternsCustomized`。Standalone 转换为 `JuggGradleCompileOptions` 后该标志恢复为 `false`，用户自定义 patterns 会被当作未自定义并回退到内置默认规则。

该问题与“新配置不继承默认远程编译模板”无关：本方案只保证已有或手工配置的字段往返完整，不改变新配置默认值。

## 目标架构

配置发现按 Host 分层，共享层只负责配置模型、生成和持久化：

```text
Android Studio Android Run Configuration
  -> SuggestRunConfiguration
  -> IdeaCliRunConfigurationManager
  -> CliRunConfiguration
  -> CliRunConfigurationStore
  -> IDEA / Standalone

Gradle ProjectInfo
  -> CliRunConfigurationGenerator
  -> CliRunConfigurationStore
  -> IDEA / Standalone
```

`SuggestRunConfiguration` 保持在 IDEA/兼容层，不进入共享 `main` 模块。IDEA adapter 使用 suggestion 中的完整 Gradle module path、variant 和 APK output 调用现有 `generateForModuleIdentity()`；Standalone 不感知 suggestion，也不依赖 IntelliJ API。

## 目标行为

### IDEA 启动

按以下顺序处理：

1. RunManager 已有非默认 Jugg 配置时，立即判定 IDEA 配置可用。
2. 对已有配置逐条尝试导出到共享 Store；单条无法解析只影响该条导出。
3. 没有 Jugg 配置且 Android Studio suggestion 已可用时，直接创建 suggestion 对应配置。
4. suggestion 尚不可用时不在启动阶段创建 ProjectInfo fallback，等待 Sync。

启动阶段延迟 ProjectInfo fallback，可以避免先创建错误 module path 或旧 variant 配置，随后 suggestion 到达却无法修正。

### Sync 成功或被标记为 SKIPPED

按以下顺序处理：

1. 保持现有 `updateProjectInfo(isAfterSync)`，ProjectInfo 继续服务 Compile Context。
2. 读取最新 Android Studio suggestions。
3. 在项目写锁内导入已有 Jugg 配置并创建缺失 suggestion 配置。
4. 按现有 fail-closed 门禁处理 Active Build Variant 选择。
5. suggestion 为空、没有任何非默认 Jugg 配置且 ProjectInfo 可用时，生成一个确定性 fallback。
6. 仍没有可运行配置时，启动有限指数退避重试。
7. 任一路径形成可运行配置后，统一把 Jugg Tool Window 设为 available。

ProjectInfo fallback 只解决“Android Studio 没有 suggestion，但 Gradle 模型已足以生成默认配置”的场景，不再为每个 Application 模块批量补配置。

### suggestion 创建和去重

创建阶段按以下规则处理：

- suggestions 先按“可唯一识别的单个 Gradle task”去重；无法唯一识别时按精确完整 command 去重。
- 已有配置与 suggestion 都能唯一识别为单个 Gradle task 时，只按标准化 task 比较。
- `./gradlew :app:assembleDebug --offline` 与 suggestion `:app:assembleDebug` 视为同一目标。
- `:app:deployDebug`、`:app:uploadDebug` 不等于 `:app:assembleDebug`，仍允许创建标准 suggestion 配置。
- 任一侧为多 task、无法唯一识别 task 或格式不受支持时，退化为精确完整 command 比较，不做包含式推断。
- 不删除、不覆盖已有配置的 command、APK output 或远端字段。

**与 main 的差异（有意收紧）**：main 的 `matchesCompileTarget()` 使用 `suggestedTask in gradleTaskTokens(existingCommand)`，因此当已有多 task command 只要**包含** suggestion task 就会被判为重复。本方案不宣称与该语义完全等价：develop 只在两侧都能唯一识别为单个 task 时才按 task 去重，其余情况一律只按精确完整 command 去重。这是基于 main 语义的有意收紧，避免把 `:app:assembleDebug :app:assembleRelease` 之类的多 task 命令误判为与标准 suggestion 等价。

标准化 task 的识别规则（与 main 的 `gradleTaskTokens()` 对齐）：按空白切分，去掉引号，排除可执行文件名（`gradle` / `gradlew` / `gradle.bat` / `gradlew.bat`，允许带路径前缀）、`-` 开头的参数和含 `=` 的参数，剩余 token 去掉前导 `:` 后必须恰好一个。

#### 稳定 ID 冲突必须在创建阶段处理

suggestion 生成的稳定 ID 可能已经被现有 IDEA 配置占用：

- 现有配置的 command 恰好等于该 suggestion 的精确标准 command 时，属于同一目标，直接复用，不重复创建。
- 现有配置的 command **不是**精确标准 command（例如同 ID 下是 `deployVariant` 自定义 target）时，不得再创建同 ID 配置。
- 冲突时跳过创建，保留现有配置和共享 Store 原值，使用 debug 日志记录降级原因；IDEA 侧保持可运行，Active Build Variant 继续 fail-closed。
- 该规则用于防止 `CliRunConfigurationStore.save()` 按相同 ID 覆盖用户自定义 profile。

#### 名称与 Store 一致性

新建配置使用 `CliRunConfigurationGenerator.generateForModuleIdentity()` 产生稳定 ID 和 develop 命名（`moduleName variant`）作为首选名称。名称冲突时使用 `RunManager.suggestUniqueName()` 得到最终实际名称，并以该最终名称创建 IDEA 配置；写入共享 Store 的 `CliRunConfiguration.name` 必须使用同一个最终名称，避免 IDEA 名称与共享 Store 名称不一致。

### suggestion 身份解析边界

`SuggestRunConfiguration` 没有独立 modulePath 字段，完整 Gradle module path 只能来自 compile command。因此只有同时满足以下条件的 suggestion 才允许创建稳定配置、参与身份导入或 Active Build Variant 选择：

- compile command 能精确解析为受支持的单个 `./gradlew :modulePath:assembleVariant` task；
- module path 合法（每段非空，不带 URL 之外的杂散字符）；
- command 中的 variant 归一化后与非空 `variantName` 一致。

无法解析、多 task、variant 不一致或 `variantName` 为空的 suggestion 不得伪造稳定身份：跳过该条 suggestion 的创建与选择参与，按已有配置、ProjectInfo fallback 或有限重试降级。

### Active Build Variant 选择

保留当前 fail-closed 规则，并把否决顺序固定在复用之前：

- 当前选中项必须是 Jugg 配置。
- source 和 target 都必须是精确标准生成命令。
- suggestion 的 command、module path 和 `variantName` 必须一致且唯一。
- 自定义 command、附加参数、多 task、歧义 suggestion 或稳定 ID 冲突时保持当前选择。

创建阶段先补齐 suggestion 配置，选择阶段只查找目标，不再通过遍历 ProjectInfo Application 模块决定是否创建目标。

**自定义 target 优先否决**：选择阶段在复用稳定 ID 或精确匹配目标之前，先检查目标 variant 是否已经被自定义 target 占用。自定义 target 指“目标 module + 目标 variant 存在配置，但该配置的 command 不是该目标的标准生成 command”。命中时直接保持当前选择，即使标准 suggestion 配置刚刚在创建阶段新建，也不能抢占当前选择。该规则不改变既有对照用例的结论：选择项本身已是标准目标、或目标 variant 只有标准配置时，仍按原逻辑复用或创建。

稳定 ID 唯一且 command 精确时优先使用稳定 ID；否则仅兼容唯一精确匹配 suggestion command 与 APK output 的旧配置。

### 已有配置的 Best-effort 导入

`ensureConfiguration()` 和 Sync 对账必须先以 RunManager 中是否存在非默认 Jugg 配置判断 IDEA 是否可用，再独立执行共享导入。“非默认”沿用现有 `SuggestRunConfiguration.isDefaultRunConfigName()` 口径。

每条配置的身份按以下顺序解析，任何一步都不能抛出：

1. 本轮成功构建的精确标准 command：`./gradlew :modulePath:assembleVariant` 单 task，moduleName 由 modulePath 按 `:` 转 `.` 得到，variant 由 task 后缀归一化得到。
2. 当前或历史共享配置中已经确认的身份：同 `cliRunConfigurationId` 的历史共享配置，或 `fromCompileOptions()` 的 `base`。
3. 使用可用 ProjectInfo 解析已知 module/variant。
4. 仍无法确定时暂不写入共享 Store，但保留 IDEA 配置和可运行状态。

第 1 步优先的第二个原因是 Report `7ec4603f` 的等价输入：ProjectInfo 全部 `moduleType=Unknown` 时，只要 command 本身是标准 command，就必须能确定身份，不能依赖 ProjectInfo。

`ProjectInfo` 不再作为身份解析的必选前提，`resolveBuildIdentity()` 改为可返回空。禁止为无法确认的自定义 command 伪造 module 或 variant：无法解析的配置跳过共享导入，其他配置继续。

已知的“身份暂不可用”使用 debug 日志记录降级原因；文件写入、序列化等非预期异常使用 warn，并保留最终异常。处理必须逐条隔离，一条失败不能阻止其他配置保存或阻止 suggestion 创建。

当后续 Sync、配置修改事件或成功 Gradle build 获得足够身份信息时，再完成该配置的共享导入。

### 成功 Gradle build 的回写与导入

`updateAfterSuccessfulGradleBuild()` 与 `CliRunConfigurationGenerator.fromCompileOptions()` 必须能在 ProjectInfo 没有 Application 模块时完成回写：

- 回写基准按 `store.loadCurrent()`、当前选中配置、ProjectInfo 单配置 fallback 的顺序获取；三者都无法形成基准时跳过本轮回写并记录 debug，不抛异常、不伪造身份。
- 身份解析按上一节的顺序执行，标准 command 直接给出 module/variant。
- 实际 command、APK output、远端字段（含 `isRemoteSyncExcludePatternsCustomized`）和 current pointer 必须完整保存。

Report 等价输入（全部 `moduleType=Unknown`）下，suggestion 创建配置后执行一次成功 Gradle build，必须保存上述字段且不抛异常。

### 重试与 Tool Window

Sync 必须实际进入 `isSyncFinished = true` 的创建路径。重试条件使用用户可观察结果：RunManager 中仍不存在非默认 Jugg 配置。

每次重试重新读取 Android Studio suggestions，并重新检查最新 ProjectInfo；只有这些输入变化后才可能成功。沿用现有最多 7 次指数退避，不新增第二套调度器或长期后台轮询。

初始化、Sync 和重试共用一个成功出口。成功出口只执行现有副作用：

```text
确认存在非默认 Jugg 配置
  -> ToolWindow.setAvailable(true)
```

不在本方案中新增自动隐藏 Tool Window 的逻辑。

### 远程同步排除规则兼容

在 `CliRunConfiguration` 增加：

```kotlin
val isRemoteSyncExcludePatternsCustomized: Boolean = false
```

同步以下四个转换边界：

- IDEA `JuggRunConfigurationOptions` -> `CliRunConfiguration`（`IdeaCliRunConfigurationManager.toCliConfiguration()`）
- `CliRunConfiguration.applyTo(JuggRunConfigurationOptions)`
- `CliRunConfigurationGenerator.fromCompileOptions()`
- `CliRunConfiguration.toCompileOptions()`

这是 additive 字段。旧 schema version 1 JSON 缺失字段时按 `false` 读取，不提升 schema version，不失效旧配置，不要求迁移或完整重建。

回归必须覆盖全部转换边界和语义丢失场景，不能只验证 JSON 往返：

| 场景 | 断言 |
|---|---|
| 旧 schema version 1 JSON 缺字段 | 反序列化得到 `false`，patterns 原样保留 |
| JSON 往返 | patterns 与 customized 标志完整保留 |
| IDEA options -> `CliRunConfiguration` | customized 标志随 patterns 一起进入共享配置 |
| `CliRunConfiguration.applyTo(options)` | 写回 IDEA options 的 customized 标志与共享配置一致 |
| `fromCompileOptions()` | `JuggGradleCompileOptions.isRemoteSyncExcludePatternsCustomized` 写入共享配置 |
| `toCompileOptions()` | 共享配置的 customized 标志回到 `JuggGradleCompileOptions` |
| `customized=true` 且 patterns 为空 | `effectiveRemoteSyncExcludePatterns` 仍为空集合，不回落内置默认规则 |

验证边界：`CliRunConfiguration.applyTo(JuggRunConfigurationOptions)` 只在创建新配置时调用，而当前所有创建来源（suggestion、ProjectInfo fallback）产生的 target 都使用默认远程字段。因此 `customized=true` 的 `applyTo` 分支在生产路径上不可达，自动化只能覆盖 `applyTo` 的字段级一致性（新建配置的 IDEA options 与共享 profile 相同），不为它新增测试专用 seam。`customized=true` 的语义由 IDEA options -> `CliRunConfiguration`、`fromCompileOptions()`、`toCompileOptions()` 和 JSON 边界共同保护。

## 代码修改范围

### `JuggManager.kt`

- 初始化继续调用运行配置入口，但启动阶段不允许 ProjectInfo fallback。
- `updateProjectInfoAndRunConfigurations()` 在更新模型后调用统一的 Sync 创建/对账入口。
- Sync 和重试均传入 `isSyncFinished = true`。
- 每次尝试重新读取 suggestions。
- 配置成功后统一恢复 Tool Window。
- 保留 `TaskRunnerManager.runProjectWriteLocked()` 的锁顺序，不重新引入独立 monitor，避免恢复历史死锁。

### `IdeaCliRunConfigurationManager.kt`

- suggestion 直接生成配置，不再先构造 ProjectInfo fallback；启动阶段没有 suggestion 时不创建任何配置。
- 提取 suggestion 创建与 task 去重逻辑（按本节的有意收紧规则），并校验 suggestion 身份可解析。
- 删除 Sync 中“遍历全部 ProjectInfo Application 模块创建配置”的行为。
- ProjectInfo fallback 仅在 Sync 后、没有 suggestion 且没有非默认 Jugg 配置时创建一个。
- 已有配置逐条 Best-effort 导入；导入结果不决定 IDEA 配置是否可用。
- 身份解析改为可空，按标准 command、当前/历史共享配置、ProjectInfo 的顺序降级。
- Active Build Variant 选择改为消费创建后的 IDEA settings 和 suggestions，不以 ProjectInfo Application 列表作为创建前提；自定义 target 否决先于稳定 ID/精确目标复用。
- 只删除因本次改动而失效的重复入口（`syncExistingConfigurations()`、`matchesConfiguration()`、`findSuggestedConfiguration()`），不新增接口、repository、provider 或测试专用 seam。

### `CliRunConfiguration.kt`

- 增加 `isRemoteSyncExcludePatternsCustomized` 字段。
- 完成 options、成功 Gradle build 和 Standalone compile options 的双向转换。
- 保持默认生成配置的远程字段仍为本地默认值。

### 兼容层

`SuggestRunConfiguration` 已提供 moduleName、完整 compile command、APK output 和 variantName，本方案不修改兼容层接口。各 Android Studio 版本继续通过现有实现产生 suggestion。

## 失败与降级策略

| 失败点 | 行为 |
|---|---|
| suggestion 读取失败 | 记录 warn；保留已有配置；Sync 后可尝试 ProjectInfo 单配置 fallback 并进入有限重试 |
| ProjectInfo 无 Application | suggestion 存在时不受影响；没有 suggestion 时本轮不创建并重试 |
| 单条已有配置身份不可解析 | 保留 IDEA 配置，跳过该条共享导入，其他配置继续 |
| 单条共享配置保存失败 | 记录 warn 和最终异常，其他配置继续；IDEA 配置仍可使用 |
| suggestion 重复或歧义 | 创建阶段按 task 去重；选择阶段 fail-closed，保持当前选择 |
| Tool Window 尚未初始化 | `getToolWindow()` 返回空时局部结束，不影响配置结果 |
| 7 次重试后仍无配置 | 记录最终 debug/warn 结果，等待下一次 Sync、重启或用户手工创建，不伪造默认配置 |

## 测试价值判断

本方案涉及自动创建、恢复、去重、配置持久化和重试，均属于稳定且用户可观察的行为，并已经由 Report `7ec4603f` 提供真实失败证据，全部通过测试价值门禁。

不通过反射断言私有方法，不扫描生产源码，不为测试增加 provider/supplier。测试直接检查 RunManager 配置、selected configuration、共享 Store、重试结果和 Tool Window 状态。

## 测试矩阵

| 层级 | 测试 owner | 场景 | 修改前预期 | 修改后预期 |
|---|---|---|---|---|
| L2 | `IdeaCliRunConfigurationFlowTest` | ProjectInfo 无 Application，suggestion 为 `app/debug` | `ensureConfiguration()` 抛异常 | 创建 IDEA 配置并写入共享 Store |
| L2 | `IdeaCliRunConfigurationFlowTest` | ProjectInfo 无 Application，已有标准 Jugg 配置 | 初始化/导入失败 | IDEA 配置立即可用，并从 command 完成共享导入 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 一条自定义配置身份不可解析，另一条可解析 | 整批导入中止 | 仅跳过不可解析配置，其他配置保存成功 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 两条 suggestion 指向同一 Gradle task | 依赖 ProjectInfo 或重复处理 | 只创建一个配置 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 已有 `assembleDebug --offline` | 可能创建稳定默认配置 | 不重复创建，保留原字段 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 已有 `deployRelease`，suggestion 为 `app/release` | ProjectInfo identity 可能阻止标准配置 | 创建标准 suggestion 配置，但不抢占自定义选择和 current pointer |
| L2 | `IdeaCliRunConfigurationFlowTest` | 稳定 ID 被 `deployRelease` 自定义 target 占用 | 可能创建同 ID 配置并覆盖共享 profile | 不创建重复 ID、不覆盖共享 Store、不改变选择 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 首选名称与已有 Jugg 配置名称冲突 | IDEA 名称与共享 Store 名称可能不一致 | IDEA 与共享 Store 使用同一最终唯一名称 |
| L2 | `IdeaCliRunConfigurationFlowTest` | suggestion 多 task / variant 与 command 不一致 / `variantName` 为空 | 可能伪造稳定身份 | 跳过该 suggestion，不创建、不参与选择 |
| L2 | `IdeaCliRunConfigurationFlowTest` | ProjectInfo 无 Application，成功 Gradle build 回写 | `resolveBuildIdentity()` 抛异常 | 保存实际 command、APK output、远端字段和 current pointer |
| L2 | develop 版 `JuggManagerRunConfigurationSyncTest` | 启动无 suggestion，Sync 后 suggestion 可用 | Sync 不进入创建重试 | Sync 创建配置并恢复 Tool Window |
| L2 | 同上 | 首次 Sync suggestion 为空，后续重试可用 | 重试入口不可达 | 有限重试读取新 suggestion 后成功，且不重复创建 |
| L2 | 同上 | 连续两次 Sync 且 suggestion 不变 | 缺少去重保护 | 只创建一个配置，选择保持不变 |
| L1 | `CliRunConfigurationTest` | 自定义远程排除规则：旧 JSON、JSON 往返、`fromCompileOptions()`、`toCompileOptions()`、空 patterns | customized 标志丢失 | patterns 与 customized 标志完整保留，空 patterns 不回落默认规则 |
| L2 | `IdeaCliRunConfigurationFlowTest` | 自定义远程排除规则：IDEA options -> `CliRunConfiguration`、`applyTo()` | customized 标志丢失 | 两侧标志与 patterns 一致（`applyTo()` 的非默认分支当前只有默认值可观察，见"验证边界"） |
| L1 | `CliRunConfigurationTest` | 标准 command / 已知身份 / 未知 command 的身份解析 | 无 ProjectInfo 时抛异常 | 依次降级，未知 command 不伪造身份 |
| L1/L2 | 现有 active variant owner | 标准 variant 切换及自定义 command | 可能受创建重构影响 | 现有 fail-closed 行为不变 |

不整体复制 main 的 1122 行旧测试文件。只将上述缺失行为移植到 develop 现有 owner；JuggManager 生命周期没有合适 owner，因此恢复一个聚焦的 `JuggManagerRunConfigurationSyncTest`，只覆盖 Sync 入口、有限重试、Tool Window 与重复 Sync 去重。锁串行语义由现有 `TaskRunnerManagerTest` / `ProjectExecutionLockTest` 负责，不在该文件重复验证并发。

该文件通过 `onSyncEvent(SyncEvent.SUCCEEDED)` 这一真实 Sync 入口驱动，用 `TestCoroutineScheduler` 推进重试延迟，并为外部依赖 `GradleProjectInfoLocalFetchManager` 提供真实实例（预置 `gradle_project_infos.json`，命中其"无需更新"早退分支），避免 mock 带默认参数的 Kotlin 方法。

## 实施顺序

1. 在 `CliRunConfigurationTest` 增加 customized flag 四个转换边界、旧 JSON、空 patterns 失败测试，和身份解析降级失败测试；补共享字段与 `resolveBuildIdentity()` 可空签名。
2. 在 `IdeaCliRunConfigurationFlowTest` 增加“无 Application + 有 suggestion”和“已有配置不依赖 ProjectInfo”的失败测试，确认因当前 fallback/导入行为失败。
3. 增加 suggestion 去重、任务语义收紧、自定义 task、稳定 ID 冲突、名称一致性和单配置失败隔离测试。
4. 调整 `IdeaCliRunConfigurationManager`：suggestion 直接创建、身份解析降级、逐条导入、取消全部 Application 模块遍历、把自定义 target 否决提前到复用之前。
5. 增加 JuggManager Sync、重试和 Tool Window 失败测试。
6. 调整 `JuggManager` 生命周期接线，恢复 Sync 后有限重试和统一成功出口。
7. 执行现有 Active Build Variant 回归，确认自定义 command 和非 Jugg selection 行为不变。
8. 同步知识库、设计记录和 Wiki。
9. 执行定向测试、编译验证和 diff 检查。

## 验证命令

实施完成后执行：

```bash
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.project.runtime.IdeaCliRunConfigurationFlowTest'
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.JuggManagerRunConfigurationSyncTest'
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.project.runtime.CliRunConfigurationTest'
./gradlew :idea:compileKotlin
git diff --check
```

如果最终没有新增独立的 `JuggManagerRunConfigurationSyncTest`，第二条命令替换为实际承载生命周期回归的现有 L2 owner。

## 文档同步

实现完成后同步：

- `docs/ai_knowledge/04_engineering_ide.md`：改为 IDEA suggestion 优先、ProjectInfo 单配置 fallback，并说明启动/Sync 时机。
- `docs/task/2026-08/standalone_jugg_cli_design.md`：修正“SuggestRunConfiguration 已废弃”和“每个 Application reconcile”的历史结论，记录 IDEA/Standalone 双来源边界。
- `docs/task/2026-08/sync_keep_selected_run_configuration.md`：保持选择语义不变，补充创建阶段已按 main suggestion 逻辑对齐。
- `docs/wiki/zh/guide/run-configuration.md` 与 `docs/wiki/guide/run-configuration.md`：检查自动创建、Gradle path 和 Active Build Variant 描述，以中文页为内容基准严格同步英文页。

不需要更新 `98_code_map.md`，本方案不新增或移动核心类。

## 完成标准

- Report `7ec4603f` 的等价输入下，即使 Jugg ProjectInfo 全部为 `Unknown`，Sync 后仍能根据 Android Studio `app/debug` suggestion 创建配置。
- Android Studio suggestion 存在时，IDEA 创建范围与 main 对齐；Gradle task 去重按本节定义的有意收紧规则执行，多 task 命令只按精确 command 去重。
- 已有 Jugg 配置不因 ProjectInfo 缺少 Application 而失效；成功 Gradle build 在 ProjectInfo 无 Application 时仍能完成回写与导入。
- 稳定 ID 被自定义 target 占用时不创建重复配置、不覆盖共享 Store、不改变当前选择。
- 标准 suggestion 配置可以创建，但目标 variant 已有自定义 target 时不得抢占当前选择和 current pointer。
- Sync 后 suggestion 暂不可用时会执行有限重试，恢复后只创建一次配置。
- Sync 或重试创建成功后 Jugg Tool Window 可用。
- Standalone 仍可在无 IDEA 环境下从共享 Store 或 Gradle ProjectInfo 初始化和运行。
- 新配置仍不继承默认远程编译模板。
- 自定义远程同步排除规则经过 IDEA、共享 JSON 和 Standalone 往返后语义不丢失。
- 现有 Active Build Variant fail-closed 回归全部通过。

## 残余风险

- Android Studio suggestion 属于 IDE Host 信息，不保证任何时刻都可用；本方案通过 Sync 后重试和 ProjectInfo 单配置 fallback 收口，不建立长期轮询。
- 旧自定义配置若同时缺少可解析 command、suggestion、ProjectInfo 和历史共享配置，无法安全推断 module/variant；该配置继续在 IDEA 可用，但 Standalone 暂时不可见，直到后续获得确定身份。
- 项目写锁会串行 Sync、重试和其他 Runtime 写事务。实现必须复用现有锁顺序，不能在项目锁外再引入 Run Configuration monitor。
