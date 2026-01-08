# Jugg 编译系统 - 源码编译器

> 文档版本: v1.0  
> 创建时间: 2025-01-20  
> 涵盖模块: main/compiler/source/**/*.kt (17个文件)

---

## 一、源码编译器概览

### 1.1 编译器架构

Jugg 源码编译系统采用**分层编译**策略，将源码编译分为三个阶段：

```
源码文件 (Java/Kotlin)
    ↓
SourceCompiler (协调器)
    ↓
├─ KotlinCompiler → Class 文件
├─ JavaCompiler → Class 文件
    ↓
ClassMinifyCompiler (混淆映射)
    ↓
DexCompiler → Dex 文件
    ↓
部署产物
```

### 1.2 编译器列表

| 编译器 | 输入类型 | 输出类型 | 职责 |
|--------|---------|---------|------|
| **SourceCompiler** | Java/Kotlin | Dex | 协调 Java/Kotlin 编译流程 |
| **JavaCompiler** | Java | Class/Java | Java 源码编译，支持 APT |
| **KotlinCompiler** | Kotlin | Class/Java/Kotlin | Kotlin 源码编译，支持 KAPT/KSP |
| **DexCompiler** | Class | Dex | Class 转 Dex，支持 Desugar |
| **DexFileMaker** | - | - | D8 工具封装 |
| **DexFileMerger** | Dex | Dex | Dex 文件合并 |
| **JarFileMaker** | Class | Jar | Jar 文件生成 |

---

## 二、SourceCompiler - 源码编译协调器

### 2.1 核心职责

**定义位置**: `SourceCompiler.kt`

```kotlin
class SourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent)
```

**支持类型**: `Java`, `Kotlin`

### 2.2 编译流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    // 1. Kotlin 编译（必须先于 Java）
    val kotlinCompileResult = kotlinCompiler.compile(kotlinCompileTask)
    
    // 2. Java 编译（包含 Kotlin APT 生成的 Java 文件）
    val javaCompileResult = javaCompiler.compile(javaCompileTask)
    
    // 3. 混淆映射（如果开启混淆）
    val minifyResult = classMinify.compile(minifyTask)
    
    // 4. Dex 编译
    val dexCompileResult = dexCompiler.compile(dexTask)
    
    return CompileResult(task, classCompileResult.details, dexCompileResult.outputs + otherOutputs)
}
```

### 2.3 关键设计

**Kotlin 优先编译**:
```kotlin
// Kotlin must go first because in the cross-reference case, 
// Java depends on Kotlin compile output
// while Kotlin don't (kotlin can use -Xjava-source-roots argument)
```

**原因**: 
- Java 依赖 Kotlin 编译产物
- Kotlin 通过 `-Xjava-source-roots` 参数可以引用 Java 源码

**失败快速传播**:
```kotlin
if (!kotlinCompileResult.isAllSuccess) {
    val otherDetails = task.files
        .filter { it.type != CompileFile.Type.Kotlin }
        .map { Result.failure(CompileError(it, listOf(-1L to "Kotlin compile failed, skip"))) }
    return CompileResult(task, kotlinCompileResult.details + otherDetails, kotlinCompileResult.outputs)
}
```

**产物分类**:
```kotlin
// e.g. META-INF/service/xxx
val otherOutputs = classCompileResult.outputs.filter {
    it.type != CompileOutput.Type.Class
}
```

---

## 三、JavaCompiler - Java 源码编译器

### 3.1 核心特性

**定义位置**: `JavaCompiler.kt`

| 特性 | 说明 |
|------|------|
| **编译器获取** | 优先使用 `ToolProvider.getSystemJavaCompiler()`，失败则反射获取 `JavacTool` |
| **APT 支持** | 支持注解处理器（可通过 `JuggSettings.isEnableApt` 控制） |
| **版本兼容** | 自动检测 source/target 版本不支持错误并重试 |
| **错误重试** | 编译失败时自动重建编译器实例 |
| **调试信息** | 默认生成调试信息（`-g`），包含局部变量名 |

### 3.2 编译参数

```kotlin
val options = mutableListOf(
    "-d", task.outputDir.absolutePath,  // 输出目录
    "-g",                                // 生成调试信息
    "-cp", dependencies.joinToString(File.pathSeparator), // Classpath
    "-source", sourceVersion,            // 源码版本
    "-target", targetVersion,            // 目标版本
    "-encoding", "UTF-8",                // 编码
)
```

**APT 参数**:
```kotlin
if (isEnableApt) {
    options.addAll(listOf(
        "-processorpath", annotationProcessorPath.joinToString(File.pathSeparator),
        "-sourcepath", module.sourceDirs.joinToString(File.pathSeparator),
    ))
} else {
    options.add("-proc:none") // 禁用注解处理
}
```

**注解处理器选项**:
```kotlin
module.javaAnnotationProcessorOptions?.forEach { (key, value) ->
    options.addAll(listOf("-A$key=\"$value\""))
}
```

### 3.3 错误处理

**诊断监听器**:
```kotlin
val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
    val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
    val message = diagnostic.toString()
    
    if (diagnostic.kind != Diagnostic.Kind.ERROR || item == null) {
        logger.debug("JavaCompiler output: [${diagnostic.kind}] $message")
        return@DiagnosticListener
    }
    
    logger.warn(message)
    item.errors.add(diagnostic.lineNumber to message)
}
```

**重试策略**:

| 重试条件 | 说明 |
|---------|------|
| **版本不支持** | 检测到 "不再支持" / "is no longer supported" |
| **错误过多** | 错误数 > `JuggSettings.minErrorToRecreateCompiler` |
| **无错误失败** | 编译失败但无错误信息 |

```kotlin
if (shouldRecreate && !hasRecreateAfterInternalError) {
    logger.warn("\n$retryReason, retry with recreating compiler once.\n")
    hasRecreateAfterInternalError = true
    compiler = getJavaCompiler(logger)
    return doModuleCompile(task, module)
}
```

### 3.4 产物处理

```kotlin
val outputs = task.outputDir.listFilesRecursively().map {
    if (it.extension == "class") {
        CompileOutput(CompileOutput.Type.Class, it, task.outputDir)
    } else if (it.extension == "java") {
        CompileOutput(CompileOutput.Type.Java, it, task.outputDir) // APT 生成的 Java
    } else {
        // e.g. META-INF/service/xxx
        CompileOutput(CompileOutput.Type.Res, it, task.outputDir, apkPath)
    }
}

