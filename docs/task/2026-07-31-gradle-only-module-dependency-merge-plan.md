# Gradle-only 模块依赖安全合并方案

## 1. 背景

`app` 同时依赖 `library1` 和 `kmpCompose`：

```text
app -> library1
app -> kmpCompose
```

Android Studio IDE project info 只识别到 `app -> library1`，Gradle project info 能识别完整依赖。`JuggProjectInfoMerger` 会把 IDE 中缺失的 `kmpCompose` 模块加入最终模块集合，但当前 `moduleDependencies` 使用 `setIfEmpty()` 整体保留非空的 IDE 列表，因此没有补回 `app -> kmpCompose`。

结果是 `BaseCompileContext.getModuleDependencies()` 无法把 `kmpCompose` Kotlin 输出加入 `app` 的增量编译 classpath，最终出现：

```text
error: unresolved reference 'KmpComposeAndroidResourceCase'
```

## 2. 已确认事实

1. `KmpComposeAndroidResourceCase.class` 在增量编译前已经存在，问题不是目标模块没有完成 Gradle 编译。
2. Gradle project info 中 `app.moduleDependencies` 为 `[library1, kmpCompose]`。
3. IDE project info 中 `app.moduleDependencies` 为 `[library1]`，且 IDE project info 不包含 `kmpCompose` 模块。
4. 最终模块集合包含 Gradle-only 的 `kmpCompose`，但 `app.moduleDependencies` 仍只有 `library1`。
5. 实际 Kotlin 编译 classpath 包含 `library1` 输出，不包含 `kmpCompose` 输出。
6. 2024 年历史实现曾直接合并 IDE 与 Gradle 的 `moduleDependencies`，提交 `a85994d65` 因合并后出现环依赖改为整份选择。
7. `ModuleCompileOrderUtils` 会把环中的剩余模块追加到末尾，但这只是避免模块丢失，不能恢复可靠的依赖顺序。

## 3. 问题定义

需要同时满足以下约束：

- 补回 IDE 无法表达、但 Gradle 已确认存在的模块依赖。
- 不恢复 IDE/Gradle 依赖集合的无条件并集。
- 不改变现有 IDE 依赖边，不主动清理历史依赖模型。
- 新增的 Gradle 依赖边不能制造新的环。
- Gradle 快照缺失、目标模块未进入最终模块集合或依赖边不安全时局部跳过，保留其他有效项目模型。
- 行为必须确定，不能因 Map 遍历顺序不同得到不同结果。

## 4. 方案比较

### 4.1 方案 A：Gradle 依赖整体覆盖 IDE 依赖

当 Gradle project info 更新时，直接使用 Gradle `moduleDependencies`。

优点：

- 实现最简单。
- 当前 KMP 场景可以恢复完整依赖。

问题：

- 历史上已经出现过 Gradle/IDE 模块名不一致和不可靠依赖快照。
- 可能删除 IDE 独有但仍有效的依赖。
- Gradle 快照可能来自较早的 dry-run 或 include build 旧副本，整体覆盖影响面过大。

结论：不采用。

### 4.2 方案 B：IDE 与 Gradle 依赖无条件并集

对每个模块执行去重并集。

优点：

- 不容易遗漏依赖。
- 逻辑直观。

问题：

- 历史提交 `a85994d65` 已证明跨来源依赖边组合可能形成环。
- 一旦形成环，拓扑排序只能降级追加剩余模块，编译顺序不再可信。
- 会把本次需求扩大为所有 IDE/Gradle 依赖差异的通用治理。

结论：不采用。

### 4.3 方案 C：仅补 Gradle-only 模块依赖，并在补入前检查环

保留当前 IDE 依赖列表，只处理目标模块完全不在 IDE project info 中的 Gradle 依赖。每条依赖补入前执行一次小型可达性检查；形成环则打印日志并放弃该边。

优点：

- 精确覆盖本次失败：IDE 完全看不到 `kmpCompose` 模块，Gradle 能看到。
- 不改变 IDE 已识别模块之间的依赖关系，避免恢复历史上的大范围并集。
- 可以在合并边界阻止新环进入最终项目模型。
- 实现可限制在 `JuggProjectInfoMerger` 内，不增加新类型或公共接口。

