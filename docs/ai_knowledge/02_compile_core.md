# Jugg 编译系统 - 核心架构

---

## 一、编译系统概览

### 1.1 核心职责

Jugg 编译系统负责将修改的源码/资源文件快速编译为可部署的产物，核心特点：

| 特性 | 说明 |
|------|------|
| **增量编译** | 仅编译变更文件及其影响范围 |
| **模块化编译** | 按模块依赖顺序编译，支持多模块项目 |
| **多类型支持** | Java/Kotlin/Resource/Asset/Manifest/Dex 等 11 种文件类型 |
| **自定义扩展** | 支持自定义编译器插件，可在编译流程前后插入 |
| **智能降级** | 编译失败/文件过多时自动降级到 Gradle |
| **依赖修复** | 自动检测并修复依赖缺失问题 |

### 1.2 编译流程总览

```
用户修改文件
    ↓
文件变更检测 (IFileChangesHandler)
    ↓
编译任务创建 (CompileTask)
    ↓
编译器选择 (ICompiler)
    ↓
模块依赖排序 (ModuleCompileOrderUtils)
    ↓
分模块编译 (BaseCompiler.splitModuleAndCompile)
    ↓
自定义编译器执行 (executeBeforeCustomCompilers / executeAfterCustomCompilers)
    ↓
编译结果收集 (CompileResult)
    ↓
影响分析 (IncrementalCompilerHelper)
    ↓
Dex 合并 (DexFileMerger)
    ↓
部署产物生成 (DeployItem)
```

---

## 二、核心数据结构

### 2.1 CompileTask - 编译任务

**定义位置**: `ICompiler.kt`

```kotlin
class CompileTask(
    val files: List<CompileFile>,           // 待编译文件列表
    val outputDir: File,                    // 编译输出目录
    private val parentTask: CompileTask?,   // 父任务（用于子任务合并）
    private val compileStatusHolder: CompileStatusHolder, // 编译状态管理
)
```

**关键方法**:

| 方法 | 功能 |
|------|------|
| `operator fun plus(task)` | 合并两个编译任务（需验证输出目录和状态管理器一致性） |
| `allFailed(error)` | 标记所有文件编译失败 |
| `notifyCompiled(files)` | 通知编译进度 |
| `isShouldCancel` | 检查是否应取消编译 |

### 2.2 CompileFile - 待编译文件

**定义位置**: `ICompiler.kt`

```kotlin
data class CompileFile(
    val type: Type,                         // 文件类型
    val file: File,                         // 文件路径
    val baseDir: File,                      // 基准目录
    val module: ModuleInfo,                 // 所属模块
    val extraInfo: Map<String, Any>,        // 扩展信息（如依赖名称）
    val dependencyPaths: List<String>,      // 额外依赖路径
)
```

**支持的文件类型** (11种):

| Type | 说明 | 示例 |
|------|------|------|
| `Java` | Java 源码 | MainActivity.java |
| `Kotlin` | Kotlin 源码 | MainActivity.kt |
| `Class` | 编译后的 Class 文件 | MainActivity.class |
| `Asset` | Assets 资源 | data.json |
| `NativeLib` | Native 库 | libxxx.so |
| `Resource` | Android 资源 | activity_main.xml |
| `Flat` | AAPT2 编译后的资源 | layout_activity_main.xml.flat |
| `BuildFile` | Gradle 构建文件 | build.gradle |
| `AndroidManifest` | Manifest 文件 | AndroidManifest.xml |
| `DexToChangePackageName` | 需要修改包名的 Dex | classes.dex |
| `Dex` | Dex 文件 | classes.dex |

**扩展属性** (通过 `CompilerExt.kt`):

| 扩展属性 | 说明 | 用途 |
|---------|------|------|
| `isDependency` | 是否为依赖库文件 | 区分项目源码和第三方库 |
| `dependencyName` | 依赖名称 | 如 "org.reactivestreams:reactive-streams:1.0.3" |
| `jarDexFileName` | Jar 对应的 Dex 文件名 | 如 "#org.reactivestreams#reactive-streams.dex" |
| `oldManifest` | 旧版本 Manifest | 用于依赖变更检测 |
| `oldJar` | 旧版本 Jar | 用于依赖变更检测 |
| `oldRes` | 旧版本资源 | 用于依赖变更检测 |