// 复制到 Java Class Path
outputs.forEach {
    it.file.copyToBaseDir(task.outputDir, javaClassPath)
}
```

---

## 四、KotlinCompiler - Kotlin 源码编译器

### 4.1 核心架构

**定义位置**: `KotlinCompiler.kt`

```kotlin
class KotlinCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent)
```

**支持类型**: `Kotlin`

### 4.2 编译流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    // 1. 分析源码特性
    val options = analyzeSource(task.files.map { it.file }, module)
    
    // 2. 选择编译模式
    return if (options.isEnableKsp && !options.isKspWithCompilation) {
        kspAndCompile(task, module, options) // KSP 单独编译
    } else {
        compile(task, module, options)       // 正常编译或 KSP 同步编译
    }
}
```

### 4.3 源码特性分析

**方法**: `analyzeSource(files, module)`

**检测特性**:

| 特性 | 检测方式 | 用途 |
|------|---------|------|
| **Kotlin Android Extensions** | 检测 `import kotlinx.android.synthetic.*` | 启用 Kotlin 合成视图 |
| **Jetpack Compose** | 检测 `import androidx.compose.*` | 启用 Compose 编译器插件 |
| **R 包名** | 读取 Manifest | 为 Kotlin Android Extensions 提供包名 |

```kotlin
files.forEach root@{ file ->
    file.readLines().forEach {
        val line = it.trim()
        if (!line.startsWith("import")) return@forEach
        
        val importContent = line.substringAfter("import").trim()
        if (importContent.startsWith("kotlinx.android.synthetic.")) {
            isNeedKotlinAndroidExtensions = true
        }
        if (importContent.startsWith("androidx.compose.")) {
            isNeedCompileCompose = true
        }
    }
}
```

### 4.4 编译选项

**数据结构**:
```kotlin
data class Options(
    val isEnableKapt: Boolean = false,                      // 启用 KAPT
    val isNeedKotlinAndroidExtensions: Boolean = false,     // 需要 Kotlin Android Extensions
    val isNeedCompileCompose: Boolean = false,              // 需要编译 Compose
    val rPackageName: String? = null,                       // R 包名
    val isCanAutoRetry: Boolean = true,                     // 允许自动重试
    val kaptOptions: Map<String, String> = emptyMap(),      // KAPT 选项
    val kaptDependencies: List<File> = emptyList(),         // KAPT 依赖
    val javaSourceDirs: List<File>? = null,                 // Java 源码目录
    val isEnableKsp: Boolean = false,                       // 启用 KSP
    val isKspWithCompilation: Boolean = true,               // KSP 与编译同步
    val kspDependencies: List<File> = emptyList(),          // KSP 依赖
    val kotlinPlugins: List<File> = emptyList(),            // Kotlin 插件
    val kotlinExtensions: List<File> = emptyList(),         // Kotlin 扩展
)
```

### 4.5 预热机制

```kotlin
override fun warmUp() {
    val selectModule = context.modules.values
        .filter { module ->
            // 选择 Kotlin 模块
            val isKotlinModule = !module.kotlinPlugins.isNullOrEmpty() ||
                    module.libraryDependencies.any { it.name.contains("kotlin-stdlib") }
            return@filter isKotlinModule
        }.maxByOrNull {
            // 选择依赖最多的模块
            it.moduleDependencies.size + it.libraryDependencies.size
        }
    
    if (selectModule != null) {
        doModuleCompile(CompileTask(emptyList(), context.tempCompileDir, CompileStatusHolder.DEFAULT), selectModule)
    }
}
```

**目的**: 提前加载 Kotlin 编译器，减少首次编译耗时。

