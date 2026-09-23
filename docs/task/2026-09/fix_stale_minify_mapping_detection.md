# 残留 mapping 导致 minify 误判的修复方案

创建日期：2026-09-16。状态：待实施。

关联 Issue：[tencentmusic/jugg#47](https://github.com/tencentmusic/jugg/issues/47)。

## 1. 结论

`ICompileContext.isMinified` 当前用 `mappingFile?.exists()` 推断“本次增量编译是否需要混淆”，把“mapping 文件存在”当成了“变体开启了 minify”。这个推断在真实工程里不成立：用户曾为某个变体临时开启 `minifyEnabled`，之后关闭，`build/<module>/outputs/mapping/<variant>/mapping.txt` 会残留在磁盘上；Jugg 不清理该目录。

残留文件会让三个环节同时走错：

1. 增量编译把未混淆产物当作混淆产物，套用陈旧 mapping；
2. `CompileEffectAnalyzer` 以 `isNeedCheckRecompileMinifyRemovedClass=true` 分析，把已经不需要处理的“R8 删除成员”当成真实影响；
3. 部署出的 class/dex 与设备上 APK 的真实命名空间不一致，最终以 `NoSuchFieldError` / `NoClassDefFoundError` 等形式在运行时崩溃。

本次修复把 minify 的判定源从“产物文件是否存在”改为“当前选中变体的真实 `minifyEnabled` 配置”，并让“配置开启但 mapping 缺失”从静默跳过变为明确失败。

已确认取舍（本方案严格遵守）：

1. 只为 `Variant` 增加一个最小 nullable `minifyEnabled` 字段，默认 `null`，旧快照自然读取为 `null`，不做版本升级或数据迁移。
2. `ModuleInfo` 不新增存储字段，按当前选中的 `buildVariant` 派生 `minifyEnabled`。
3. `isMinified` 只以“当前变体 `minifyEnabled == true`”为准，不再读取 mapping 文件是否存在。
4. minify 关闭时，即使旧 mapping 残留，也必须跳过 minify compiler，正常完成增量编译。
5. minify 开启但 mapping 缺失时，由 minify compiler 在执行点打印用户可见 `warn` 并让本轮增量编译失败，不静默跳过、不伪造成功。
6. 不增加“增量编译前统一 fail-closed 检查”。
7. 不增加“禁止残留 mapping 进入后续分析”的独立门禁；除 `isMinified` 改为真实配置自然带来的路由变化外，不改 `ClassObfuscator` / `JuggManager` 等后续分析链。
8. 不删除 mapping 文件，不做 APK/mapping hash 配对、时间戳校验、迁移或清理机制。

## 2. 失败边界与 behavior owner

### 2.1 现状链路

```text
GradleProjectInfoReader (legacy / Android Components 两条变体采集路径)
  -> ModuleInfo.variants          // 只有 name 和 signingConfigName
  -> ModuleInfo.buildVariant      // 由 guessBuildVariant 选出
  -> ICompileContext.isMinified   // = applicationModule.buildPathInfo.mappingFile.exists()
  -> SourceCompiler.compileDexOutputs   // dex 输出目录 un_minify / 是否进入 minify 阶段
  -> DexMinifyCompiler.initIfNeeded     // mapping 存在即加载并混淆
  -> DeployFileManager.getRecompileFiles(isMinified, ...) -> CompileEffectAnalyzer
```

behavior owner 是 `ICompileContext.isMinified`：它决定了 dex 产物语义、影响分析口径和部署内容。变体是否 minify 属于 Gradle 配置事实，`ModuleInfo` 只知道变体名而不知道这个事实，因此判定只能落在 mapping 文件这一不稳定的产物上。

### 2.2 观测到的失败形态

Issue #47 的 Report `ab049b35` 中，`mapping.txt` 与 `usage.txt` 都加载成功，增量编译对 `SingPracticeHomeActivity` 应用了 mapping 并部署到 overlay，运行时报：

```text
java.lang.NoSuchFieldError: No field Companion of type Lcom/***/SingPracticeHomeActivity$Companion;
in class Lcom/***/SingPracticeHomeActivity;
```

维护者在 Issue 中的结论是“很大概率是残留的 mapping.txt 导致了误判”。该结论与本次实现边界一致：判定源不是 APK 是否混淆，而是磁盘上是否存在 mapping 文件。

`_jugg_fix` DEX 在 AGP D8 与 bundled R8 下均生成失败是 Report 中的另一个真实问题，不属于本方案的修复范围。

## 3. 实现方案

### 3.1 数据模型

`Variant` 增加一个字段：

```kotlin
data class Variant(
    val name: String,
    val signingConfigName: String?,
    /** Resolved minify flag; null for snapshots written before this field existed. */
    val minifyEnabled: Boolean? = null,
)
```

`ModuleInfo` 不新增存储字段，只按当前变体派生：

```kotlin
val minifyEnabled: Boolean? get() = variants.firstOrNull { it.name == buildVariant }?.minifyEnabled
```

旧快照的 `variants` 里没有该字段，Gson/Groovy 读取后为 `null`；`null` 表示“未知”，不允许被当作 `true`。

### 3.2 变体采集（两条路径都要拿到真实配置）

| 路径 | 位置 | 读取方式 |
|---|---|---|
| Android Components | `GradleVariantCollector.registerAndroidComponentsVariants` | 变体对象实现 `com.android.build.api.variant.CanMinifyCode`，读取 `isMinifyEnabled`；AGP 不支持时保持 `null` |
| legacy | `GradleProjectInfoReader.updateVariantAndSignConfigs` | 先读 `variant.isMinifyEnabled`（部分 AGP 暴露），否则回退 `variant.buildType.isMinifyEnabled`（`com.android.builder.model.BuildType` 的稳定契约） |

两条路径都沿用现有 `reflector(...)` 兼容读取方式，读取失败保持 `null`，不引入新的抽象或配置。

### 3.3 isMinified

```kotlin
val isMinified get() = applicationModule?.minifyEnabled == true
```

`isReleaseApk`、`mappingFile` 等既有成员保持不变，只把 minify 判定改为真实配置。

### 3.4 minify compiler 的失败契约

`DexMinifyCompiler.initIfNeeded()` 与 `ClassMinifyCompiler.initIfNeeded()` 统一改为三分支：

```kotlin
if (!context.isMinified) {
    logger.debug("Minify is disabled for the current variant, skip obfuscation.")
    return task.wrapToResult()
}
val mappingFile = context.mappingFile
if (mappingFile == null || !mappingFile.exists()) {
    logger.warn("Minify is enabled for the current variant, but mapping file not found: " +
            "${mappingFile?.absolutePath}")
    logger.warn("Compile failed, please run a full Gradle build to regenerate the mapping file.")
    return task.allFailed("mapping file not found for the minified variant")
}
```

- 失败使用既有 `CompileTask.allFailed(message)`，与 `SourceCompiler.compileDexOutputs()` 中 dex 编译失败的处理方式一致，不需要新增异常类型。
- 不再使用 `isReleaseApk` 区分 warn/debug：minify 是否开启与变体命名无关，debuggable + minify 的变体同样必须失败。
- 失败只有在 minify compiler 真正执行时才发生；不在增量编译入口做统一 fail-closed 检查。

## 4. 不做的改动

| 项 | 原因 |
|---|---|
| 删除或清理残留 mapping.txt | 用户明确不做；mapping 仍属于用户构建产物 |
| APK/mapping hash 配对、时间戳校验 | 需要额外持久化状态，超出问题范围 |
| 增量编译前统一 fail-closed | 会让与 minify 无关的编译路径也失败 |
| 在 `ClassObfuscator` / `JuggManager` 过滤残留 mapping | `isMinified` 改为真实配置后，影响分析已按未混淆语义运行；单独过滤属于第二套门禁 |
| 提升 `JuggProjectInfoSerialize.VERSION` | 旧快照可确定性恢复为 `null`，无需让用户重新全量构建 |
| 新增接口/实现类/配置项 | 修复范围只是判定源与失败契约 |

## 5. 验证方案

### 5.1 失败证据

修复前：`DexMinifyCompiler` 在 `minifyEnabled=false` 且残留 mapping 存在时仍会加载 mapping 并把 dex 混淆成 `La/b;`，这是 issue 中运行时崩溃的直接来源，可用 L1 用例稳定复现（见 5.3 用例 1，修复前必然失败）。

### 5.2 测试价值判断

| 候选行为 | 结论 | 原因 |
|---|---|---|
| 变体 minify 配置决定 `isMinified` 与 minify compiler 路由 | 新增 | 用户可见的产物语义，被真实破坏后表现为运行时崩溃 |
| minify 开启但 mapping 缺失时返回失败 | 新增 | 新的稳定性契约，防止静默产出错误产物 |
| 变体采集得到真实 `minifyEnabled` | 复用 + 少量新增 | 采集本身是数据来源：在已有真实 AGP fixture 用例上补充断言，并为缺失的那条路径新增一条定向用例 |
| `Variant.minifyEnabled` 是否为 `null` | 不新增 | 字段默认值本身无独立行为 |
| warn 文案精确字符串 | 不新增 | 非契约文案；只记录“是否产生 warn 级别日志” |

### 5.3 测试落点

| # | 用例 | 层级 | Owner |
|---|---|---|---|
| 1 | `minifyEnabled=false` 且残留 mapping 存在时不混淆，输出保持未混淆语义 | L1 | `main/.../compiler/obfuscation/DexMinifyCompilerVariantMinifyTest` |
| 2 | `minifyEnabled=true` 且 mapping 缺失时 `DexMinifyCompiler` 返回失败并产生 warn | L1 | 同上 |
| 3 | `ClassMinifyCompiler` 在同一条件下返回失败 | L1 | 同上 |
| 4 | 真实 AGP 采集：Android Components 路径（AGP 9.0.0）得到正确 `minifyEnabled` | L2 | `ReadProjectInfoGradle9CompatTest#generatedScript_shouldCollectApplicationAndLibraryVariantsOnAgp90` 补充断言 |
| 5 | 真实 AGP 采集：legacy 路径（AGP 7.2.2）得到正确 `minifyEnabled` | L2 | `ReadProjectInfoGradle7CompatTest#generatedScript_shouldCollectVariantMinifyFlagsFromLegacyVariants` 新增 |
| 6 | minify=true 正常路径不回归 | L3 | `main/.../compiler/SourceMinifyCompileTest`（已有 release 编译链路回归） |

用例 1 使用 `SimpleCompileContext` + 真实 `ModuleInfo`/`Variant`，对 `isMinified` 的默认实现做真实求值，不 mock 被测行为；`DexMinifyCompiler`、`DexObfuscator`、mapping 文件与 dex 产物都是真实对象。因此不需要为测试在生产代码增加任何 seam。

### 5.4 替代验证

- `./gradlew :idea:compileKotlin`：类型与接线。
- `ReadProjectInfoScriptContentTest`：`main/.../gradle/script/**` 与 `project/data/**` 参与生成 `readProjectInfo.gradle.kts`，改动必须覆盖生成脚本语法回归。
- 有 JDK 条件时补 `ReadProjectInfoGradle5CompatTest` / `ReadProjectInfoGradle6CompatTest`；真实功能行为由 Gradle 7/9 compat 用例覆盖。

## 6. 已知边界

`minifyEnabled` 为 `null` 只可能出现在“快照未包含该字段”或“AGP 未暴露该配置”两种情况，按方案它一律判定为未开启 minify。因此升级插件后、如果增量编译直接复用了升级前写下的 `gradle_project_infos.json`，一个开启 minify 的变体会先被判成未开启，直到下一次成功的 Gradle 读取（IDE Sync 或完整 Gradle 构建）刷新快照。

这是“不再用 mapping 文件是否存在作为开启依据”这一已确认取舍的必然结果：如果为 `null` 回退到 mapping 存在性判断，就等于把被修复的误判重新引入未知分支。替代办法是提升快照版本或强制失效旧快照，但方案已确认不做迁移。排查时用 `minifyEnabled=null` 识别该状态（见 `04_engineering_project.md` §7）。

## 7. 文档同步

| 文档 | 需要的改动 |
|---|---|
| `02_compile_obfuscation.md` | §3 数据流表与 §5 隐形约束：`isMinified` 改为真实变体配置；缺 mapping 由 warn 跳过改为用户可见 warn + 明确失败 |
| `04_engineering_project.md` | §3.2 字段表补充派生 `minifyEnabled`；§4.1 变体采集说明补充两条路径的 minify 读取 |
| `09_plugin_runtime_debug.md` | §4 release 增量 crash 首查证据补充“先确认变体 minify 配置与 mapping 来源是否一致” |
| `98_code_map.md` | 混淆映射行状态时间更新（如涉及） |
| `docs/wiki` | 检查是否存在 release/混淆增量编译的用户文档页面，确有影响时同步 |