### 2.3 CompileOutput - 编译产物

**定义位置**: `ICompiler.kt`

```kotlin
data class CompileOutput(
    val type: Type,                         // 产物类型
    val file: File,                         // 产物文件
    val baseDir: File,                      // 基准目录
    val apkPath: String?,                   // APK 路径（可选）
    val relativeModule: ModuleInfo?,        // 关联模块（可选）
)
```

**产物类型** (10种):

| Type | 说明 | 部署方式 |
|------|------|---------|
| `Class` | Class 文件 | 转 Dex 后部署 |
| `Flat` | AAPT2 Flat 文件 | 链接后部署 |
| `Dex` | Dex 文件 | 直接部署到 `assets/jugg_/` |
| `Res` | 资源文件 | 覆盖 APK 中的 `res/**` |
| `Asset` | Assets 文件 | 覆盖 APK 中的 `assets/**` |
| `NativeLib` | Native 库 | 覆盖 APK 中的 `lib/**` |
| `Java` | 生成的 Java 源码 | 如 R.java，需再次编译 |
| `Kotlin` | 生成的 Kotlin 源码 | 如 KSP 生成代码 |
| `ResXml` | DataBinding 生成的 XML | 中间产物 |
| `OtherNotDeployed` | 不部署的中间文件 | 仅用于编译流程 |

### 2.4 CompileResult - 编译结果

**定义位置**: `ICompiler.kt`

```kotlin
data class CompileResult(
    val task: CompileTask,                  // 原始任务
    val details: List<Result<CompileFile, CompileError>>, // 每个文件的编译结果
    val outputs: List<CompileOutput>,       // 编译产物列表
)
```

**关键属性**:

| 属性 | 说明 |
|------|------|
| `successFiles` | 编译成功的文件列表 |
| `failedFiles` | 编译失败的文件列表 |
| `isAllSuccess` | 是否全部成功 |
| `compiledFailedFiles` | 真正编译失败的文件（排除快速失败） |
| `notCompiledFiles` | 未编译的文件（快速失败） |

**关键方法**:

| 方法 | 功能 |
|------|------|
| `operator fun plus(result)` | 合并两个编译结果 |
| `quickFailedOthers(parentTask)` | 快速失败其他文件（用于依赖编译失败时） |

### 2.5 Result<Success, Failure> - 通用结果封装

**定义位置**: `Result.kt`

```kotlin
class Result<Success, Failure>(
    val isSuccess: Boolean,
    success: Success?,
    failure: Failure?,
)
```

**设计亮点**: 类型安全的结果封装，避免异常抛出，支持链式处理。

---

## 三、编译器接口与基类

### 3.1 ICompiler - 编译器接口

**定义位置**: `ICompiler.kt`

```kotlin
interface ICompiler: Disposable {
    val supportedTypes: List<CompileFile.Type>  // 支持的文件类型
    fun compile(task: CompileTask): CompileResult // 编译方法
    fun warmUp() = Unit                          // 预热（可选）
    val order: Int get() = NO_ORDER              // 执行顺序
    fun consumeFiles(files: List<CompileFile>): List<CompileFile> // 消费文件
}
```

**关键设计**:

1. **类型过滤**: 通过 `supportedTypes` 声明支持的文件类型
2. **执行顺序**: 通过 `order` 控制自定义编译器的执行时机
3. **文件消费**: 通过 `consumeFiles` 拦截文件，避免被其他编译器处理

### 3.2 BaseCompiler - 编译器基类

**定义位置**: `BaseCompiler.kt`

**核心流程**:

```kotlin
override fun compile(task: CompileTask): CompileResult {
    // 1. 检查取消状态
    if (task.isShouldCancel) return task.toCancelResult()
    
    // 2. 检查文件类型和上下文
    checkTypesCanCompile(task)
    checkContextCanCompile(task)
    
    // 3. 执行前置自定义编译器
    val (filteredTask, beforeResult) = executeBeforeCustomCompilers(beforeCompileOrderRange, task)
    
    // 4. 执行核心编译
    result += doCompile(filteredTask)
    
    // 5. 执行后置自定义编译器
    result += executeAfterCustomCompilers(afterCompileOrderRange, filteredTask, result)
    
    // 6. 通知编译进度
    task.notifyCompiled(task.files)
    
    return result
}
```

**关键方法**:

| 方法 | 功能 | 默认实现 |
|------|------|---------|
| `doCompile(task)` | 核心编译逻辑 | 调用 `splitModuleAndCompile` |
| `doModuleCompile(task, module)` | 单模块编译 | 抽象方法，子类实现 |
| `doApkCompile(task, apkFileUnit)` | 单 APK 编译 | 抛出未实现异常 |
| `splitModuleAndCompile(task)` | 按模块拆分编译 | 已实现 |
| `splitApkAndCompile(task)` | 按 APK 拆分编译 | 已实现 |

**自定义编译器执行机制**:

```kotlin
// 前置编译器：在核心编译前执行
val beforeCompileOrderRange: IntRange = CompileOrder.noOrder

// 后置编译器：在核心编译后执行
val afterCompileOrderRange: IntRange = CompileOrder.noOrder
```

**示例**: 资源编译器可设置 `beforeCompileOrderRange = CompileOrder.beforeRes`，在资源编译前执行自定义逻辑。

### 3.3 模块编译顺序算法

**实现位置**: `ModuleCompileOrderUtils.kt`

**算法**: 拓扑排序 (Topological Sort)

```kotlin
fun getModuleCompileOrders(modules: Set<ModuleInfo>): List<ModuleInfo> {
    // 1. 构建依赖图
    val dependencyMap: MutableMap<String, MutableSet<String>> = mutableMapOf()
    
    // 2. 初始化无依赖模块队列
    val queue = ArrayDeque<ModuleInfo>()
    
    // 3. 拓扑排序
    while (queue.isNotEmpty()) {
        val moduleInfo = queue.removeFirst()
        compileOrder.add(moduleInfo)
        // 移除已编译模块的依赖关系
        dependencyMap.forEach { (moduleName, dependencies) ->
            dependencies.remove(moduleInfo.name)
            if (dependencies.isEmpty()) {
                queue.add(moduleMap[moduleName]!!)
            }
        }
    }
    
    // 4. 处理循环依赖（按依赖数量排序）
    if (dependencyMap.isNotEmpty()) {
        val remainModules = dependencyMap.entries
            .sortedBy { it.value.size }
            .map { moduleMap[it.key]!! }
        compileOrder.addAll(remainModules)
    }
    
    return compileOrder
}
```

**容错设计**: 检测到循环依赖时，按依赖数量排序后添加到编译队列末尾，确保不丢失模块。

---

## 四、编译上下文 (ICompileContext)

### 4.1 核心属性

**定义位置**: `ICompiler.kt`

| 属性 | 类型 | 说明 |
|------|------|------|
| `logger` | Logger | 日志打印器 |
| `tempCompileDir` | File | 临时编译目录 |
| `tempModuleDir` | File | 临时模块目录（用于生成不属于任何模块的文件） |
| `androidHome` | File | Android SDK 目录 |
| `androidJar` | File | android.jar 路径 |
| `modules` | Map<String, ModuleInfo> | 项目所有模块 |
| `apkInfos` | List<ApkInfo> | 已部署的 APK 信息 |
| `projectDir` | File | 项目根目录 |
| `deployedFiles` | List<CompileOutput> | 已部署的文件 |
| `signingConfig` | SigningConfig? | APK 签名配置 |
| `incrementalDataDir` | File | 增量数据存储目录 |
| `customCompilers` | List<ICompiler> | 自定义编译器列表 |
| `scene` | Scene | 编译场景（IDE / INCREMENTAL_APK） |