代价：

- 不解决“IDE 和 Gradle 都包含目标模块，但 IDE 单独漏了一条边”的更宽泛问题。
- 需要一个小型图可达性检查。

结论：推荐采用。未被当前失败证据覆盖的普通缺边场景留作后续独立问题，不在本次扩展。

## 5. 推荐设计

### 5.1 合并时机

不要在逐字段构造 `mergedModuleInfo` 时直接并集依赖。应在以下步骤完成后统一处理：

1. IDE 与 Gradle 同名模块完成字段合并。
2. 允许进入当前 `BuildTarget` 的 Gradle-only 模块已经加入 `mergedModules`。
3. Gradle 模块名称已经过 `nameUpdateMap` 规范化。

此时最终模块集合和当前依赖图都已完整，可对候选边做可靠判断。

建议在 `JuggProjectInfoMerger.doMerge()` 返回 `JuggProjectInfo` 前增加一个私有步骤：

```text
mergedModules
  -> find dependencies targeting Gradle-only modules
  -> check dependency-to-owner reachability
  -> add safe dependency or log and skip
  -> final merged modules
```

### 5.2 候选边条件

Gradle 依赖边 `owner -> dependency` 只有同时满足以下条件才进入候选集合：

1. `owner` 存在于最终 `mergedModules`。
2. `dependency` 存在于最终 `mergedModules`。
3. `dependency` 不存在于原始 IDE project info，即目标是 Gradle-only 模块。
4. `dependency` 存在于规范化后的 Gradle project info。
5. 当前 `owner.moduleDependencies` 尚未包含该依赖。
6. `owner != dependency`，拒绝名称规范化产生的自依赖。
7. 目标模块已经通过 `ModulePathMergePolicy.shouldIncludeGradleOnlyModule()` 的 BuildTarget 过滤。

第 3 条是控制影响面的核心门禁。它保证本次只补 IDE 根本无法表达的模块，不重新合并两个来源都认识的普通模块关系。

### 5.3 环检测

`moduleDependencies` 的方向是：

```text
owner -> dependency
```

补入 `A -> B` 前，从 `B` 沿当前已合并依赖图检查是否能够到达 `A`：

- 能到达：补入后会形成环，打印 warning 并放弃该边。
- 不能到达：安全补入。

使用迭代 DFS/BFS 即可，不引入图框架或新类。搜索只访问最终模块集合中存在的依赖，避免外部或已过滤模块干扰判断。

### 5.4 处理示例

当前失败场景：

```text
IDE:
app -> library1

Gradle:
app -> library1
app -> kmpCompose

Gradle-only modules:
kmpCompose
```

`app -> kmpCompose` 满足候选条件，且 `kmpCompose` 无法沿当前图到达 `app`，因此安全加入。

跨来源成环场景：

```text
IDE:
C -> A

Gradle:
A -> B
B -> C

Gradle-only modules:
B
```

Gradle-only 模块 `B` 已携带 `B -> C`。补入 `A -> B` 前，从 `B` 可以经 `C -> A` 回到 owner，因此补入后会形成 `A -> B -> C -> A`，必须打印日志并放弃。

### 5.5 失败与降级

- 目标模块不存在：跳过该边，保留当前 IDE 依赖图。
- 自依赖：跳过该边。
- 检测到环：只放弃当前依赖，继续处理其他 Gradle-only 依赖。
- 因环被放弃时记录 warning 日志，包括 owner、dependency 和原因。
- 不修改 `ModuleCompileOrderUtils` 的现有环依赖兜底；它继续处理输入模型中原本就存在的环。
- 不因单条依赖无法补入而让整个 project info merge 失败。

## 6. 代码影响范围

### 6.1 生产代码

仅修改：

- `main/src/main/java/com/sickworm/intellij/jugg/project/merger/JuggProjectInfoMerger.kt`

建议保持为两个小型私有方法：

- 补入目标为 Gradle-only 模块的依赖。
- 判断目标模块是否能够沿当前依赖图到达 owner。

不新增接口、配置项、数据字段或持久化版本。

### 6.2 非目标

