# Jugg 编译系统 - 自定义编译器和 UI

> 文档版本: v1.0  
> 创建时间: 2025-01-20  
> 涵盖模块: main/compiler/custom/*.kt + main/compiler/ui/*.kt (4个文件)

---

## 一、自定义编译器和 UI 概览

### 1.1 模块架构

```
自定义编译器 JAR
    ↓
CustomCompilerManager
    ├─ 下载/更新编译器 JAR
    ├─ MD5 校验
    └─ ServiceLoader 加载
    ↓
ICompilerCreator (SPI)
    ↓
自定义 ICompiler 实例
    ↓
集成到编译流程

UI 数据结构
    ├─ RunResult (运行结果)
    └─ BuildChangesConfirmResult (构建确认结果)
```

### 1.2 组件列表

| 组件 | 类型 | 职责 |
|------|------|------|
| **CustomCompilerManager** | 管理器 | 管理自定义编译器的下载、更新、加载 |
| **ICompilerCreator** | SPI 接口 | 自定义编译器创建接口 |
| **RunResult** | 数据类 | 运行结果数据结构 |
| **BuildChangesConfirmResult** | 枚举 | 构建确认结果枚举 |

---

## 二、CustomCompilerManager - 自定义编译器管理器

### 2.1 核心职责

**定义位置**: `CustomCompilerManager.kt`

| 职责 | 说明 |
|------|------|
| **编译器下载** | 从 HTTP URL 下载自定义编译器 JAR |
| **MD5 校验** | 校验下载的 JAR 文件 MD5 |
| **编译器更新** | 更新和清理过期的编译器 JAR |
| **编译器加载** | 使用 ServiceLoader 加载自定义编译器 |
| **生命周期管理** | 管理自定义编译器的初始化和销毁 |

### 2.2 编译器配置

**配置数据结构**:
```kotlin
// 来自 JuggServer
data class CustomCompilerInfo(
    val jarFileName: String,
    val path: String,
    val md5: String
)
```

**配置示例**:
```json
[
  {
    "jarFileName": "my-custom-compiler.jar",
    "path": "http://example.com/compilers/my-custom-compiler.jar",
    "md5": "abc123def456..."
  },
  {
    "jarFileName": "local-compiler.jar",
    "path": "libs/local-compiler.jar",
    "md5": "xyz789..."
  }
]
```

### 2.3 编译器更新流程

```kotlin
fun updateCustomCompilers(customCompilers: List<CustomCompilerInfo>?) {
    if (customCompilers == null) {
        logger.debug("updateCustomCompilers with null config, exit.")
        return
    }
    
    // 1. 更新编译器 JAR
    customCompilerJars = customCompilers.mapNotNull {
        updateCustomCompiler(it)
    }
    
    // 2. 清理过期的 JAR
    customCompilerDir.listFiles()?.forEach { file ->
        if (!customCompilerJars.contains(file)) {
            logger.debug("custom compiler $file deprecated, delete it")
            file.delete()
        }
    }
    
    // 3. 异步下载缺失的编译器
    juggServer.launchSafe {
        downloadCompilers(customCompilers)
    }
}
```

**单个编译器更新**:
```kotlin
private fun updateCustomCompiler(customCompilerInfo: CustomCompilerInfo): File? {
    val file = getCustomCompiler(customCompilerInfo)
    if (file != null) {
        val md5 = file.md5()
        if (md5 != customCompilerInfo.md5) {
            logger.debug("custom compiler $file md5 mismatch, delete it")
            file.delete()
            return null
        }
    }
    return file
}
```

### 2.4 编译器查找

**查找优先级**:
1. **绝对路径**: 如果 `path` 是绝对路径且文件存在
2. **相对路径**: 相对于项目根目录的路径
3. **HTTP URL**: 从 `customCompilerDir` 查找已下载的文件

```kotlin
private fun getCustomCompiler(customCompilerInfo: CustomCompilerInfo): File? {
    val name = customCompilerInfo.jarFileName
    val path = customCompilerInfo.path
    
    // 1. 绝对路径
    val absFile = File(path)
    if (absFile.isAbsolute && absFile.exists()) {
        logger.debug("custom compiler $absFile exists")
        return absFile
    }
    
    // 2. 相对路径
    val relativeFile = File(projectDir, path)
    if (relativeFile.exists()) {
        logger.debug("custom compiler $relativeFile exists")
        return relativeFile
    }
    
    // 3. HTTP URL
    if (path.startsWith("http")) {
        val targetFile = customCompilerDir.resolve(name)
        if (targetFile.exists()) {
            logger.debug("http target file $targetFile exists")
            return targetFile
        } else {
            logger.debug("http target file $targetFile not exists, download it later")
            return null
        }
    }
    
    logger.debug("unknown path $path, ignore")
    return null
}
```

### 2.5 编译器下载

**下载流程**:
```kotlin
private fun downloadCompilers(customCompilers: List<CustomCompilerInfo>) {
    var isNeedReset = false
    customCompilers.forEach {
        if (it.path.startsWith("http")) {
            downloadCompiler(it)
            isNeedReset = true
        }
    }
    if (isNeedReset) {
        resetCompilerJars()
    }
}

private fun downloadCompiler(customCompilerInfo: CustomCompilerInfo) {
    val targetFile = customCompilerDir.resolve(customCompilerInfo.jarFileName)
    if (targetFile.exists() && targetFile.length() > 0) {
        return
    }
    
    try {
        // 下载文件
        juggServer.downloadFile(customCompilerInfo.path, targetFile)
        
        val isSuccess = targetFile.exists() && targetFile.length() > 0
        if (!isSuccess) {
            logger.debug("failed to download $customCompilerInfo")
            return
        }
        
        logger.debug("success download $customCompilerInfo")
        
        // MD5 校验
        val md5 = targetFile.md5()
        if (md5 != customCompilerInfo.md5) {
            logger.debug("custom compiler $customCompilerInfo md5 mismatch, actual: $md5. delete it")
            targetFile.delete()
        }
    } catch (e: Exception) {
        logger.warn("error downloading $customCompilerInfo, skip. error: $e")
    }
}
```

**MD5 计算**:
```kotlin
private fun File.md5(): String {
    val md = MessageDigest.getInstance("MD5")
    md.update(readBytes())
    return md.digest().joinToString("") { "%02x".format(it) }
}
```

### 2.6 编译器加载

**初始化**:
```kotlin
@Synchronized
fun init(context: ICompileContext, parent: Disposable) {
    logger.debug("init")
    this.compileContext = context
    this.compileParentDisposable = parent
    this.customCompilers = emptyList()
}
```

**ServiceLoader 加载**:
```kotlin
private fun initCompilers(): List<ICompiler> {
    logger.debug("initCompilers")
    val context = compileContext ?: return emptyList()
    val parent = compileParentDisposable ?: return emptyList()
    
    // 1. 创建 URLClassLoader
    val urls = customCompilerJars.map { it.toURI().toURL() }.toTypedArray()
    val classLoader = URLClassLoader(urls, this::class.java.classLoader)
    
    // 2. 使用 ServiceLoader 加载 ICompilerCreator
    val customCompilers = mutableListOf<ICompiler>()
    ServiceLoader.load(ICompilerCreator::class.java, classLoader).forEach {
        val compiler = it.create(context, parent)
        customCompilers.add(compiler)
    }
    
    logger.debug("initCompilers finished: $customCompilers")
    return customCompilers
}
```

**获取编译器**:
```kotlin
@Synchronized
fun getCustomCompilers(): List<ICompiler> {
    if (customCompilerJars.isNotEmpty() && customCompilers.isEmpty()) {
        customCompilers = initCompilers()
    }
    return customCompilers
}
```

**重置编译器**:
```kotlin
private fun resetCompilerJars() {
    customCompilerJars = customCompilerDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
    this.customCompilers = emptyList() // 下次重新创建
    logger.debug("resetCompilerJars: $customCompilerJars")
}
```

---

## 三、ICompilerCreator - 自定义编译器创建接口

### 3.1 SPI 接口定义

**定义位置**: `ICompilerCreator.kt`

```kotlin
interface ICompilerCreator {
    fun create(context: ICompileContext, parent: Disposable): ICompiler
}
```

### 3.2 使用方式

**1. 实现 ICompilerCreator 接口**:
```kotlin
package com.example.mycompiler

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator

class MyCompilerCreator : ICompilerCreator {
    override fun create(context: ICompileContext, parent: Disposable): ICompiler {
        return MyCustomCompiler(context, parent)
    }
}
```

**2. 创建 SPI 配置文件**:

文件路径: `META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator`

文件内容:
```
com.example.mycompiler.MyCompilerCreator
```

**3. 实现自定义编译器**:
```kotlin
package com.example.mycompiler

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo

class MyCustomCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {
    
    override val supportedTypes = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)
    
    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeSource
    override val afterCompileOrderRange: IntRange = CompileOrder.afterSource
    
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // 自定义编译逻辑
        logger.info("MyCustomCompiler is compiling ${task.files.size} files")
        
        // ... 编译逻辑 ...
        
        return CompileResult(task, details, outputs)
    }
}
```

**4. 打包为 JAR**:
```bash
# 确保包含 META-INF/services 目录
jar cvf my-custom-compiler.jar -C build/classes/kotlin/main .
```

**5. 配置到 Jugg**:
```json
{
  "customCompilers": [
    {
      "jarFileName": "my-custom-compiler.jar",
      "path": "http://example.com/my-custom-compiler.jar",
      "md5": "abc123..."
    }
  ]
}
```

### 3.3 自定义编译器示例

**示例 1: 代码检查编译器**:
```kotlin
class CodeLintCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {
    
    override val supportedTypes = listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin)
    override val beforeCompileOrderRange = CompileOrder.beforeSource
    
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val details = task.files.map { file ->
            val errors = lintFile(file.file)
            if (errors.isEmpty()) {
                Result.success(file)
            } else {
                Result.failure(CompileError(file, errors))
            }
        }
        return CompileResult(task, details, emptyList())
    }
    
    private fun lintFile(file: File): List<Pair<Long, String>> {
        // 代码检查逻辑
        return emptyList()
    }
}
```

**示例 2: 代码生成编译器**:
```kotlin
class CodeGenCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {
    
    override val supportedTypes = listOf(CompileFile.Type.Java)
    override val afterCompileOrderRange = CompileOrder.afterSource
    
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val outputs = task.files.flatMap { file ->
            generateCode(file.file, task.outputDir)
        }
        return CompileResult(task, task.files.map { Result.success(it) }, outputs)
    }
    
    private fun generateCode(file: File, outputDir: File): List<CompileOutput> {
        // 代码生成逻辑
        return emptyList()
    }
}
```

---

## 四、UI 数据结构

### 4.1 RunResult - 运行结果

**定义位置**: `RunResult.kt`

```kotlin
data class RunResult(
    val isGradleCompile: Boolean,
    val isCompileSuccess: Boolean,
    val isDeploySuccess: Boolean,
    val isNeedResetHasRun: Boolean = false,
) {
    companion object {
        val FAILED = RunResult(
            isGradleCompile = false, 
            isCompileSuccess = false, 
            isDeploySuccess = false
        )
    }
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `isGradleCompile` | Boolean | 是否为 Gradle 编译 |
| `isCompileSuccess` | Boolean | 编译是否成功 |
| `isDeploySuccess` | Boolean | 部署是否成功 |
| `isNeedResetHasRun` | Boolean | 是否需要重置运行状态 |

**使用场景**:
```kotlin
// 编译成功，部署成功
val successResult = RunResult(
    isGradleCompile = false,
    isCompileSuccess = true,
    isDeploySuccess = true
)

// 编译失败
val compileFailedResult = RunResult(
    isGradleCompile = false,
    isCompileSuccess = false,
    isDeploySuccess = false
)

// Gradle 编译
val gradleCompileResult = RunResult(
    isGradleCompile = true,
    isCompileSuccess = true,
    isDeploySuccess = true,
    isNeedResetHasRun = true
)

// 使用预定义的失败结果
val failedResult = RunResult.FAILED
```

### 4.2 BuildChangesConfirmResult - 构建确认结果

**定义位置**: `BuildChangesConfirmResult.kt`

```kotlin
enum class BuildChangesConfirmResult {
    FIND_CHANGE,    // 发现变更
    IGNORE_CHANGE,  // 忽略变更
    CANCEL,         // 取消
    FALLBACK        // 降级
}
```

**枚举值说明**:

| 枚举值 | 说明 | 用途 |
|--------|------|------|
| `FIND_CHANGE` | 发现变更 | 用户确认发现变更，继续编译 |
| `IGNORE_CHANGE` | 忽略变更 | 用户选择忽略变更，跳过编译 |
| `CANCEL` | 取消 | 用户取消操作 |
| `FALLBACK` | 降级 | 降级到 Gradle 编译 |

**使用场景**:
```kotlin
fun confirmBuildChanges(changes: List<File>): BuildChangesConfirmResult {
    if (changes.isEmpty()) {
        return BuildChangesConfirmResult.IGNORE_CHANGE
    }
    
    val userChoice = showConfirmDialog(
        "发现 ${changes.size} 个文件变更，是否继续编译？",
        options = listOf("继续", "忽略", "取消", "使用 Gradle")
    )
    
    return when (userChoice) {
        0 -> BuildChangesConfirmResult.FIND_CHANGE
        1 -> BuildChangesConfirmResult.IGNORE_CHANGE
        2 -> BuildChangesConfirmResult.CANCEL
        3 -> BuildChangesConfirmResult.FALLBACK
        else -> BuildChangesConfirmResult.CANCEL
    }
}
```

---

## 五、设计亮点总结

### 5.1 自定义编译器扩展

| 亮点 | 说明 |
|------|------|
| **SPI 机制** | 使用 Java ServiceLoader 实现插件化 |
| **动态加载** | 运行时动态加载自定义编译器 JAR |
| **HTTP 下载** | 支持从 HTTP URL 下载编译器 |
| **MD5 校验** | 确保下载的 JAR 文件完整性 |
| **自动更新** | 自动检测和更新编译器版本 |
| **隔离加载** | 使用 URLClassLoader 隔离加载 |

### 5.2 编译器管理

| 亮点 | 说明 |
|------|------|
| **多路径支持** | 支持绝对路径、相对路径、HTTP URL |
| **自动清理** | 自动清理过期的编译器 JAR |
| **懒加载** | 仅在需要时加载编译器 |
| **异步下载** | 异步下载编译器，不阻塞主流程 |
| **错误容错** | 下载失败时不影响主流程 |

### 5.3 UI 数据结构

| 亮点 | 说明 |
|------|------|
| **简洁明了** | 数据结构简洁，易于理解 |
| **类型安全** | 使用枚举和数据类，类型安全 |
| **预定义常量** | 提供常用的预定义值 |
| **易于扩展** | 易于添加新的字段和枚举值 |

---

## 六、自定义编译器开发指南

### 6.1 开发步骤

**1. 创建 Gradle 项目**:
```gradle
plugins {
    kotlin("jvm") version "1.9.23"
}

dependencies {
    compileOnly("com.sickworm.jugg:main:1.0.0") // Jugg 主模块
}
```

**2. 实现 ICompilerCreator**:
```kotlin
class MyCompilerCreator : ICompilerCreator {
    override fun create(context: ICompileContext, parent: Disposable): ICompiler {
        return MyCompiler(context, parent)
    }
}
```

**3. 实现自定义编译器**:
```kotlin
class MyCompiler(
    context: ICompileContext,
    parent: Disposable,
) : BaseCompiler(context, parent) {
    
    override val supportedTypes = listOf(CompileFile.Type.Java)
    override val beforeCompileOrderRange = CompileOrder.beforeSource
    
    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // 编译逻辑
        return CompileResult(task, details, outputs)
    }
}
```

**4. 创建 SPI 配置**:
```
META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
```

**5. 打包和发布**:
```bash
./gradlew jar
# 上传到 HTTP 服务器或放到项目 libs 目录
```

**6. 配置到 Jugg**:
```json
{
  "customCompilers": [
    {
      "jarFileName": "my-compiler.jar",
      "path": "http://example.com/my-compiler.jar",
      "md5": "..."
    }
  ]
}
```

### 6.2 最佳实践

**1. 编译器顺序**:
- 使用 `beforeCompileOrderRange` 和 `afterCompileOrderRange` 控制执行顺序
- 代码检查编译器应在源码编译之前（`beforeSource`）
- 代码生成编译器应在源码编译之后（`afterSource`）

**2. 错误处理**:
- 使用 `Result.success()` 和 `Result.failure()` 返回结果
- 提供详细的错误信息（行号 + 错误消息）
- 捕获异常并转换为 `CompileError`

**3. 日志输出**:
- 使用 `logger.debug()` 输出调试信息
- 使用 `logger.info()` 输出重要信息
- 使用 `logger.warn()` 输出警告信息
- 使用 `logger.error()` 输出错误信息

**4. 性能优化**:
- 避免重复计算，使用缓存
- 仅处理变更的文件
- 使用并发处理（如果适用）

**5. 资源管理**:
- 实现 `dispose()` 方法释放资源
- 使用 `use` 块自动关闭资源
- 避免内存泄漏

---

## 七、总结

### 7.1 自定义编译器系统

**核心功能**:
- 支持动态加载自定义编译器 JAR
- 支持从 HTTP URL 下载编译器
- MD5 校验确保文件完整性
- 使用 ServiceLoader 实现插件化
- 自动更新和清理过期编译器

**关键技术**:
- Java ServiceLoader SPI 机制
- URLClassLoader 动态加载
- HTTP 文件下载
- MD5 校验
- 异步下载

### 7.2 UI 数据结构

**核心功能**:
- `RunResult`: 运行结果数据结构
- `BuildChangesConfirmResult`: 构建确认结果枚举

**使用场景**:
- 编译和部署结果传递
- 用户交互确认
- 状态管理

---

**文档状态**: ✅ 已完成  
**阶段 2 状态**: ✅ 已完成所有编译模块文档  
**下一步**: 开始阶段 3 - 部署模块分析