### 4.2 派生属性

| 属性 | 计算逻辑 | 用途 |
|------|---------|------|
| `packageName` | 最短的 applicationId | 过滤动态特性模块包名 |
| `isSingleApk` | APK 文件数量 == 1 | 判断是否为单 APK 项目 |
| `isReleaseApk` | buildVariant 包含 "release" | 判断是否为 Release 构建 |
| `mappingFile` | applicationModule.mappingFile | R8/ProGuard 混淆映射文件 |
| `isMinified` | mappingFile 存在 | 判断是否开启混淆 |
| `applicationModule` | 主应用模块 | 获取主模块信息 |
| `dynamicFeatureModules` | 动态特性模块列表 | 获取动态特性模块 |
| `modulesWithOrder` | 按依赖排序的模块列表 | 编译顺序 |

### 4.3 关键方法

| 方法 | 功能 |
|------|------|
| `getModuleDependencies(module, task)` | 获取模块依赖路径 |
| `getGeneratedSourcePaths(module)` | 获取生成的源码路径（如 R.java） |
| `getDesugarInfo(files, module, toDir)` | 获取 Desugar 信息（Java 8+ 特性支持） |
| `getLastBuildAndroidManifest(file)` | 获取上次构建的 Manifest |
| `getParentModules(module, isAddSelfToResult)` | 获取父模块列表 |
| `printClasspathCheck(module)` | 打印 Classpath 检查信息 |
| `getModulePackageName(module)` | 获取模块包名 |
| `backupGradleDir(sourceDir, ...)` | 备份 Gradle 目录 |

### 4.4 Desugar 信息

**定义位置**: `ICompiler.kt`

```kotlin
data class DesugarInfo(
    val allInterfacesWithDefaultMethod: List<String>,  // 包含默认方法的接口
    val coreLibraryRewriteClassMap: Map<String, String>, // 核心库重写映射
    val isNeedRewriteCoreLibrary: Boolean,             // 是否需要重写核心库
    val desugaredLibraryConfiguration: String?,        // Desugar 配置
)
```

**用途**: 支持 Java 8+ 特性（如 Lambda、Stream API）在低版本 Android 上运行。

---

## 五、增量编译助手 (IncrementalCompilerHelper)

### 5.1 核心职责

**定义位置**: `IncrementalCompilerHelper.kt`

| 职责 | 说明 |
|------|------|
| **编译重试** | 编译失败时自动重试（依赖修复后） |
| **影响分析** | 检测编译后影响的其他源文件 |
| **降级判断** | 文件过多/模块过多/设备未就绪时降级到 Gradle |
| **Dex 合并** | 合并多个 Dex 文件为单个或多个 Dex |

### 5.2 编译流程

```kotlin
fun compile(
    undeployedFiles: List<ChangedFile>,
    uiHandler: CompileUiHandler,
    compileStatusHolder: CompileStatusHolder,
    compiledFilesThisTime: List<ChangedFile> = emptyList(),
    isRetry: Boolean = false,
): CompileTaskResult
```

**流程图**:

```
1. 检查取消状态
    ↓
2. 转换为 CompileFile
    ↓
3. 执行编译 (JuggCompiler.compile)
    ↓
4. 更新文件状态 (deployFileManager)
    ↓
5. 检查编译结果
    ├─ 成功 → 6. 影响分析
    └─ 失败 → 9. 依赖修复
    ↓
6. 获取受影响的源文件 (getRecompileFiles)
    ↓
7. 过滤已编译文件 (避免死循环)
    ↓
8. 递归编译受影响文件
    ↓
9. 依赖修复 (IDependencyMissingResolver)
    ├─ 修复成功 → 重试编译
    └─ 修复失败 → 返回失败
```

### 5.3 降级策略

**检查点**: `checkFilesFallback()`