---

## 五、KotlinCompilerInvoker - Kotlin 编译器调用器

### 5.1 核心职责

**定义位置**: `KotlinCompilerInvoker.kt`

| 职责 | 说明 |
|------|------|
| **编译器管理** | 管理项目编译器和嵌入式编译器 |
| **参数构建** | 构建 Kotlin 编译器参数 |
| **插件管理** | 管理 Kotlin 插件（KAPT/KSP/Compose 等） |
| **错误重试** | 自动检测并修复编译错误 |
| **版本兼容** | 支持 Kotlin 1.4 ~ 2.1 |

### 5.2 编译器选择

**优先级**: 项目编译器 > 嵌入式编译器

```kotlin
fun initIfNeeded(projectCompilerClasspath: List<File>?, logger: Logger) {
    if (!JuggSettings.isUseProjectKotlinCompiler) {
        // 使用嵌入式编译器
        classLoader = getIsolateClassLoader(juggPluginClasspathUrls)
        isUseProjectCompiler = false
        return
    }
    
    try {
        // 尝试使用项目编译器
        classLoader = getIsolateClassLoader(projectCompilerClasspathUrls, isAllIncluded = true)
        isUseProjectCompiler = true
    } catch (e: Exception) {
        // 降级到嵌入式编译器
        classLoader = getIsolateClassLoader(juggPluginClasspathUrls)
        isUseProjectCompiler = false
    }
}
```

### 5.3 编译参数构建

**核心参数**:
```kotlin
val compileArgs = (module.kotlinFreeCompilerArgs + listOf(
    "-verbose",
    "-jvm-target", jvmTarget,                    // JVM 目标版本
    "-nowarn",                                   // 禁用警告
    "-no-stdlib",                                // 不自动添加 stdlib
    "-no-reflect",                               // 不自动添加 reflect
    "-module-name", moduleName,                  // 模块名
    "-Xfriend-paths=${kotlinClassPath.absolutePath}", // 友元路径
    "-Xskip-prerelease-check",                   // 跳过预发布版本检查
    "-Xskip-metadata-version-check",             // 跳过元数据版本检查
    "-Xallow-no-source-files",                   // 允许无源文件
    "-Xreport-output-files",                     // 报告输出文件
    "-Xjava-source-roots=${javaSourceRoots.joinToString(",")}", // Java 源码根目录
    "-Xallow-unstable-dependencies",             // 允许不稳定依赖
    "-d", outputDir.path,                        // 输出目录
)).toMutableList()
```

**语言版本**:
```kotlin
if (!kotlinCompile.isUseProjectCompiler) {
    // 使用嵌入式编译器，需要设置语言版本
    compileArgs.addAll(listOf("-language-version", guessKotlinVersionForEmbedded(module, logger)))
}
```

### 5.4 插件参数

**Kotlin 插件**:
```kotlin
val kotlinPlugins = options.kotlinPlugins
    .filter { !disablePlugins.contains(it) && !tryDisablePlugins.contains(it) }

if (kotlinCompile.isUseProjectCompiler) {
    kotlinPlugins.forEach {
        pluginArgs.add("-Xplugin=${it.path}")
    }
}
```

**Kotlin Android Extensions**:
```kotlin
if (options.isNeedKotlinAndroidExtensions) {
    val variantArgs: List<String> = resourcePaths.flatMap { resourcePath ->
        listOf("-P", "plugin:org.jetbrains.kotlin.android:variant=${flavor};${resourcePath}")
    }
    extensionArgs.addAll(variantArgs)
    extensionArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.android:package=${options.rPackageName}"))
    extensionArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.android:experimental=true"))
    
    if (!kotlinCompile.isUseProjectCompiler) {
        extensionArgs.add("-Xplugin=$kotlinAndroidExtensionsPath")
    }
}
```

**KAPT 参数**:
```kotlin
if (options.isEnableKapt) {
    kaptArgs.addAll(listOf(
        "-P", "plugin:org.jetbrains.kotlin.kapt3:sources=${kaptSourceDir}",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:classes=${kaptClassesDir}",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:stubs=${kaptStubsDir}",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:incrementalData=${kaptIncrementalDataDir}",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:verbose=true",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:correctErrorTypes=true",
        "-P", "plugin:org.jetbrains.kotlin.kapt3:aptMode=stubsAndApt",
    ))
    
    options.kaptDependencies.forEach {
        kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apclasspath=${it.path}"))
    }
    
    val encodedKaptOptions = encodeList(kaptOptions)
    kaptArgs.addAll(listOf("-P", "plugin:org.jetbrains.kotlin.kapt3:apoptions=${encodedKaptOptions}"))
}
```

