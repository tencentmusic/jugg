# Issue #36 Runtime 依赖差分调整方案

## 1. 用户可见问题

用户升级 Maven 依赖：

```text
com.mars.netdisk:feature-search:13.32.3.2748
→ com.mars.netdisk:feature-search:scan-13.33.0-test1
```

Jugg 已识别并处理 `feature-search` 自身的 class、资源和 Manifest，但设备仍运行旧实现。

现场日志显示 `feature-search` 已进入依赖差分和部署，因此可以排除“顶层依赖完全没有被识别”。实际缺口是该依赖通过 Maven runtime scope 引入了仅在 `RuntimeClasspath` 可见的实现库：

```text
App
└── feature-search                 CompileClasspath 可见
    └── runtime 实现库              仅 RuntimeClasspath 可见
```

原实现只读取 `CompileClasspath`。runtime 实现库没有进入依赖差分、增量 dex 和部署，设备继续使用完整构建 APK 中的旧版本。

## 2. 已落地结果

修复提交：

```text
a0785f718 [bugfix] prevent runtime-only dependency updates from using old code
```

已经实现：

1. Application 和 Dynamic Feature 从当前 variant 的 `RuntimeClasspath` 读取外部库，保存到 `ModuleInfo.runtimeLibraryDependencies`。
2. 非 APK 根模块不额外解析 runtime 图。
3. APK 根模块的 runtime configuration 不存在或解析失败时明确失败，不保存权威空列表。
4. `DependencyDiffResultSet` 根据完整构建基线统一决定两份 diff 是否加入 runtime 依赖。
5. compile/runtime 中同一个物理 artifact 按绝对路径去重。
6. 保持 `FullBuildInfoSerializer.VERSION = 1`，不让旧完整构建基线失效。

## 3. 基线兼容策略

本轮是否比较 runtime 依赖，只由最近一次完整构建基线决定：

```kotlin
val includeRuntimeDependencies =
    fullBuildDependencies.modules.values.any {
        it.runtimeLibraryDependencies.isNotEmpty()
    }
```

两份 diff 必须采用同一模式：

| 完整构建基线 | `diffResult` | `diffResultWithFull` |
|---|---|---|
| runtime 为空 | compile only | compile only |
| runtime 非空 | compile + runtime | compile + runtime |

不能根据 last snapshot 单独启用 runtime。旧完整基线为空、一次增量读取后的 last snapshot 已包含 runtime 时，如果两份 diff 分别选模式，会出现展示结果识别到变化、实际编译结果却忽略变化。

旧基线继续使用 compile 范围，不强制用户升级后立即完整构建。之后任一次成功完整 Gradle 构建记录了非空 runtime 基线，后续依赖增量才启用 runtime 检测。

## 4. 为什么当前保留 compile + runtime

### 4.1 Gradle 语义

从最终 APK 产物看，`RuntimeClasspath` 比 `CompileClasspath` 更接近打包真值：

```text
compileClasspath ≈ implementation + compileOnly
runtimeClasspath ≈ implementation + runtimeOnly
```

因此，如果 dependency diff 只负责 APK 中的 DEX、资源、Manifest、assets 和 native lib，runtime-only 会比 compile/runtime union 更准确。union 可能把不进入 APK 的 `compileOnly` 依赖带入部署差分；compile 与 runtime configuration 独立解析出不同 variant 或物理 artifact 时，绝对路径去重也不能保证合并结果对应一个真实 Gradle 依赖图。

### 4.2 当前 dependency diff 同时更新源码编译 classpath

当前 `DependencyDiffResult` 不只服务部署差分。用户确认依赖增量后，Gradle 路径会把完整基线 diff 的新旧库写入临时编译上下文：

```text
JuggCompileHelper
  -> diffResultWithFull.newLibraryDependencies / oldLibraryDependencies
  -> CompileContextManager.updateTempLibraries()
  -> BaseCompileContext.update()
  -> tempModule.libraryDependencies
  -> BaseCompileContext.getModuleDependencies()
  -> Java/Kotlin compiler classpath
```

关键行为：

- `JuggCompileHelper.checkLibraryIncrementalCompile()` 在用户确认后调用 `updateTempLibraries()`；
- `BaseCompileContext.update()` 删除旧版本、复制新库，并更新 `tempModule.libraryDependencies`；
- `BaseCompileContext.getModuleDependencies()` 把临时库加入每次源码编译的 classpath。

IDE Sync 路径不调用 `updateTempLibraries()`；它通过刷新后的 `ModuleInfo.libraryDependencies` 更新源码 classpath。

### 4.3 直接切换 runtime-only 的回归

如果本次只把 union 改成“有 runtime 就只使用 runtime”：

1. `compileOnly` 等只影响源码编译的依赖变化会从 diff 消失；
2. Gradle dependency-diff 路径无法把这些变化写入临时编译 classpath；
3. androidTest synthetic module 的 `runtimeLibraryDependencies` 固定为空，其 `${variant}AndroidTestCompileClasspath` 行为可能被绕过；
4. 非 APK 根模块原有 compile dependency 行为会被重新定义。

