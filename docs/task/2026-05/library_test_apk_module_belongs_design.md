# Library Test APK 多归属增量编译部署方案

> 创建时间：2026-05-06  
> 状态：已落地（2026-05-08，见“11. 落地记录”）
> 适用范围：本方案只处理 Jugg 增量编译 / 部署阶段的 APK 归属正确性，不扩大到完整 Gradle androidTest 能力、gutter、RunConfig、test runner UI。  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景

当前 Jugg 的 `ModuleApkBelongs` 已经把原始 `Map<ModuleInfo, ApkFileUnit>` 封装为：

- `getBelongsApk(module)`：保持原有单 APK 语义。
- `getAllBelongsApk(module)`：预留一个模块影响多个 APK 的视图。

这一步只完成了数据结构入口收敛，现有调用点仍按“一个模块只归属一个 APK”执行。新的目标是支持以下事实：

1. 一个普通 library 模块会被打进正常 APK。
2. 当打包 library 的 test APK 时，同一个 library 模块的代码也会被打进这个 test APK。
3. Jugg 的 `tempModule` 也可能同时影响 base APK 和 test APK。

因此第二步不能只修改 `ModuleApkBelongsUtils`。当前链路里 APK 归属会从 `CompileOutput.apkPath` 传到 `DeployItem.apkPath`，部署和 overlay 也继续使用这个单值字段分流。如果不扩展 `CompileOutput -> DeployItem -> JuggDeployData` 数据流，多归属信息会在部署前丢失。

---

## 2. 当前代码证据

### 2.1 编译输出仍是单 APK 字段

入口：`main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`

`CompileOutput` 只有单个 `apkPath: String?`：

```kotlin
data class CompileOutput(
    val type: Type,
    val file: File,
    val baseDir: File,
    val apkPath: String? = null,
    val relativeModule: ModuleInfo? = null,
)
```

影响：

- `CompileOutput.Type.Dex` 当前没有携带 APK 归属。
- `CompileOutput.Type.Res / Asset / NativeLib` 只能携带一个 APK 路径。
- 后续状态管理、部署数据生成、embedded 更新都只能看到这个单值。

### 2.2 `toDeployItem()` 会丢掉 Dex 归属

入口：`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFilePathExt.kt`

当前转换逻辑：

- Dex：固定转成 `DeployItem.FLAG_CLASS`。
- Res / Asset / NativeLib：要求 `apkPath != null`，然后写入 `DeployItem.apkPath`。
- 其他类型：固定 `DeployItem.FLAG_BASE_APK`。

这意味着即使将来给 Dex 的 `CompileOutput` 增加多 APK 归属，只要 `toDeployItem()` 不改，部署层仍然不知道这个 Dex 应该作用于哪些 APK。

### 2.3 `DeployItem` 也是单 APK 字段

入口：`main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

当前 `DeployItem` 结构：

```kotlin
open class DeployItem(
    val name: String,
    val type: CompileOutput.Type,
    val checksum: Long,
    val content: ByteArray,
    val apkPath: String,
)
```

`ClassDeployItem` 只包装 `DeployItem + classNodes`，没有额外 target 信息。部署数据一旦被拆成 `newClasses / hotFixModifiedClasses / hotReloadModifiedClasses / overlays / updateApkFiles`，仍然只能通过 `DeployItem.apkPath` 做单 APK 判断。

### 2.4 `DeployDataPlanner` 从 staging 输出直接转 deploy item

入口：`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployDataPlanner.kt`

当前主流程：

```kotlin
val stagingOutputs = stateTracker.getStagingFiles(isFilterMergedDex = true)
val deployItems = stagingOutputs.map { it.toDeployItem() }
var deployData = deployDataGenerator.buildDeployData(deployItems, ...)
```

所以多 APK 归属必须在 `CompileOutput` 或 `toDeployItem()` 之前已经存在，否则后面没有可靠来源恢复。

### 2.5 部署任务按 applicationId 循环，但传同一份 data

入口：`idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`

当前执行：

```kotlin
val packages = data.apks.sortedForInstall().groupBy { it.applicationId }
for ((applicationId, apkInfos) in packages) {
    val apkFiles = apkInfos.flatMap { it.files }.map { it.apkFile }
    perform(device, deployer, applicationId, apkFiles)
}
```

`perform()` 的 `files` 是当前 applicationId 的 APK 文件，但 `fullSwap()` / `codeSwap()` 仍传入原始 `data`。如果 `data` 里同时包含 base APK 和 test APK 的 class / overlay，当前没有 package 级过滤，容易把错误归属的变更带进另一个 package 的 deploy。

### 2.6 overlay/updateApk 仍按单 `apkPath` 查找目标 APK

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ResourceApkGenerator.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabase.kt`