- 不把 Gradle 依赖整体设为权威来源。
- 不合并两个来源都认识的模块之间的普通缺边。
- 不删除 IDE 独有依赖。
- 不重构 `ModuleCompileOrderUtils`。
- 不新增完整 Kotlin source-set dependency graph。
- 不处理 KMP class 输出路径；本次目标 class 已存在于现有 `tmp/kotlin-classes/debug`。

## 7. 测试与验证

### 7.1 测试价值判断

该行为通过测试价值门禁：项目模型依赖图是稳定、可观察、会直接影响编译 classpath 和模块顺序的兼容契约；当前日志已经提供真实失败证据。

### 7.2 测试矩阵

| 层级 | 测试 owner | 场景 | 修改前 | 修改后 |
|---|---|---|---|---|
| L1 | `JuggProjectInfoMergerAndroidTestTest` | IDE 有 `app -> library1`，Gradle 额外包含 Gradle-only `kmpCompose` | 合并结果缺少 `app -> kmpCompose` | 安全补入 `app -> kmpCompose` |
| L1 | `JuggProjectInfoMergerAndroidTestTest` | Gradle-only 候选边会与 IDE/Gradle 现有边组合成环 | 若直接并集会形成环 | 拒绝候选边，保留无新增环的图 |
| L1 | `JuggProjectInfoMergerAndroidTestTest` | Gradle 缺边的目标模块已存在于 IDE project info | 现状不补入 | 继续保持不补入，限制变更范围 |
| L3 | `KmpComposeDeployFlowTest#deployComposeResourcesAndConsumeAccessorsAtRuntime` | app 源码引用 KMP Android 业务类，同时增量编译 Compose resource | `unresolved reference KmpComposeAndroidResourceCase` | 增量编译、部署和运行时资源读取成功，无 Gradle fallback |

### 7.3 TDD 顺序

1. 先向 `JuggProjectInfoMergerAndroidTestTest` 添加 Gradle-only KMP 依赖场景，确认因缺少依赖边而失败。
2. 添加跨来源组合成环场景，确认测试能阻止无条件并集方案。
3. 实现受限补边和环检测。
4. 运行定向 L1 测试。
5. 复用当前失败的 L3 Flow，确认 `KmpComposeAndroidResourceCase` 可被 app 增量编译解析并运行。

建议命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMergerAndroidTestTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest.deployComposeResourcesAndConsumeAccessorsAtRuntime"
./gradlew :idea:compileKotlin
```

## 8. 实施步骤

1. 在现有 merger L1 owner 中补失败测试，覆盖安全补边和拒绝成环两种行为。
2. 保留当前字段级 `moduleDependencies` 基线选择，不恢复 `mergeWithBase()`。
3. 在 Gradle-only 模块加入完成后执行依赖补边后处理。
4. 对目标为 Gradle-only 模块的依赖执行存在性、自依赖和重复依赖门禁。
5. 补入前检查目标模块是否能到达 owner；能到达则打印 warning 并放弃。
6. 对安全依赖更新对应 `ModuleInfo.copy(moduleDependencies = ...)`。
7. 记录成功补入或因环放弃的结果。
8. 执行定向 L1、现有 L3 Flow 和 `:idea:compileKotlin`。
9. 同步 `docs/ai_knowledge/04_engineering_project.md`，补充 Gradle-only 模块依赖的安全合并规则。

## 9. 残余风险

- 若 IDE 和 Gradle 都包含目标模块，但 IDE 漏掉依赖边，本方案不会补入。这是刻意限制的首版边界。
- 若 Gradle-only 模块自身携带错误依赖，当前只阻止新增边形成环，不校正该模块已有依赖。
- 最终项目模型可能已经从 IDE 来源带有环；本方案不扩大修复范围，继续由现有 compile order 兜底。
- 多条 Gradle-only 依赖按稳定模块顺序处理；先安全补入的边会参与后续成环检查。

## 10. 决策

推荐实施方案 C：只补目标为 Gradle-only 模块的缺失依赖，补入前执行一次小型成环检查；失败则打印日志并放弃该边。

该方案直接解决当前 KMP classpath 缺失，同时保持最小影响面，不重演历史上的无条件依赖并集问题。