**Compose 参数**:
```kotlin
private fun handleComposeArgs(...): List<String> {
    if (!options.isNeedCompileCompose) return emptyList()
    
    // 查找 Compose 插件
    var composeExtension = kotlinExtensions.find { it.path.contains("androidx.compose") }
        ?: kotlinPlugins.find { it.path.contains("org.jetbrains.compose") }
        ?: kotlinPlugins.find { it.path.contains("kotlin-compose-compiler") }
    
    if (composeExtension != null) {
        composeArgs.add("-Xplugin=${composeExtension.path}")
        composeArgs.addAll(listOf("-P", "plugin:androidx.compose.plugins.idea:enabled=true"))
        composeArgs.addAll(listOf("-P", "plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true"))
    }
    
    return composeArgs
}
```

### 5.5 错误重试机制

**重试条件**:

| 错误类型 | 检测方式 | 处理方式 |
|---------|---------|---------|
| **元数据版本错误** | 检测 "metadata" + "expected version" | 修复元数据版本并重试 |
| **错误过多** | 错误数 > `minErrorToRecreateCompiler` | 重建编译器并重试 |
| **内部错误** | `ExitCode.INTERNAL_ERROR` | 重建编译器并重试 |
| **插件选项缺失** | 检测 "Plugin \"xxx\" usage" | 禁用插件并重试 |
| **Parcelize 错误** | 检测 "ParcelizeComponentRegistrar" | 禁用 Parcelize 插件并重试 |
| **JVM Target 不匹配** | 检测 "cannot inline bytecode built with JVM target" | 调整 JVM Target 并重试 |

**元数据版本修复**:
```kotlin
private fun handleMetadataError(outputParser: KotlinCompilerOutputParser, logger: Logger): Boolean {
    if (outputParser.metadataVersionErrors.isEmpty()) return false
    
    outputParser.metadataVersionErrors.forEach { metadataError ->
        try {
            val errorMerger = KmModuleMergerForCompilation(metadataError.metadataFile.parentFile.parentFile)
            errorMerger.loadAndMerge()
            errorMerger.save(metadataError.expectMetadataVersion)
        } catch (e: Exception) {
            logger.debug("save .kotlin_module failed, just delete it.", e)
        }
    }
    return true
}
```

**插件禁用**:
```kotlin
val noOptionPlugins = mutableListOf<File>()
compileResults.forEach { result ->
    if (!result.isFailed) return@forEach
    
    val regex = Regex("Plugin \"(.*)\" usage")
    for (error in result.getFailure().errors) {
        val pluginName = regex.find(error.second)?.groupValues?.get(1)
        if (pluginName != null) {
            val relativePlugins = (kotlinPlugins + kotlinExtensions).filter {
                it.path.contains(pluginName, ignoreCase = true)
            }
            noOptionPlugins.addAll(relativePlugins)
        }
    }
}

if (noOptionPlugins.isNotEmpty()) {
    tryDisablePlugins = noOptionPlugins
    shouldRecreate = true
}
```

### 5.6 版本推断

**方法**: `guessKotlinVersionForEmbedded(module, logger)`

```kotlin
private fun guessKotlinVersionForEmbedded(module: ModuleInfo, logger: Logger): String {
    // 1. 查找 kotlin-stdlib
    val kotlinStdlibName = module.libraryDependencies.find {
        it.file.name.contains("kotlin-stdlib")
    }?.file?.nameWithoutExtension
    
    if (kotlinStdlibName == null) {
        return K2JVMCompilerIsolate.VERSION // 默认 1.7
    }
    
    // 2. 解析版本号
    val kotlinVersion = try {
        val splits = kotlinStdlibName.split("-")
        val regex = Regex("[0-9.]+")
        val version = splits.find { it.matches(regex) }
        version.split(".").take(2).joinToString(".")
    } catch (e: Exception) {
        return K2JVMCompilerIsolate.VERSION
    }
    
    // 3. 版本兼容性处理
    if (kotlinVersion in listOf("1.1", "1.2", "1.3")) {
        return "1.4" // 最低 1.4
    }
    if (kotlinVersion in listOf("2.2", "2.3", ..., "2.10")) {
        return "2.1" // 最高 2.1
    }
    
    return kotlinVersion
}
```

---

## 六、K2JVMCompilerIsolate - 编译器隔离加载

### 6.1 设计目的

**定义位置**: `K2JVMCompilerIsolate.kt`

**问题**:
1. IntelliJ IDEA 内嵌的 Kotlin 编译器版本可能与项目不一致
2. PsiClassImpl 类冲突：`com.intellij.psi.impl.source.PsiClassImpl` vs `org.jetbrains.kotlin.com.intellij.psi.impl.source.PsiClassImpl`

**解决方案**: 使用隔离的 ClassLoader 加载 Kotlin 编译器

### 6.2 ClassLoader 架构

```
URLClassLoader (空数组)
    ↓
PriorityURLClassLoader (Kotlin 编译器 + 依赖)
    ↓
Jugg Plugin ClassLoader (低优先级父加载器)
```