关键行为：

- `OverlayUpdateBuilder.build()` 用 `DeployItem.apkPath` 找 `cacheEntry.apks`，找不到落到 base APK。
- `IncrementalDeployHelper.updateApk()` 遍历所有 APK，用 `it.apkPath == apkFile.path` 决定写入哪个 APK。
- `ResourceApkGenerator.getResourceApkDeployItem()` 按 `changedOverlays.groupBy { it.apkPath }` 生成 resource APK。
- `DeployDataDatabase.addFullRes()` 用 `changedOverlays.filter { it.apkPath == apkFile.path }` 排除已变更文件。

这些都是 test APK 多归属必须覆盖的 downstream。

---

## 3. 目标与非目标

### 3.1 目标

本次改造目标：

1. 保留现有单 APK 语义，让未接入多归属的逻辑继续通过 `getBelongsApk()` / `apkPath` 工作。
2. 为 `CompileOutput -> DeployItem -> JuggDeployData` 增加“所有目标 APK”信息，避免多归属在部署前丢失。
3. 让 library 模块变更能同时影响正常 APK 与 library test APK。
4. 让 `tempModule` 生成的 R / styleable / DataBinding 相关输出能同时影响 base APK 与 test APK。
5. 部署时按 APK / package 正确分流，避免把 base APK 变更推给 test APK，或反过来。

### 3.2 非目标

本方案不解决：

- library androidTest gutter / RunConfig / Gradle full compile 命令派生。
- test APK 首次产物发现与 `ApkInfo` 读取规则扩展。
- 常驻 instrumentation 进程内的 redefine。
- 完整 Gradle pipeline 的 library test APK 产物正确性。

---

## 4. 总体设计

### 4.1 核心原则

采用“双视图”模型：

- primary 视图：继续服务必须选择一个 APK 的旧逻辑，字段名保持弱化，不再叫 primary。
- all targets 视图：服务部署分流和多 APK 写入。

对应命名：

- `CompileOutput.apkPath`：继续表示旧语义下的 `getBelongsApk()` 结果。
- `CompileOutput.targetApkPaths`：新增，表示该输出需要影响的所有 APK。
- `DeployItem.apkPath`：继续表示旧语义下的单 APK 兼容字段。
- `DeployItem.targetApkPaths`：新增，表示 deploy item 的所有目标 APK。

`apkPath` 不删除，避免一次性改穿大量代码；新逻辑必须优先看 `targetApkPaths`。

### 4.2 数据流

目标数据流：

```text
ModuleApkBelongs
  -> CompileOutput(apkPath, targetApkPaths)
  -> DeployItem(apkPath, targetApkPaths)
  -> DeployDataGenerator
  -> JuggDeployData
  -> JuggDeployTask.filterForApplication(...)
  -> OverlayUpdateBuilder / IncrementalDeployHelper
```

关键点：

- `CompileOutput` 是多归属信息的第一持久化载体。
- `DeployItem` 是部署阶段的多归属载体。
- `JuggDeployTask` 在每个 `applicationId` 部署前必须得到 package-scoped data。
- `OverlayUpdateBuilder` 和 `IncrementalDeployHelper` 不能再只看单 `apkPath`。

---

## 5. 详细方案

### 5.1 扩展 `CompileOutput`

文件：`main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`

建议新增字段：