| 降级条件 | 阈值 | 说明 |
|---------|------|------|
| **模块数量过多** | > `JuggSettings.maxCompileSourceModules` | 默认值未知，需查看配置 |
| **文件数量过多** | > `JuggSettings.maxCompileSourceFilePoints` | Java 文件 2 分，Kotlin 文件 3 分 |
| **设备未就绪** | `IdeDeployState.State.INVALID_DEVICE` | 设备未连接或不支持 |

**降级结果**: 返回 `CompileTaskResult.incrementalFailed(isCanFallback=true, ...)`

### 5.4 影响分析机制

**核心逻辑**: 检测顶层类（Top-Level Class）变更

```kotlin
// 1. 获取受影响的源文件
val recompileFiles = deployFileManager.getRecompileFiles(isMinified)
val effectedSourceFiles = recompileFiles.effectedSourceFiles

// 2. 过滤已编译文件
val compiledFilesThisTimeSet = (undeployedFiles + compiledFilesThisTime).map { it.file.absolutePath }.toSet()

// 3. 检查顶层类变更
val kmModuleMerger = KmModuleMergerForCompilation(module.buildPathInfo.kotlinClassPath)
kmModuleMerger.loadAndMerge()
val extensionClasses = kmModuleMerger.getExtensionClasses().toSet()

// 4. 判断是否需要重新编译
if (extensionClasses.contains(effectedByClass)) {
    // 顶层类变更，强制重新编译
    return true
}
```

**设计亮点**: 避免 Kotlin 扩展函数/属性变更时的重复编译。

### 5.5 Dex 合并

**方法**: `mergeDex(compileResult, dexOutputDir)`

```kotlin
fun mergeDex(compileResult: CompileResult, dexOutputDir: File): CompileResult? {
    // 1. 提取所有 Dex 文件
    val dexFiles = compileResult.outputs
        .filter { it.type == CompileOutput.Type.Dex }
        .map { it.file }
    
    // 2. 清空输出目录
    dexOutputDir.deleteRecursively()
    dexOutputDir.mkdirs()
    
    // 3. 执行合并
    val mergedDexFiles = doMergeDex(dexFiles, dexOutputDir)
    
    // 4. 替换编译结果中的 Dex 文件
    val mergedOutput = mergedDexFiles + 
        compileResult.outputs.filter { it.type != CompileOutput.Type.Dex }
    
    return compileResult.copy(outputs = mergedOutput)
}
```

**用途**: 减少 Dex 文件数量，提升部署速度。

---

## 六、增量部署助手 (IncrementalDeployHelper)

### 6.1 核心职责

**定义位置**: `IncrementalDeployHelper.kt`

| 职责 | 说明 |
|------|------|
| **APK 更新** | 将编译产物插入 APK 并重新签名 |
| **增量 APK 导出** | 导出包含增量数据的 APK（用于分发） |

### 6.2 APK 更新流程

```kotlin
fun updateApk(apkInfos: List<ApkInfo>, allDeployItems: List<DeployItem>): Pair<Boolean, String>
```

**流程**:

```
1. 检查签名配置
    ↓
2. 遍历所有 APK 文件
    ↓
3. 创建 ApkFileModifier
    ↓
4. 过滤部署项
    ├─ Dex 文件 → 插入到 assets/jugg_/xxx.dex
    └─ 其他文件 → 覆盖原路径
    ↓
5. 插入文件并重新签名 (insertAndResign)
    ↓
6. 返回结果
```

**关键常量**:

```kotlin
const val INCREMENTAL_DATA_PATH = "assets/jugg_/"
```

**设计亮点**: Dex 文件不覆盖原 APK 中的 classes.dex，而是放在 `assets/jugg_/` 目录，由 JVMTI Agent 加载。

### 6.3 增量 APK 导出

**方法**: `exportIncrementalApk(outputDir, deployItems)`

**流程**:

```
1. 复制原 APK 到临时文件
    ↓
2. 更新临时 APK（调用 updateApk）
    ↓
3. 重命名为最终文件名
    ↓
4. 返回 APK 文件列表
```

**用途**: 生成可直接安装的增量 APK，用于测试或分发。

---

## 七、编译顺序控制 (CompileOrder)