**关键代码**:
```kotlin
private fun getIsolateClassLoader(urls: List<URL>, isAllIncluded: Boolean = false): URLClassLoader {
    val libraryClasspath = filterCompilerLibraries(urls)
    val missingClasspath = getMissingClasspath(libraryClasspath)
    if (missingClasspath.isNotEmpty()) {
        throw JuggInternalException.initKotlinCompilerFailed(missingClasspath)
    }
    
    val finalLibraryClasspath = if (isAllIncluded) urls else libraryClasspath
    
    val loader = PriorityURLClassLoader(
        finalLibraryClasspath.toTypedArray(), 
        lowPriorityParent = this::class.java.classLoader
    )
    
    // 包装一层空 ClassLoader，避免 KAPT 使用 Jugg 的 ClassLoader
    return URLClassLoader(emptyArray(), loader)
}
```

### 6.3 必需库检查

```kotlin
private val requiredLibraries = setOf(
    "annotations",
    "kotlin-compiler-embeddable",
    "kotlin-reflect",
    "kotlin-stdlib",
)

private fun getMissingClasspath(libraryClasspath: List<URL>): List<String> {
    return requiredLibraries.filter { libraryName ->
        !libraryClasspath.any {
            File(it.file).name.startsWith(libraryName) && File(it.file).name.endsWith(".jar")
        }
    }
}
```

### 6.4 编译器调用

```kotlin
@Synchronized
fun exec(printStream: PrintStream, args: Array<String>): ExitCode {
    // 1. 加载编译器类
    val compileClass = classLoader.loadClass("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    val compileInstance = compileClass.declaredConstructors[0].newInstance()
    
    // 2. 调用 exec 方法
    val method = compileClass.getMethod("exec", PrintStream::class.java, Array<String>::class.java)
    val exitCodeIsolate = method.invoke(compileInstance, printStream, args)
    
    // 3. 转换退出码
    val exitCodeClass = classLoader.loadClass("org.jetbrains.kotlin.cli.common.ExitCode")
    val exitCodeMethod = exitCodeClass.getDeclaredMethod("getCode")
    val exitCodeInt = exitCodeMethod.invoke(exitCodeIsolate)
    
    val exitCode = when(exitCodeInt) {
        0 -> ExitCode.OK
        1 -> ExitCode.COMPILATION_ERROR
        2 -> ExitCode.INTERNAL_ERROR
        3 -> ExitCode.SCRIPT_EXECUTION_ERROR
        else -> throw IllegalArgumentException("unexpected exit code $exitCodeInt")
    }
    
    return exitCode
}
```

---

## 七、KotlinCompilerOutputParser - 输出解析器

### 7.1 核心职责

**定义位置**: `KotlinCompilerOutputParser.kt`

| 职责 | 说明 |
|------|------|
| **输出解析** | 解析 Kotlin 编译器输出 |
| **错误收集** | 收集编译错误信息 |
| **文件映射** | 映射源文件到输出文件 |
| **元数据错误检测** | 检测元数据版本错误 |

### 7.2 消息类型

```kotlin
private enum class MessageType {
    LOGGING,    // 日志信息
    WARNING,    // 警告信息
    ERROR,      // 错误信息
    OUTPUT,     // 输出文件信息
    EXCEPTION,  // 异常信息
}
```

**识别正则**:
```kotlin
val newLineRegex = Regex("(.*):?(logging|warning|error|output|exception):(.*)")
```

### 7.3 错误解析

**错误格式**:
```
src/main/java/com/example/MainActivity.kt:9:26: error: smart cast to 'MutableList<String>' is impossible
```

**解析逻辑**:
```kotlin
private val errorRegex = Regex("(.*):(.*):(.*): error: (.*)")

private fun parseErrorMessage(message: String): String {
    // 1. 检查元数据版本错误
    if (MetadataVersionError.isMyError(message)) {
        val error = MetadataVersionError.create(message)
        if (error != null) {
            metadataVersionErrors.add(error)
        }
        return message
    }
    
    // 2. 解析文件路径和行号
    val contents = errorRegex.find(message)
    val filePath = contents?.groups?.get(1)?.value?: ""
    val line = contents?.groups?.get(2)?.value?.toLongOrNull()?: -1L
    
    // 3. 查找对应文件
    val file = files.find { it.file.absolutePath.endsWith(filePath) }
    if (file == null) {
        // 通用错误，添加到所有文件
        files.forEach {
            innerErrors.getOrPut(it) { mutableListOf() }.add(-1L to message)
        }
        return message
    }
    
    // 4. 记录错误
    innerErrors.getOrPut(file) { mutableListOf() }.add(line to message)
    
    // 5. 替换为绝对路径（方便 IDE 跳转）
    return message.replace(filePath, file.file.absolutePath)
}
```

### 7.4 输出文件解析

**输出格式**:
```
output: output:
/path/to/output/MainActivity.class
Sources:
/path/to/source/MainActivity.kt
```

**解析逻辑**:
```kotlin
private fun parseOutputMessage(message: String) {
    val contents = message.split("\n")
    val outputFiles = mutableListOf<File>()
    val sourceFile = mutableListOf<File>()
    
    // 跳过第一行 "output: output:"
    for (i in 1 until contents.size) {
        val filePath = contents[i].trim()
        if (filePath == "Sources:") {
            // 解析源文件
            for (j in i + 1 until contents.size) {
                val sourceFilePath = contents[j].trim()
                sourceFile.add(File(sourceFilePath))
            }
            break
        }
        // 解析输出文件
        outputFiles.add(File(filePath))
    }
    
    // 建立映射关系
    sourceFile.forEach {
        innerOutputs.getOrPut(it) { mutableListOf() }.addAll(outputFiles)
    }
}
```