```kotlin
data class CompileOutput(
    val type: Type,
    val file: File,
    val baseDir: File,
    val apkPath: String? = null,
    val relativeModule: ModuleInfo? = null,
    var targetApkPaths: List<String> = emptyList(),
) {
    init {
        targetApkPaths = normalizeTargetApkPaths(apkPath, targetApkPaths)
    }
}
```

说明：

- 字段追加到构造函数尾部，减少现有命名参数和位置参数调用的破坏面。
- `apkPath` 继续表示旧单值语义。
- `targetApkPaths` 现在在构造期自动补齐 `apkPath`，旧的 `allTargetApkPaths` 已删除。

### 5.2 扩展 `DeployItem`

文件：`main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

建议新增字段和判断方法：

```kotlin
open class DeployItem(
    val name: String,
    val type: CompileOutput.Type,
    val checksum: Long,
    val content: ByteArray,
    val apkPath: String,
    var targetApkPaths: List<String> = emptyList(),
) {
    init {
        targetApkPaths = normalizeTargetApkPaths(apkPath, targetApkPaths)
    }

    fun belongsTo(apkPath: String): Boolean {
        return when {
            this.apkPath == FLAG_BASE_APK -> true
            targetApkPaths.isNotEmpty() -> apkPath in targetApkPaths
            this.apkPath == FLAG_CLASS -> true
            else -> this.apkPath == apkPath
        }
    }

    fun belongsToAny(apkPaths: Collection<String>): Boolean {
        return apkPaths.any { belongsTo(it) }
    }
}
```

说明：

- 对旧 Dex 的 `FLAG_CLASS` 保持“作用于 base”的兼容语义。
- 对新 Dex，`apkPath` 仍可为 `FLAG_CLASS`，但 `targetApkPaths` 表示真实目标。
- 对资源类输出，`apkPath` 是旧单值，`targetApkPaths` 可以是单个或多个目标。
- 现在 `targetApkPaths` 统一在构造期归一化，不再保留 `allTargetApkPaths`。

### 5.3 修改 `CompileOutput.toDeployItem()`

文件：`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFilePathExt.kt`

目标行为：

- Dex：保留 `apkPath = FLAG_CLASS`，但把 `CompileOutput.targetApkPaths` 写到 `DeployItem.targetApkPaths`。
- Res / Asset / NativeLib：保留 `apkPath` 校验，同时把 `targetApkPaths` 写入。
- 其他类型：继续不可部署。

示意：

```kotlin
when (type) {
    CompileOutput.Type.Dex -> DeployItem(
        deployName,
        type,
        crc,
        bytes,
        DeployItem.FLAG_CLASS,
        targetApkPaths = targetApkPaths,
    )
    CompileOutput.Type.Res, CompileOutput.Type.Asset, CompileOutput.Type.NativeLib -> {
        if (apkPath == null) throw JuggInternalException.outputDidNotSpecificApkPath(toString())
        DeployItem(deployName, type, crc, bytes, apkPath, targetApkPaths = targetApkPaths)
    }
    else -> DeployItem(deployName, type, crc, bytes, DeployItem.FLAG_BASE_APK)
}
```

### 5.4 编译侧填充 target APK

#### 5.4.1 source / dex 输出

文件：

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/DexCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt`

建议规则：

- 源码编译仍按 module 编译一次。
- Dex 输出使用 `context.moduleBelongsApkMap.getAllBelongsApk(module)` 填充 `targetApkPaths`。
- `apkPath` 使用 `getBelongsApk(module)?.apkFile?.path`，保持旧逻辑。
- Kotlin / Java 编译出来的 class 不直接部署，可以先不携带 APK；最终 Dex 阶段携带即可。
- `JavaCompilerInvoker` 中 DataBinding / generated res 类输出目前会用 `getBelongsApk(module)` 写 `apkPath`，这类输出也需要同步写 `targetApkPaths`，否则后续从 class output 搬到 overlay output 时会丢失。

Dex 示例：

```kotlin
private fun ModuleInfo.targetApkPaths(): List<String> {
    return context.moduleBelongsApkMap.getAllBelongsApk(this).map { it.apkFile.path }
}
```

输出：