### 7.1 顺序定义

**定义位置**: `CompileOrder.kt`

```kotlin
object CompileOrder {
    const val NO_ORDER = 0
    
    private const val FIRST = 1
    
    private const val ASSET_START = 1000
    private const val ASSET = 1100
    private const val ASSET_END = 1200
    
    private const val RES_START = 2000
    private const val RES = 2100
    private const val RES_END = 2200
    
    private const val SOURCE_START = 3000
    private const val SOURCE = 3100
    private const val SOURCE_END = 3200
    
    private const val MINIFY_START = 4000
    private const val MINIFY = 4100
    private const val MINIFY_END = 4200
    
    private const val DEX_START = 5000
    private const val DEX = 5100
    private const val DEX_END = 5200
    
    private const val LAST = 10000
}
```

### 7.2 执行阶段

| 阶段 | 范围 | 说明 |
|------|------|------|
| `atFirst` | 1..999 | 最先执行 |
| `beforeAsset` | 1000..1099 | Asset 编译前 |
| `afterAsset` | 1101..1199 | Asset 编译后 |
| `beforeRes` | 2000..2099 | 资源编译前 |
| `afterRes` | 2101..2199 | 资源编译后 |
| `beforeSource` | 3000..3099 | 源码编译前 |
| `afterSource` | 3101..3199 | 源码编译后 |
| `beforeMinify` | 4000..4099 | 混淆前 |
| `afterMinify` | 4101..4199 | 混淆后 |
| `beforeDex` | 5000..5099 | Dex 编译前 |
| `afterDex` | 5101..5199 | Dex 编译后 |
| `atLast` | 5200..9999 | 最后执行 |
| `noOrder` | 0..0 | 不参与排序 |

**使用示例**:

```kotlin
class MyCustomCompiler : BaseCompiler(...) {
    override val order: Int = CompileOrder.beforeSource.first
    override val beforeCompileOrderRange = CompileOrder.beforeSource
}
```

---

## 八、UI 交互与状态管理

### 8.1 CompileUiHandler - UI 交互接口

**定义位置**: `CompileUiHandler.kt`

| 方法 | 功能 |
|------|------|
| `confirmFallbackWhenNoFileChanges()` | 无文件变更时确认是否降级 |
| `confirmBuildChanges(project, changedBuildFiles)` | 构建文件变更时确认操作 |
| `confirmDependencyChanges(manager, runResult)` | 依赖变更时确认操作 |
| `updateIndicatorText(text)` | 更新进度指示器文本 |
| `listenCancelAction(listener)` | 监听取消操作 |
| `notifyByBalloon(text)` | 通过气泡通知用户 |
| `onEnd(runResult)` | 编译结束回调 |
| `cancel()` | 取消编译 |

### 8.2 CompileStatusHolder - 编译状态管理

**定义位置**: `CompileUiHandler.kt`

| 方法 | 功能 |
|------|------|
| `setCompileFiles(files)` | 设置待编译文件列表 |
| `onFilesCompiled(files)` | 通知文件编译完成 |
| `cancel()` | 取消编译 |
| `isShouldCancel` | 是否应取消编译 |

**设计模式**: 观察者模式，解耦编译逻辑和 UI 更新。

---

## 九、工具类与扩展

### 9.1 CompilerUtils - 编译工具类

**定义位置**: `CompilerUtils.kt`

| 方法 | 功能 |
|------|------|
| `matchGradleDir(dirSelectInOrder, default, condition)` | 匹配 Gradle 目录（选择最新修改的） |
| `File.listFilesRecursively()` | 递归列出所有文件 |
| `File.clearDir()` | 清空目录 |
| `File.changeBaseDir(curBaseDir, newBaseDir, ...)` | 修改文件基准目录 |
| `File.copyToBaseDir(curBaseDir, newBaseDir)` | 复制文件到新基准目录 |
| `Process.readOutput(logger)` | 读取进程输出 |
| `copyResource(resourcePath)` | 复制资源文件到系统目录 |

**平台检测**:

```kotlin
val isWindows = osName.contains("win")
val isLinux = listOf("nix", "nux", "aix").any { osName.contains(it) }
val isMac = osName.contains("mac")
```

### 9.2 CompilerExt - 编译器扩展

**定义位置**: `CompilerExt.kt`

**关键扩展方法**:

| 方法 | 功能 |
|------|------|
| `List<CompileFile>.desc()` | 生成编译文件描述（按模块和类型分组） |
| `CompileTask.toCancelResult()` | 转换为取消结果 |
| `ICompileContext.subContext(subTempCompileDirName)` | 创建子上下文 |
| `CompileOutput.toCompileFile(defaultModule)` | 转换为 CompileFile |
| `CompileFile.toCompileOutput()` | 转换为 CompileOutput |
| `CompileResult.failedAll(message)` | 标记所有文件失败 |
| `CompileTask.wrapToResult()` | 包装为成功结果 |

**依赖名称转 Dex 文件名**:

```kotlin
// 示例 1: Maven 依赖
// org.reactivestreams:reactive-streams:1.0.3 
// → #org.reactivestreams#reactive-streams.dex

// 示例 2: 本地 Jar
// ./app/libs/library2.v2.jar 
// → #app#libs#library2#library2.v2.dex
```

---

## 十、类结构分析 (ClassStructure)

### 10.1 ClassNode - Dex 类节点

**定义位置**: `ClassStructure.kt`

```kotlin
class ClassNode(
    val dexFileName: String,        // Dex 文件名
    val className: String,          // 类名
    val access: Int,                // 访问修饰符
    val methods: List<MethodNode>,  // 方法列表
    val fields: List<FieldNode>,    // 字段列表
    val interfaceNames: List<String>, // 接口列表
    val superClass: String,         // 父类
    val source: String,             // 源文件名
)
```

**用途**: 解析 Dex 文件，分析类结构，用于影响分析。

### 10.2 MethodNode - 方法节点

```kotlin
class MethodNode(
    val owner: String,              // 所属类
    val access: Int,                // 访问修饰符
    val name: String,               // 方法名
    val desc: String,               // 方法描述符
)
```

**关键方法**:

| 方法 | 功能 |
|------|------|
| `isEffectedChanged(method)` | 判断方法是否有效变更（忽略 abstract 和 private） |
| `equalsWithoutAccess(other)` | 忽略访问修饰符的相等性判断 |

### 10.3 FieldNode - 字段节点

```kotlin
class FieldNode(
    val owner: String,              // 所属类
    val access: Int,                // 访问修饰符
    val name: String,               // 字段名
    val type: String,               // 字段类型
)
```

### 10.4 ClassStringPool - 字符串池

**用途**: 节省内存，避免重复字符串实例。

```kotlin
object ClassStringPool {
    private var stringPool = ConcurrentHashMap<String, String>()
    
    operator fun get(string: String): String {
        return stringPool.getOrPut(string) { string }
    }
    
    fun clear() {
        stringPool = ConcurrentHashMap()
        System.gc()
    }
}
```

---

## 十一、依赖缺失解决器 (IDependencyMissingResolver)

### 11.1 接口定义

**定义位置**: `IDependencyMissingResolver.kt`

```kotlin
interface IDependencyMissingResolver {
    /**
     * @return is can retry
     */
    fun resolve(compileResult: CompileResult): Boolean
}
```

**用途**: 自动检测并修复依赖缺失问题，如：
- 缺少第三方库
- 缺少生成的代码（如 R.java）
- 缺少注解处理器生成的代码

**实现位置**: 具体实现在 `idea` 模块中。

---

## 十二、Mockito 修复器 (MockitoFixer)

### 12.1 问题背景

**定义位置**: `MockitoFixer.kt`

**问题**:
1. ByteBuddyAgent（Mockito 依赖）读取 `System.getProperty("java.home")` 时，路径包含空格会导致调用失败
2. JDK 1.8 会导致 "Could not self-attach to current VM using external process" 错误

