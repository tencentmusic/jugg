# Sync 后误切 Jugg Run Configuration

> 日期：2026-08-31
> 报告：`1e3cc9ef`
> 状态：develop / main 已统一为 fail-closed；仅标准生成配置自动跟随 Active Build Variant，自定义或不确定 command 一律 keep

本文记录修改前表现、已落地的修改后表现，以及讨论过但未全部落地的方案与问题。实现细节以代码为准；Sync 选中语义的权威描述见 `docs/ai_knowledge/04_engineering_ide.md`。

## 1. 用户可见问题

插件更新后用户未改 Run Configuration，却出现 `Compile command changed` 并降级走完整 Gradle。用户预期：只更新插件时不应因 compile command 字符串变化而丢失增量。

现场（Kugou / Windows）：

- 用户选中 `jugg:musicApp`，command 为 `gradlew.bat :app:musicApp:deployDebug --stacktrace`
- Sync（含 IDE 标记 `SKIPPED`）后选中变成 `jugg:app.musicApp`，command 为 `assembleDebug`
- MCP `compile` 跟随当前选中项，于是 `FullBuildInfo.compileCommand` 与本轮 command 不一致，强制 Gradle
- Windows 上 `assembleDebug` 继续失败（缺 `bash-shadow-...`），与本次误切是后续放大，不是根因

MCP 只是放大器：它优先用当前选中的 Jugg Configuration。真正改选中项的是 Sync 后的自动切换。

## 2. 修改前表现

两边都把「选中 command 不是当前默认 / suggestion 的 assemble（或精确 task）」当成要切换。自定义同 variant task（`deployDebug`）会被换成 `assembleDebug`。

### 2.1 develop（`IdeaCliRunConfigurationManager`）

Sync SUCCEEDED / SKIPPED 后 `reconcileActiveBuildVariants()`：

- 按 application module 的当前 `buildVariant` 生成默认 `assemble{Variant}` 配置
- 身份只认 `:modulePath:assemble{Variant}`
- `selectActiveVariant` 发现选中项对不上默认 assemble 配置，就改选到该配置

因此 `:app:musicApp:deployDebug` 与 `:app:musicApp:assembleDebug` 被当成不同身份。父模块路径 `:app:` 还有可能误匹配嵌套模块 task。

### 2.2 main（`JuggManager.trySelectActiveBuildVariantConfiguration`）

Sync 后 `tryCreateRunConfigurations()` 用 `AsDeployerCompat.getSuggestRunConfigurations()`：

- 已有 command 必须包含 suggestion 的唯一 Gradle task 才算同一目标
- 选中 Jugg 配置的 token 里没有该 task 时，自动选中同模块的 suggestion 配置
- 同时会新建缺失的 `assembleDebug` 配置

`deployDebug` 的 token 不含 `app:assembleDebug`，同样被切走。`--offline` / `-P` 等附加参数当时已经能保留（task 相同即可），但换动词不行。

### 2.3 共同后果

下一轮 Run / MCP compile 读到新的 assemble command，与上次成功 full build 的 `FullBuildInfo.compileCommand` 字符串不等，打出 `Compile command changed` 并 Gradle 回退。

## 3. 已落地的修改后表现

产品意图相同：**只有能证明当前项和目标项都是未修改的标准生成配置，且 build variant 确实变化时才改选中项。** 两边代码结构不同，判定式不同，这是预期，不是漏移植。

### 3.1 develop

入口：`JuggManager.updateProjectInfoAndRunConfigurations` 与 `IdeaCliRunConfigurationManager.selectActiveVariant`。

判定分为 source、variant、target 三道门禁：

- source：选中 command 必须精确等于单 task `./gradlew :modulePath:assemble{Variant}`，不接受附加参数或其他 task。
- variant：Sync 后从普通 Android Run Configuration 的最新 Android model suggestion 读取 active variant；suggestion command 必须同样是标准 assemble command，完整 Gradle module path 必须与 source 一致，且 `variantName` 必须与 command 一致。
- target：优先按 active variant 的稳定配置 id 唯一找到精确 command；兼容旧配置时允许唯一精确匹配 suggestion command + APK output；都不存在时创建稳定目标。仅存在同 variant 自定义 target 时仍 keep。

任一门禁失败都 keep。`matchesConfiguration()` 仍用于避免重复创建和保留已有配置，但不再用于挑选自动切换目标。

例子：

| 选中 command | active | 结果 |
|---|---|---|
| `./gradlew :app:musicApp:assembleDebug` | `release` | switch |
| `gradlew.bat :app:musicApp:deployDebug --stacktrace` | `release` | keep |
| `./gradlew :app:musicApp:assembleDebug --offline` | `release` | keep |
| `./gradlew :app:musicApp:uploadDebug` | `release` | keep |
| `./gradlew happyBuild` | `release` | keep |