### 7.5 元数据版本错误

**错误格式**:
```
/path/to/META-INF/app_debug.kotlin_module: error: module was compiled with an incompatible version of Kotlin. 
The binary version of its metadata is 1.7.0, expected version is 1.1.16.
```

**数据结构**:
```kotlin
data class MetadataVersionError(
    val message: String,
    val metadataFile: File,
    val actualVersion: String,
    val expectVersion: String,
    val expectMetadataVersion: JvmMetadataVersion,
)
```

**解析逻辑**:
```kotlin
fun create(message: String): MetadataVersionError? {
    val regex = Regex("(.*): error: .* is ([0-9.]+), expected version is ([0-9.]+)\\.")
    val matchResult = regex.find(message) ?: return null
    
    val metadataFile = File(matchResult.groups[1]!!.value)
    val actualVersion = matchResult.groups[2]!!.value
    val expectVersion = matchResult.groups[3]!!.value
    
    val expectMetadataVersion = run {
        val splits = expectVersion.split(".")
        val major = splits.getOrNull(0)?.toIntOrNull()
        val minor = splits.getOrNull(1)?.toIntOrNull()
        val patch = splits.getOrNull(2)?.toIntOrNull()
        if (major == null || minor == null) return@run null
        JvmMetadataVersion(major, minor, patch ?: 0)
    } ?: return null
    
    return MetadataVersionError(message, metadataFile, actualVersion, expectVersion, expectMetadataVersion)
}
```

---

## 八、DexCompiler - Dex 编译器

### 8.1 核心职责

**定义位置**: `DexCompiler.kt`

| 职责 | 说明 |
|------|------|
| **Class 转 Dex** | 将 Class 文件转换为 Dex 文件 |
| **Jar 增量编译** | 对比 Jar 文件变更，仅编译变更的 Class |
| **Desugar 支持** | 支持 Java 8+ 特性（Lambda、Stream API 等） |
| **MinSdk 适配** | 根据 minSdkVersion 调整 Dex 编译参数 |

### 8.2 MinSdk 计算

```kotlin
val minApi = run {
    val applicationMinApi = context.applicationModule?.minSdkVersion?.toIntOrNull()
    val isEnableDesugared = context.isEnableDesugared
    
    val finalMinApi = when {
        // 项目启用 Desugar，但主模块 minSdk >= 26（禁用 Desugar）
        // 使用 21 强制启用 Desugar
        (isEnableDesugared && applicationMinApi != null && applicationMinApi >= 26) -> 21
        
        // 使用主模块的 minSdkVersion
        (applicationMinApi != null && applicationMinApi > 0) -> applicationMinApi
        
        // 启用 Desugar
        isEnableDesugared -> 21
        
        // 禁用 Desugar
        else -> 31
    }
    
    finalMinApi
}
```

### 8.3 Jar 增量编译

**方法**: `diffJar(compileFile)`

```kotlin
private fun diffJar(compileFile: CompileFile): List<CompileFile> {
    val oldJar = compileFile.oldJar
    if (oldJar == null || !oldJar.exists()) {
        // 无旧版本，编译所有 Class
        return listOf(compileFile)
    }
    
    // 1. 读取旧 Jar 的 CRC 映射
    val oldJarEntryMap = mutableMapOf<String, Long>()
    ZipFile(oldJar).use { zipFile ->
        zipFile.entries().asSequence().forEach {
            oldJarEntryMap[it.name] = it.crc
        }
    }
    
    // 2. 对比新 Jar，提取变更的 Class
    val changedClasses = mutableListOf<CompileFile>()
    ZipFile(jarFile).use { zipFile ->
        zipFile.entries().asSequence().forEach {
            if (!it.name.endsWith(".class")) return@forEach
            if (it.name.startsWith("META-INF/")) return@forEach
            
            val oldCrc = oldJarEntryMap[it.name]
            if (oldCrc == null || oldCrc != it.crc) {
                // CRC 不同，提取 Class 文件
                val classFile = File(tmpClassesDir, it.name)
                classFile.parentFile.mkdirs()
                classFile.writeBytes(zipFile.getInputStream(it).readBytes())
                changedClasses.add(CompileFile(CompileFile.Type.Class, classFile, tmpClassesDir, compileFile.module))
            }
        }
    }
    
    return changedClasses
}
```

### 8.4 Dex 编译

**方法**: `doDex(task, inputFiles, files, minApi, isFilePerClass, outputDexName, module)`