**解决方案**:
- 项目要求 JDK 17，问题 2 已解决
- 问题 1 暂时注释掉修复代码（可能影响测试环境）

```kotlin
object MockitoFixer {
    fun tryFix() {
        if (isWindows) return
        
        // 打印 Java Home 信息
        var propertyJavaHome = System.getProperty("java.home")
        val envJavaHome = System.getenv("JAVA_HOME")
        println("propertyJavaHome: $propertyJavaHome, envJavaHome: $envJavaHome")
        
        // 修复代码已注释
    }
}
```

---

## 十三、编译任务结果 (CompileTaskResult)

### 13.1 数据结构

**定义位置**: `CompileTaskResult.kt`

```kotlin
data class CompileTaskResult(
    val isSuccess: Boolean,                     // 是否成功
    val isGradleCompile: Boolean,               // 是否为 Gradle 编译
    val isCanFallback: Boolean,                 // 是否可降级
    val costTime: Long,                         // 耗时
    val failedReason: String?,                  // 失败原因
    val incrementalFailedReason: String?,       // 增量编译失败原因
    val incrementalCompileResult: CompileResult?, // 增量编译结果
)
```

### 13.2 工厂方法

| 方法 | 说明 |
|------|------|
| `incrementalSuccess(compileResult)` | 增量编译成功 |
| `incrementalFailed(isCanFallback, failedReason)` | 增量编译失败 |
| `incrementalCanceled(startTime)` | 增量编译取消 |

### 13.3 导出增量 APK 结果

```kotlin
data class ExportIncrementalApkResult(
    val isSuccess: Boolean,
    val apkFiles: List<File>,
    val failedReason: String?,
)
```

---

## 十四、设计亮点总结

### 14.1 架构设计

| 亮点 | 说明 |
|------|------|
| **接口抽象** | ICompiler / ICompileContext 解耦编译逻辑和上下文 |
| **模板方法** | BaseCompiler 定义编译流程，子类实现具体逻辑 |
| **责任链模式** | 自定义编译器通过 order 控制执行顺序 |
| **观察者模式** | CompileStatusHolder 解耦编译进度和 UI 更新 |
| **工厂模式** | CompileTaskResult 提供多种工厂方法 |

### 14.2 性能优化

| 优化点 | 说明 |
|--------|------|
| **增量编译** | 仅编译变更文件及其影响范围 |
| **模块并行** | 无依赖关系的模块可并行编译（待验证） |
| **字符串池** | ClassStringPool 减少内存占用 |
| **智能降级** | 文件过多时降级到 Gradle，避免性能问题 |

### 14.3 容错设计

| 容错点 | 说明 |
|--------|------|
| **循环依赖处理** | 拓扑排序检测循环依赖，按依赖数量排序 |
| **编译重试** | 依赖修复后自动重试 |
| **快速失败** | 依赖编译失败时，快速失败其他文件 |
| **取消检查** | 编译流程多处检查取消状态 |

### 14.4 扩展性

| 扩展点 | 说明 |
|--------|------|
| **自定义编译器** | 通过 ICompiler 接口扩展 |
| **编译顺序控制** | 通过 CompileOrder 控制执行时机 |
| **文件消费机制** | 通过 consumeFiles 拦截文件 |
| **上下文扩展** | 通过 ICompileContext 扩展上下文信息 |

---

## 十五、待深入分析的模块

| 模块 | 文件数 | 说明 |
|------|--------|------|
| `compiler/source/` | ~15 | Java/Kotlin/Dex 编译器实现 |
| `compiler/overlay/` | ~10 | 资源增量编译 |
| `compiler/databinding/` | ~5 | DataBinding/ViewBinding 支持 |
| `compiler/manifest/` | ~3 | Manifest 增量编译 |
| `compiler/obfuscation/` | ~3 | R8 混淆支持 |

**下一步**: 阅读 `compiler/source/*.kt`，深入理解源码编译流程。

---

**文档状态**: ✅ 已完成  
**下一步**: 阅读阶段 2.2 - compiler/source/*.kt