suggestion 只作为 Run Configuration 切换的只读 active variant 证据，不覆盖 CompileContext。模块身份使用完整 Gradle task 路径，因此根工程 `:app` 与 included build `:SMCommon:app` 不会因简单名相同而互相切换。suggestion 缺失、重复、command 与 variant 不一致，或兼容层只能得到冲突身份时均允许漏切并保留当前选择。

该调整修复了 `JuggDuplicateAppModules`：IDEA model 同时存在根工程 `app` 与 included build `SMCommon.app` 时，`JuggProjectInfo` 合并可能为了编译产物安全保留旧 Gradle variant；Run Configuration 不再受该编译模型降级影响，直接使用 IDEA 当前 suggestion，同时不改变 R.jar、classpath、Manifest、签名等 CompileContext 消费面。

另：`Compile command changed` 日志补上 `last=` / `current=`。MCP 选配置逻辑未改。

### 3.2 main

main 没有 `IdeaCliRunConfigurationManager` / `CliRunConfiguration`，不能原样 cherry-pick。入口仍是 `trySelectActiveBuildVariantConfiguration`。

main 没有稳定 CLI 配置 id，因此采用更严格的 command 与 suggestion 精确匹配：

- 选中 command 必须精确符合单 task `./gradlew :modulePath:assemble{Variant}`，不接受附加参数或其他 task。
- active suggestion 也必须符合相同标准格式，且 `variantName` 与 command 一致。
- source 与 active variant 不同。
- 目标配置必须唯一，并同时精确匹配 suggestion 的 command 与 APK output path。

main 的 Sync 顺序是先 `tryCreateRunConfigurations`，后 `updateProjectInfo`，因此切换时不能依赖此刻的 CompileContext（往往仍是旧 variant）。suggestion 来自 AS，已经是新的。

例子：

| 选中 command | suggestion | 结果 |
|---|---|---|
| `./gradlew :app:assembleDebug` | `assembleRelease` / `release` | switch |
| `gradlew.bat :app:deployDebug --stacktrace` | `assembleRelease` / `release` | keep |
| `./gradlew :app:packageDebug` | `packageRelease` / `release` | keep |
| `./gradlew :app:assembleDebug --offline` | `assembleRelease` / `release` | keep |
| `./gradlew happyBuild` | `assembleRelease` / `release` | keep |

main 仍会按既有逻辑创建缺失 suggestion 配置，但自定义或不确定 command 不会触发自动选择。

### 3.3 两边实现不同但产品语义一致

develop 与 main 都使用 Sync 当下的 Active Build Variant suggestion。develop 额外保留共享 CLI profile 的稳定配置 id，并兼容唯一精确匹配 command + APK output 的旧配置；main 仅使用精确 command 与 APK output。两边都不再通过任意 task 后缀推断自定义 command，也都不使用 `FullBuildInfo` 作为 Sync 当前 variant。

统一失败策略是：无法证明 source、variant 或 target 时 keep，接受边界漏切。

## 4. 第一版讨论记录

以下内容用于保留决策历史，不代表当前最终行为；最终语义以第 3 节和 `04_engineering_ide.md` 为准。

### 4.1 动词白名单（已弃用）

把 `assemble|deploy|install|bundle` + `{Variant}` 都当成同一身份。

问题：过于专用。下一个自定义动词（`happyBuild`、`packageDebug` 以外的名字）仍会误切。已从 develop 去掉。

### 4.2 仅当 leaf task 编码了另一个已知 variant 才切（develop 第一版曾落地）

优点：不枚举动词；`deployDebug` keep；父模块不会吞嵌套模块；`paidDebug` 最长后缀优先。

问题：

- 依赖 CC `variants` 是否齐全。列表为空时只剩当前 `buildVariant`，`assembleDebug` 在 active 已是 `release` 时可能解析不出、该切却 keep。
- `endsWith` 无 CamelCase 边界时，未登记 flavor 可能塌到更短后缀。
- 加宽后的 `matchesConfiguration` 会让「真切 variant 之后选哪一条」变成配置列表顺序（review 指出，未改）。

### 4.3 main：编码了当前 suggestion variant 才 keep（main 第一版曾落地）

优点：不需要完整 variant 列表；报告场景与 `debug → release` 都能过；改动只在 `JuggManager`。

问题：`./gradlew happyBuild`、`:app:foo` 仍会切。与 develop 的「未知 → keep」不一致。