```kotlin
private fun doDex(...): CompileResult {
    val tempOutput = File(context.tempCompileDir, "output")
    tempOutput.clearDir()
    
    // 1. 获取 Desugar 信息
    val classpathDir = File(context.tempCompileDir, "classpath")
    classpathDir.mkdirs()
    classpathDir.clearDir()
    val desugarInfo = context.getDesugarInfo(files, module, classpathDir)
    
    // 2. 调用 D8 编译
    dexFileMaker.dex(
        tempOutput, 
        files.map { it.file }, 
        listOf(classpathDir.absolutePath),
        context.androidJar, 
        minApi, 
        isFilePerClass, 
        desugarInfo.desugaredLibraryConfiguration
    )
    
    // 3. 收集 Dex 文件
    val dexFiles: List<File> = if (isFilePerClass) {
        tempOutput.listFilesRecursively()
    } else {
        val dexFile = File(tempOutput, "classes.dex")
        if (!dexFile.exists()) {
            emptyList() // Jar 无 Class（如 kotlin-stdlib-common）
        } else {
            val renameDexFile = File(tempOutput, outputDexName)
            dexFile.renameTo(renameDexFile)
            listOf(renameDexFile)
        }
    }
    
    // 4. 移动到输出目录
    val finalOutputs = dexFiles.map {
        val outputFile = it.changeBaseDir(it.baseDir, task.outputDir)
        outputFile.parentFile.mkdirs()
        it.renameTo(outputFile)
        CompileOutput(CompileOutput.Type.Dex, outputFile, task.outputDir)
    }
    
    return CompileResult(task, inputFiles.map { Result.success(it) }, finalOutputs)
}
```

---

## 九、DexFileMaker - D8 工具封装

### 9.1 核心职责

**定义位置**: `DexFileMaker.kt`

封装 Android D8 工具，提供简洁的 Dex 编译接口。

### 9.2 编译参数

```kotlin
fun dex(
    outputDir: File,
    classFilesOrDir: List<File>,
    classpath: Collection<String>,
    androidJar: File,
    minApi: Int,
    isFilePerClass: Boolean = true,
    desugaredLibraryConfiguration: String? = null,
)
```

**参数说明**:

| 参数 | 说明 |
|------|------|
| `outputDir` | 输出目录 |
| `classFilesOrDir` | Class 文件或目录列表 |
| `classpath` | Classpath（用于 Desugar） |
| `androidJar` | android.jar 路径 |
| `minApi` | 最低 API 级别 |
| `isFilePerClass` | 是否每个 Class 生成一个 Dex |
| `desugaredLibraryConfiguration` | Desugar 配置 |

### 9.3 D8 命令构建

```kotlin
val args = mutableListOf<String>()

if (isFilePerClass) {
    args.add("--file-per-class")
}

args.add("--lib")
args.add(androidJar.absolutePath)

args.add("--min-api")
args.add("$minApi")

if (classpath.isNotEmpty()) {
    classpath.forEach {
        args.add("--classpath")
        args.add(it)
    }
}

args.add("--output")
args.add(outputDir.absolutePath)

args.addAll(classFilesOrDir.map { it.absolutePath })

val builder = D8Command.parse(args.toTypedArray(), Origin.root())
if (desugaredLibraryConfiguration != null) {
    builder.addDesugaredLibraryConfiguration(desugaredLibraryConfiguration)
}

com.android.tools.r8.D8.run(builder.build())
```

---

## 十、DexFileMerger - Dex 合并器

### 10.1 核心职责

**定义位置**: `DexFileMerger.kt`

合并多个 Dex 文件为单个或多个 Dex 文件（受 64K 方法数限制）。

### 10.2 合并逻辑

```kotlin
fun merge(dexFiles: List<File>, outputDir: File) {
    outputDir.deleteRecursively()
    outputDir.mkdirs()
    
    val args = mutableListOf<String>()
    args.add("--output")
    args.add(outputDir.absolutePath)
    args.addAll(dexFiles.map { it.absolutePath })
    
    val builder = D8Command.parse(args.toTypedArray(), Origin.root())
    com.android.tools.r8.D8.run(builder.build())
}
```

**用途**: 减少 Dex 文件数量，提升部署速度。

---

## 十一、JarFileMaker - Jar 文件生成器

### 11.1 核心职责

**定义位置**: `JarFileMaker.kt`

将 Class 文件打包为 Jar 文件。

### 11.2 打包逻辑

```kotlin
fun jar(classDir: File, outputFile: File, classFile: File = classDir, isNeedManifest: Boolean = false) {
    if (outputFile.exists()) {
        outputFile.delete()
    }
    
    val target = if (isNeedManifest) {
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        JarOutputStream(FileOutputStream(outputFile), manifest)
    } else {
        JarOutputStream(FileOutputStream(outputFile))
    }
    
    add(classDir, classFile, target)
    target.close()
}
```