```kotlin
CompileOutput(
    type = CompileOutput.Type.Dex,
    file = outputFile,
    baseDir = task.outputDir,
    apkPath = context.moduleBelongsApkMap.getBelongsApk(module)?.apkFile?.path,
    targetApkPaths = module.targetApkPaths(),
)
```

#### 5.4.2 overlay / resources / manifest 输出

文件：

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt`

这类编译器通过 `BaseCompiler.splitApkAndCompile()` 进入 `doApkCompile(task, apkFileUnit)`，当前只使用 `getBelongsApk()`。

建议分两类处理：

1. 可以按 APK 独立生成的输出：修改 `splitApkAndCompile()`，用 `getAllBelongsApk()` 将同一个 module 的输入加入多个 APK 组。输出仍是单 APK target，即 `targetApkPaths = listOf(apkFileUnit.apkFile.path)`。
2. 只应生成一次的共享输出：保持单次编译，但在输出上写多个 `targetApkPaths`。

资源和 manifest 建议走第 1 类。原因：

- `resources.arsc` / `AndroidManifest.xml` 与具体 APK 的资源表、package、manifest merge 结果绑定。
- test APK 和 base APK 的资源表不一定相同，用同一个物理输出直接多投风险较高。
- 按 APK 分组可以复用已有 `apkFileUnit` 维度的实现。

#### 5.4.3 `tempModule`

文件：`main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`

当前 `tempModule` 已通过 `getAllBelongsApk()` 暴露 base APK + test APK。下一步需要确保消费者真的使用这个 all 视图：

- Dex / R.dex / styleable 相关输出：应携带 base + test 的 `targetApkPaths`。
- per-APK resource output：应被 `splitApkAndCompile()` 分发到 base 和 test 两组。
- 仍需要单 APK 的 styleable / desugar / minify 逻辑继续使用 `getBelongsApk()`。

---

## 6. 部署侧分流方案

### 6.1 `JuggDeployData` 增加按 APK 过滤能力

文件：`main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt`

建议新增：

```kotlin
fun filterForApks(apkInfos: List<ApkInfo>): JuggDeployData {
    val apkPaths = apkInfos.flatMap { it.files }.map { it.apkFile.path }.toSet()
    val filteredNewClasses = newClasses.filter { it.deployItem.belongsToAny(apkPaths) }
    val filteredHotFixClasses = hotFixModifiedClasses.filter { it.deployItem.belongsToAny(apkPaths) }
    val filteredHotReloadClasses = hotReloadModifiedClasses.filter { it.deployItem.belongsToAny(apkPaths) }
    val filteredOverlays = overlays.filter { it.belongsToAny(apkPaths) }
    val filteredUpdateApkFiles = updateApkFiles.filter { it.belongsToAny(apkPaths) }
    return copy(
        apks = apkInfos,
        newClasses = filteredNewClasses,
        hotFixModifiedClasses = filteredHotFixClasses,
        hotReloadModifiedClasses = filteredHotReloadClasses,
        overlays = filteredOverlays,
        updateApkFiles = filteredUpdateApkFiles,
    )
}
```

说明：

- `parsedDex` 可以先保留原值，影响分析已经完成；deploy 实际应用时用 filtered class lists。
- 如果后续发现 `parsedDex` commit 到 DB 造成跨 package 污染，再补 scoped parsedDex。第一阶段先控制部署正确性。

### 6.2 `JuggDeployTask` 每个 package 使用 scoped data

文件：`idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`

当前 `perform()` 使用原始 `data`。建议改为：

```kotlin
for ((applicationId, apkInfos) in packages) {
    val scopedData = data.filterForApks(apkInfos)
    val apkFiles = apkInfos.flatMap { it.files }.map { it.apkFile }
    perform(device, deployer, applicationId, apkFiles, scopedData)
}
```

并让 `perform()` 调用：

- `deployer.fullSwap(getPathsToInstall(files), scopedData)`
- `deployer.codeSwap(getPathsToInstall(files), debuggerRedefiners, scopedData)`

这样 base package 和 test package 不再共享同一份未过滤的 class / overlay。

### 6.3 `OverlayUpdateBuilder` 使用 target APK

文件：`idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/OverlayUpdateBuilder.kt`

当前 `data.overlays.associate { ... }` 只能把一个 deploy item 映射到一个 APK。建议逻辑：

1. 根据当前 package scoped data 里的 `data.apks` 得到允许的 APK 路径。
2. 对每个 overlay，计算它在当前 cache entry 中命中的目标 APK。
3. 如果 `targetApkPaths` 有多个，展开成多个 overlay entry；如果只有一个，行为与旧逻辑一致。

注意：

- `associate` 对同名 `ApkEntry` 可能覆盖。展开时需要确认 key 的等价逻辑；如果 `ApkEntry` 的 equality 包含 apk，则可以直接展开，否则需要保守验证。
- 对 `FLAG_BASE_APK` / 旧 `FLAG_CLASS` 保持 base APK fallback。

### 6.4 `IncrementalDeployHelper.updateApk()` 使用 `belongsTo()`

文件：`main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt`

当前逻辑：

```kotlin
val isBaseOutput = it.apkPath == DeployItem.FLAG_CLASS || it.apkPath == DeployItem.FLAG_BASE_APK
if ((isBaseApk && isBaseOutput) || (it.apkPath == apkFile.path)) {
    deployItems.add(it)
}
```

建议替换为：

```kotlin
if (it.belongsTo(apkFile.path) || (isBaseApk && it.apkPath == DeployItem.FLAG_BASE_APK)) {
    deployItems.add(it)
}
```

Dex 如果要同时嵌入 base 和 test APK，必须依赖 `targetApkPaths`，不能再把 `FLAG_CLASS` 固定解释为 base-only。

### 6.5 compat resource APK 生成按目标路径分组

文件：`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ResourceApkGenerator.kt`

当前按 `apkPath` 分组。建议引入 helper：

```kotlin
private fun DeployItem.expandByTargetApkPath(): List<Pair<String, DeployItem>>
```

如果 `targetApkPaths` 有值，就按 target 展开；否则按旧 `apkPath`。

这样 compat deploy 生成 resource APK 时不会把属于 test APK 的 overlay 打进 base 的 resource APK。

### 6.6 full resource 补齐逻辑按 target 排除

文件：`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabase.kt`

当前：

```kotlin
val nameSet = changedOverlays
    .filter { it.apkPath == apkFile.path }
    .map { it.name }
    .toSet()