### 4.4 让 main 的 `happyBuild` 也不切

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 没有 module leaf task 则 keep | `happyBuild` keep；有 `:app:…` 仍按现逻辑 | `:app:foo` 仍切；可能仍创建多余 assemble 配置 |
| B. 对齐 develop，用 CC `variants` | 解析不出则 keep | main Sync 时 CC 可能未更新，`debug → release` 会漂 |
| C. 从已有 Jugg 配置名拼已知 variant | 不读 CC | 配置名叫 `jugg:app` 且没有第二条配置时，拼不出 `debug`，真切 variant 可能 keep |

### 4.5 用「有 CC 且 module.buildVariant 有值，且 suggest variant ≠ 当前 variant」才切

字面把「当前 variant」理解成 CC 的 `buildVariant`，**两边都不能当统一规则**：

- develop 先更新 CC 再 reconcile，suggest 的等价物就是 `module.buildVariant`，两者相同，**永远不切**。
- main 先改选中项再更新 CC。Sync 当下 suggest 已是新 variant、CC 往往是旧的，会因为时序差而切；CC 已经跟上（重开工程、模型已是新 variant）时又**不切**，选中的旧 debug 配置会留下。

能同时覆盖「`happyBuild` 不切、同 variant 自定义不切、真换 variant 要切」的读法是：

1. 门禁：有 CompileContext，且对应 application module 的 `buildVariant` 非空。不满足 → 不切。
2. **当前 variant** = 选中 Run Configuration 的 command 解析结果。解析不出 → 不切。
3. **目标 variant**：main 用 suggestion 的 `variantName`（AS 已新）；develop 用已更新的 CC `buildVariant`。不一致才切。

未实施。落地的话 main 还要把 `CompileContextManager` 接进选中逻辑，现有 `JuggManagerRunConfigurationSyncTest` 是空 mock，所有 switch 用例都要补 project info。

### 4.6 在 full build 完成且初始化完成时，把当时的 build variant 写入 `FullBuildInfo`

`FullBuildInfo` 现有字段只有 `compileCommand` / `buildTarget` / `createdAt`。Gradle 成功且 CC 已就绪时写入 `buildVariant`，得到的是 **「上次成功 Gradle 是哪个 variant」**，不依赖这次 Sync 有没有跑完 `updateProjectInfo`。

可以缓解 main「Sync 时 CC 是旧的」：IDE 还在 debug、上次也是 debug 时，`happyBuild` / `deployDebug` 都不会被切走。

不能单独当选中逻辑里的「当前 variant」：

- 上次 full build 是 `release`，用户已手动选回 `deployDebug`，IDE 也是 debug：suggest=`debug`、记录=`release`。用记录当「当前」会把 `deployDebug` 切成 `assembleDebug`，与本次要修的误切同类。
- 选中 `happyBuild`、IDE 改到 release：会切走 `happyBuild`。develop 对解析不出的 command 不切。这是产品选择，不是「记录了就能和 develop 对齐」。

它适合做编译基线（与 `Compile command changed` 同类），不适合替代「选中 command 自己的 variant」。若要用，只能作为选中 command **解析不出**时的弱回退，不能作为唯一当前 variant。

未实施。也不应把 `FullBuildInfo.compileCommand` 当成 Sync 阶段的当前 variant：IDE 已切到 release 但还没跑过新的 full build 时，基线仍是旧 command，会把 Sync 目标判反。

## 5. 明确的非目标与已排除方向

- 不改 MCP `compile` 的配置选择顺序（选中 Jugg → 上次 full build → 列表首项）。
- 不把 develop 的 CLI run configuration 集合搬到 main。
- 不以动词白名单表达「同一 variant」。
- 不在文档或实现里把 FullBuildInfo 当作 Sync 选中项的当前 variant。

## 6. 已决策事项

1. `happyBuild`、无 module leaf、自定义动词、附加参数和多 task command 全部 keep。
2. develop 只切到 `generateForModule()` 的稳定 id 标准目标，不选择任意同 variant 自定义配置。
3. main 只切到唯一精确匹配 suggestion command + APK output 的标准目标。
4. 不给 `FullBuildInfo` 增加 `buildVariant`，也不把它作为 Sync 选择判断的回退。

## 7. 验证与提交

- 报告 `1e3cc9ef` 为修改前失败证据。
- develop：`CliRunConfigurationTest`、`IdeaCliRunConfigurationFlowTest`。
- main：`JuggManagerRunConfigurationSyncTest`。
- develop：`39647216` `[bugfix] switch selected run configuration only on a real variant change`
- main：`f16792e5d` 同上标题（移植到 `trySelectActiveBuildVariantConfiguration`）
- 第二版 fail-closed：新增 suffix 碰撞、assemble 附加参数、根级自定义 task、自定义目标配置的失败测试；两边均保留标准 assemble variant 切换回归。