**递归添加文件**:
```kotlin
private fun add(baseDir: File, source: File, target: JarOutputStream) {
    if (source.isDirectory) {
        var name = source.path.replace("\\", "/")
        name = name.substring(baseDir.absolutePath.length)
        if (name.isNotEmpty()) {
            if (!name.endsWith("/")) name += "/"
            val entry = JarEntry(name.substring(1))
            entry.time = source.lastModified()
            target.putNextEntry(entry)
            target.closeEntry()
        }
        for (nestedFile in source.listFiles()) {
            add(baseDir, nestedFile, target)
        }
        return
    }
    
    // 添加文件
    var name = source.path.replace("\\", "/")
    name = name.substring(baseDir.absolutePath.length + 1)
    val entry = JarEntry(name)
    entry.time = source.lastModified()
    target.putNextEntry(entry)
    
    val ins = BufferedInputStream(FileInputStream(source))
    val buffer = ByteArray(1024)
    while (true) {
        val count = ins.read(buffer)
        if (count == -1) break
        target.write(buffer, 0, count)
    }
    target.closeEntry()
    ins.close()
}
```

---

## 十二、Kotlin 元数据处理

### 12.1 IKmModuleMergerForCompilation - 元数据合并接口

**定义位置**: `IKmModuleMergerForCompilation.kt`

**职责**: 合并 `.kotlin_module` 文件，解决 Kotlin 扩展函数/属性的引用问题。

**接口定义**:
```kotlin
interface IKmModuleMergerForCompilation {
    fun loadAndMerge()
    fun save(targetVersion: JvmMetadataVersion? = null)
}
```

**实现选择**:
```kotlin
companion object {
    fun create(kotlinVersion: String?, kotlinClassPath: File, logger: Logger): IKmModuleMergerForCompilation {
        if (kotlinVersion == null) {
            return KmModuleMergerCopy(kotlinClassPath)
        }
        
        val versions = getKotlinVersion(kotlinVersion)
        if (versions == null) {
            return KmModuleMergerCopy(kotlinClassPath)
        }
        
        val major = versions[0]
        val minor = versions[1]
        return if ((major > 2) || (major == 2 && minor >= 2)) {
            KmModuleMergerForCompilation22(kotlinClassPath) // Kotlin 2.2+
        } else {
            KmModuleMergerForCompilation(kotlinClassPath)   // Kotlin < 2.2
        }
    }
}
```

**版本兼容性**:
- **Kotlin < 2.2**: 使用 `KmModuleMergerForCompilation`
- **Kotlin >= 2.2**: 使用 `KmModuleMergerForCompilation22`
- **无法识别**: 使用 `KmModuleMergerCopy`（仅复制，不合并）

---

## 十三、设计亮点总结

### 13.1 编译流程优化

| 优化点 | 说明 |
|--------|------|
| **Kotlin 优先编译** | Java 依赖 Kotlin 产物，Kotlin 通过 `-Xjava-source-roots` 引用 Java |
| **失败快速传播** | Kotlin 编译失败时，快速失败 Java 编译 |
| **Jar 增量编译** | 对比 CRC，仅编译变更的 Class |
| **预热机制** | 提前加载 Kotlin 编译器，减少首次编译耗时 |

### 13.2 错误处理

| 错误类型 | 处理方式 |
|---------|---------|
| **版本不支持** | 自动调整 source/target 版本并重试 |
| **元数据版本错误** | 自动修复元数据版本并重试 |
| **插件选项缺失** | 自动禁用插件并重试 |
| **JVM Target 不匹配** | 自动调整 JVM Target 并重试 |
| **编译器内部错误** | 重建编译器实例并重试 |

### 13.3 插件支持

| 插件 | 支持方式 |
|------|---------|
| **KAPT** | 完整支持，包括选项传递和产物收集 |
| **KSP** | 支持单独编译和同步编译两种模式 |
| **Kotlin Android Extensions** | 自动检测 import 并启用 |
| **Jetpack Compose** | 自动检测 import 并启用 |
| **Parcelize** | 自动检测并处理 ClassCastException |

### 13.4 版本兼容性

| 版本范围 | 支持方式 |
|---------|---------|
| **Kotlin 1.4 ~ 2.1** | 自动推断版本并设置语言版本 |
| **Java 1.6 ~ 21** | 自动检测并调整 source/target 版本 |
| **Android API 21 ~ 35** | 自动调整 minApi 和 Desugar 配置 |

### 13.5 性能优化

| 优化点 | 说明 |
|--------|------|
| **隔离 ClassLoader** | 避免类冲突，提升编译稳定性 |
| **输出流式解析** | 边编译边解析，减少内存占用 |
| **Jar 增量编译** | 仅编译变更的 Class，减少编译时间 |
| **文件级 Dex** | `--file-per-class` 提升增量编译效率 |

---

## 十四、待深入分析的模块

| 模块 | 文件数 | 说明 |
|------|--------|------|
| `compiler/overlay/` | ~10 | 资源增量编译 |
| `compiler/databinding/` | ~5 | DataBinding/ViewBinding 支持 |
| `compiler/manifest/` | ~3 | Manifest 增量编译 |
| `compiler/obfuscation/` | ~3 | R8 混淆支持 |

**下一步**: 阅读 `compiler/overlay/*.kt`，深入理解资源增量编译流程。

---

**文档状态**: ✅ 已完成  
**下一步**: 阅读阶段 2.3 - compiler/overlay/*.kt