```

建议改为：

```kotlin
val nameSet = changedOverlays
    .filter { it.belongsTo(apkFile.path) }
    .map { it.name }
    .toSet()
```

否则首次 full overlay 时，多归属 overlay 可能在某个 APK 维度被重复补齐。

---

## 7. recompile / desugar / minify / styleable 的处理

这些逻辑仍需要一个“主 APK”视图，短期不应该全部改成多 target：

- `BaseCompileContext.getDesugarInfo(...)` 当前通过 `getBelongsApk(module)` 选择 `apkFile`，用于查询 desugar 相关历史信息。
- `BaseCompileContext.findDesugaredLibraryConfigurationWithCache(...)` 仍要选择一个 APK 来读取配置。
- `CompileEffectAnalyzer.getMinifyInfo(...)` 基于 Dex 影响分析，不需要先复制到每个 APK；部署分流阶段再按 target 过滤。
- `StyleableFileGenerator` 按 APK 反查 module，用 primary 视图可以保持旧行为，避免多个 APK 生成冲突的 `styleable` 文件。

为什么必须保留单 APK 选择：

1. desugar / minify / styleable 不是部署目标选择问题，而是编译分析上下文问题。
2. 这些上下文依赖历史 DB、APK resource table、mapping 或 variant 配置；同一模块同时属于多个 APK 时，Jugg 仍需要一个默认上下文来做旧逻辑兼容。
3. 直接把这些分析复制到所有 APK，可能引入重复 recompile、重复 styleable、或不同 APK 资源表不一致导致的非预期输出。

因此分层原则是：

- 编译分析上下文：继续使用 `getBelongsApk()`。
- 部署目标传播：使用 `getAllBelongsApk()` / `targetApkPaths`。

---

## 8. 分阶段落地计划

### Task 1：扩展数据模型但保持旧行为

改动：

- `CompileOutput` 增加 `targetApkPaths`。
- `DeployItem` 增加 `targetApkPaths`、`belongsTo()`、`belongsToAny()`。
- `toDeployItem()` 传递 target 信息。

测试：

- 新增 `DeployFilePathExtTest`：
  - Dex `CompileOutput(targetApkPaths = listOf(base, test))` 转成 `DeployItem.FLAG_CLASS`，但 target 保留 base + test。
  - Res `CompileOutput(apkPath = base, targetApkPaths = listOf(base, test))` 转成 `DeployItem` 后 target 保留 base + test。
  - 旧 Res 只有 `apkPath` 时 `belongsTo(base) == true`。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.DeployFilePathExtTest"
```