这不是局部修复，而是在没有拆分职责的情况下改变 dependency diff 的整体契约。

### 4.4 最终决策

Issue #36 保留：

```text
旧基线：compile
新基线：compile + runtime
```

不改为：

```text
旧基线：compile
新基线：runtime only
```

原因不是 union 更符合 Gradle 的最终打包模型，而是它能在补齐 runtime 漏检的同时保留现有源码 classpath、androidTest 和非 APK 根模块行为。现有失败证据只证明 runtime-only 依赖被漏掉，没有证据证明 compile 依赖参与 diff 已造成用户失败，因此本次按 Bugfix 最小影响面收口。

## 5. 未来准确模型

如果后续要让实现严格对应 Gradle 语义，不能只替换一行 `flatMap`，需要拆分两个行为 owner：

### Compile dependency diff

- 数据来源：`CompileClasspath`；
- 负责更新 Java/Kotlin 源码编译 classpath；
- 保留 `compileOnly`、androidTest synthetic module 和非 APK 根模块行为；
- 不直接决定 APK library DEX、资源或 native lib 部署。

### Packaged/runtime dependency diff

- 数据来源：Application、Dynamic Feature 等 APK 根模块的 `RuntimeClasspath`；
- 负责 APK 中 DEX、资源、Manifest、assets 和 native lib 的增删改与回退；
- runtime 数据可用时只使用 runtime 图，不与 compile 图合并；
- runtime 快照不可用时才回退 compile 行为。

准确回退还需要三态数据契约。当前空列表同时可能表示旧快照未采集、非 APK 根模块不采集、或者已采集但确实为空，不能可靠作为可用性标记。未来需要选择一种明确表达：

```kotlin
val runtimeLibraryDependencies: List<LibraryDependency>?
```

或者增加独立的 snapshot capability，例如：

```kotlin
val hasRuntimeLibrarySnapshot: Boolean
```

该演进会影响 project info 序列化、基线兼容、Gradle/IDE 两条路径以及 diff 结果数据结构，应作为独立任务调查和验证，不并入 Issue #36。

## 6. 场景行为

| 场景 | 本次行为 | 原因 |
|---|---|---|
| 旧完整基线 runtime 为空，当前 runtime 非空 | 两份 diff 都只比较 compile | 避免全部 runtime 库被误报为新增 |
| 旧完整基线 runtime 为空，last snapshot 已有 runtime | 两份 diff 仍只比较 compile | 避免展示与实际编译采用不同模式 |
| runtime-aware 基线发生 runtime-only 升级 | compile + runtime | 识别并部署真实实现库 |
| runtime-aware 基线发生 runtime-only 删除 | compile + runtime | 移除旧 runtime library 产物 |
| compile/runtime 包含同一物理 artifact | 按绝对路径去重 | 同一文件只比较一次 |
| runtime configuration 不可用 | 当前 Gradle 读取失败 | 不伪造完整依赖快照 |
| compileOnly 依赖变化 | 保持原有 compile diff | 继续维护源码编译 classpath |
| androidTest synthetic module | 保持 compile 依赖 | 当前没有对应 runtime snapshot |

## 7. 验证 owner

| 层级 | 测试 | 保护行为 |
|---|---|---|
| L1 | `idea/src/test/java/com/sickworm/intellij/jugg/compile/DependencyDiffResultTest.kt` | 两份 diff 统一模式、旧基线兼容、runtime 增删改、artifact 去重 |
| L2 | `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/ReadProjectInfoGradle7CompatTest.kt` | Maven runtime scope 传递库只进入 RuntimeClasspath snapshot |
| L1 | `FullBuildInfoSerializerTest`、`CompileContextDbFullBuildInfoTest` | v1 FullBuildInfo 与旧 compile context 不失效 |
| L0 | `:idea:compileKotlin` | 主工程编译验证 |

未来拆分准确模型时至少还需要覆盖：

1. `compileOnly` 更新只改变 compiler classpath，不产生部署 library 文件；
2. `runtimeOnly` 更新只改变 packaged/runtime diff，仍能进入 dex 和部署；
3. compile/runtime 解析到不同 artifact 时，两条 diff 各自保持单一 Gradle configuration 的结果；
4. runtime snapshot 为 `null`、空列表和非空列表时分别执行回退、权威空结果和 runtime diff；
5. androidTest synthetic module 继续使用 compile dependency owner。

## 8. 残余限制

1. 旧插件升级后不会立即获得 runtime diff，需等待后续一次成功完整 Gradle 构建。
2. 完整基线确实没有 runtime 外部库时，第一次新增纯 runtime-only 库仍可能延迟到下一次完整构建后被检测。
3. Library 自身 androidTest 的 runtime-only dependency 当前未采集。
4. compile/runtime 对同一坐标解析出不同物理 artifact 时，当前 union 可能保留两个文件。
5. dependency diff 的 compile 与 packaged/runtime 双重职责仍然存在，后续准确模型需要独立拆分。

这些限制是本次“不扩大 Bugfix 范围、不强制所有升级用户完整构建”取舍的一部分。