### Task 2：部署前按 package 过滤

改动：

- `JuggDeployData.filterForApks(apkInfos)`。
- `JuggDeployTask` 对每个 applicationId 使用 scoped data。

测试：

- 新增或扩展 `JuggDeployDataTest`：
  - base APK 只保留 target base 的 class / overlay。
  - test APK 只保留 target test 的 class / overlay。
  - target base + test 的 class 在两边都保留。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployDataTest"
```

### Task 3：编译输出写入多 target

改动：

- `DexCompiler` 输出 Dex 时写入 module 的 all target APK。
- `JavaCompilerInvoker` 里生成的 deployable res output 写入 all target APK。
- `JuggCompiler` 搬运 `CompileOutput` 时保留 `targetApkPaths`。

测试：

- 扩展 `ModuleApkBelongsUtilsAndroidTestTest`，确认 library module / tempModule 的 `getAllBelongsApk()` 包含 base + test。
- 新增 Dex 编译相关单元测试或轻量 fake compiler test，确认 module 多归属时 Dex `CompileOutput.targetApkPaths` 不为空且包含两个 APK。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"
```

### Task 4：per-APK overlay 编译分发

改动：

- `BaseCompiler.splitApkAndCompile()` 改用 `getAllBelongsApk()` 建组。
- `ArscCompiler` / `AssetOverlayCompiler` / `AndroidManifestCompiler` 输出保持单 target。

测试：

- 新增 `BaseCompilerMultiApkSplitTest`：
  - module all belongs = base + test 时，`doApkCompile()` 被调用两次。
  - 每次 task 文件相同，apkFileUnit 分别为 base/test。
  - 旧单归属 module 仍只调用一次。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerMultiApkSplitTest"
```

### Task 5：部署写 APK / overlay update 使用多 target

改动：

- `IncrementalDeployHelper.updateApk()` 使用 `DeployItem.belongsTo(apkFile.path)`。
- `OverlayUpdateBuilder` 对多 target overlay 做展开。
- `ResourceApkGenerator` 按 target 分组。
- `DeployDataDatabase.addFullRes()` 用 `belongsTo()` 排除 changed overlay。

测试：

- `IncrementalDeployHelperTest`：target base + test 的 Dex 会写入两个 APK。
- `OverlayUpdateBuilderTest`：target test 的 overlay 映射到 test APK cache entry。
- `ResourceApkGeneratorTest`：base/test overlay 分别生成对应 resource APK。
- `DeployDataDatabaseTest`：full res 补齐按目标 APK 排除 changed overlay。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.IncrementalDeployHelperTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.OverlayUpdateBuilderTest"
```

### Task 6：最小集成验证

测试场景：

1. base APK + library test APK 同时存在。
2. 修改 library 普通源码。
3. Jugg 增量编译只编译一次 source/dex。
4. 生成的 Dex deploy item target 包含 base APK + test APK。
5. `JuggDeployTask` 对 base package 和 test package 分别拿到 scoped data。
6. overlay/updateApk 不跨 APK 错投。

建议补充一条 manager-level fake test，复用现有 mock harness，不要求真实设备。

验证命令：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.*MultiApk*"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.*MultiApk*"
./gradlew :idea:compileKotlin
```

---

## 9. 风险与待确认点

### 9.1 `parsedDex` / DB 是否需要 package scoped

第一版方案只过滤 `newClasses / hotFixModifiedClasses / hotReloadModifiedClasses / overlays / updateApkFiles`，`parsedDex` 暂不拆分。这样部署实际应用是 scoped 的，但部署成功后 DB commit 仍可能记录跨 package class 状态。

如果 library class 在 base APK 和 test APK 中同时存在但历史结构不同，`DeployDataGenerator.getClassNodes()` 当前按 class name 查询，可能出现跨 APK 历史污染。这不是 `apkPath` 扩列表能自动解决的问题。

建议第一阶段先做部署归属正确；如果测试暴露 DB 污染，再追加 package-scoped class DB 方案。

### 9.2 Dex merge 后 target 信息传递

`DeployDataPlanner.convertToMergedDexDeployData()` 会调用 `IncrementalCompilerHelper.mergeDex()` 产生新的 merged Dex `CompileOutput`。需要确认 merge 后如何合并 target：

- 如果输入 Dex target 全部相同，merged Dex 继承该 target。
- 如果输入 Dex target 不同，merged Dex target 应是并集。

否则 dex merge 会再次丢失 APK 归属。

### 9.3 Export incremental APK

`IncrementalDeployHelper.exportIncrementalApk()` 当前会把 `DeployItem.apkPath` 映射到临时 APK 路径。多 target 后也要同步映射 `targetApkPaths`，否则导出增量 APK 时仍只更新单 APK。

### 9.4 特殊 flag 的语义

`DeployItem.FLAG_CLASS` 当前既表示“这是 class deploy item”，又隐含 base APK fallback。多 target 后建议语义拆开：

- `apkPath == FLAG_CLASS` 只表示 deploy item 类型兼容字段。
- 真实 APK 目标优先看 `targetApkPaths`。
- `targetApkPaths` 为空时才回退旧 base 行为。

---

## 10. 推荐实施顺序

推荐按以下顺序实现，每步独立提交：

1. 数据模型和转换链路：`CompileOutput` / `DeployItem` / `toDeployItem()`。
2. 部署前 package scoped filtering：`JuggDeployData.filterForApks()` / `JuggDeployTask`。
3. Dex/source 输出携带 target。
4. per-APK overlay 编译使用 all belongs。
5. overlay/updateApk/resourceApk/fullRes 下游改用 target。
6. dex merge/export incremental APK 的 target 继承。

这个顺序可以保证每一步都能通过定向单测验证，并且前两步不依赖真实 library test APK 产物即可先验证数据不丢失。

---

## 11. 落地记录

2026-05-08 已按本方案完成核心链路：

- `CompileOutput` / `DeployItem` 增加 `targetApkPaths`，并通过 `toDeployItem()` 保留多 APK 归属。
- `ModuleApkBelongsUtils` 对 self-targeting library Test APK 建立 base + test APK all-view。
- `DexCompiler`、`JavaCompilerInvoker`、`JuggCompiler`、`BaseCompiler.splitApkAndCompile()` 已传递或分发 all targets。
- `JuggDeployData.filterForApks()` 与 `JuggDeployTask.groupByApplicationId()` 已按 applicationId 使用 scoped deploy data。
- `OverlayUpdateBuilder`、`IncrementalDeployHelper`、`ResourceApkGenerator`、`DeployDataDatabase.addFullRes()` 已改为优先使用 `targetApkPaths`。
- Dex merge 和 export incremental APK 已保留 target 并集。
- 新增 `LibraryTestApkBackfillPlanner` / `LibraryTestApkBackfillHelper`，当 `sourcePath` 命中 self-targeting androidTest module 且 APK 缺失时，只执行当前 module 的 `assemble<Variant>AndroidTest` 并把新增 Test APK 合入本轮 APK 列表。

仍不包含：

- app-style other-targeting test APK 懒加载补齐。
- package-scoped parsedDex / class DB 拆分；当前阶段只保证部署数据归属正确。
