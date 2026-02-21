# Jugg 编译系统 - DataBinding/ViewBinding 编译器

---

## 一、DataBinding/ViewBinding 概览

### 1.1 编译器架构

Jugg DataBinding/ViewBinding 编译系统采用**两阶段编译**策略：

```
布局文件 (layout/*.xml)
    ↓
DataBindingGenBaseClassesCompiler (阶段1)
    ├─ 分割布局文件 (DataBinding)
    ├─ 生成 ViewBinding 类 (XXXBinding.java)
    └─ 生成布局信息文件 (layout-xxx.xml)
    ↓
DataBindingGenMapperCompiler (阶段2, 仅 DataBinding)
    ├─ 运行注解处理器 (KAPT)
    ├─ 生成 DataBindingImpl 类
    ├─ 生成 BR 类
    ├─ 生成 Mapper 代理
    └─ 增量合并 BR 常量
    ↓
部署产物 (Java 文件 + 分割后的 XML)
```

### 1.2 编译器列表

| 编译器 | 输入类型 | 输出类型 | 职责 |
|--------|---------|---------|------|
| **DataBindingGenBaseClassesCompiler** | Resource | Java/ResXml | 生成 ViewBinding 类和分割布局文件 |
| **DataBindingGenMapperCompiler** | Resource | Java/ResXml | 生成 DataBinding 实现类和 Mapper |
| **DataBindingArgsManager** | - | - | 管理 DataBinding 编译参数 |
| **LayoutIncludeAnalyzer** | - | - | 分析布局 include 关系 |
| **DataBindingClasspathHelper** | - | - | 管理 DataBinding Classpath |
| **DataBindingTemplates** | - | - | DataBinding 代码模板 |
| **LogFileMerger** | - | - | 合并日志文件 |
| **MergingFileLookup** | - | - | 文件查找助手 |

### 1.3 ViewBinding vs DataBinding

| 特性 | ViewBinding | DataBinding |
|------|-------------|-------------|
| **启用方式** | `viewBinding { enabled = true }` | `dataBinding { enabled = true }` |
| **布局标签** | 无需特殊标签 | 需要 `<layout>` 根标签 |
| **生成类** | XXXBinding.java | XXXBinding.java + XXXBindingImpl.java |
| **注解处理** | 不需要 | 需要 KAPT |
| **BR 类** | 不生成 | 生成 BR.java |
| **Mapper** | 不生成 | 生成 DataBinderMapperImpl.java |
| **性能** | 更快 | 较慢（需要注解处理） |

---

## 二、DataBindingArgsManager - 参数管理器

### 2.1 核心职责

**定义位置**: `DataBindingArgsManager.kt`

| 职责 | 说明 |
|------|------|
| **目录管理** | 管理 DataBinding 编译的所有输入输出目录 |
| **参数配置** | 配置 DataBinding 编译参数 |
| **增量支持** | 管理增量编译的中间文件 |
| **Gradle 兼容** | 兼容 Gradle 编译目录结构 |

### 2.2 关键属性

**基础配置**:
```kotlin
val isJava = false                          // 是否为 Java 项目
val isUseAndroidX = true                    // 使用 AndroidX
val isUseViewBinding = isUseViewBinding(moduleInfo)  // 是否启用 ViewBinding
val isUseDataBinding = isUseDataBinding(moduleInfo)  // 是否启用 DataBinding
val isIncremental = false                   // 增量编译（暂未启用）
val packageName = context.getModulePackageName(moduleInfo) ?: ""
```

**ViewBinding 目录**:
```kotlin
// 生成的 ViewBinding 类输出目录
val dataBindingSourcesOutputDir = 
    dir(tempCompileDir, "generated/data_binding_base_class_source_out/${moduleInfo.buildVariant}/out")

// 分割后的布局文件目录
val dataBindingStrippedXmlDir = 
    dir(tempCompileDir, "intermediates/incremental/${moduleInfo.buildVariant}/merge${moduleInfo.buildVariant.camel}/stripped.dir")

// 布局信息文件目录
val tempDataBindingLayoutXmlDir = 
    dir(tempCompileDir, "intermediates/data_binding_layout_info_type_merge/${moduleInfo.buildVariant}/out")

// 依赖类目录
val dependencyClassesFolders: List<File> = listOf(
    incrementalDependencyClassesFolder,
    moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("out"),
    moduleInfo.buildPathInfo.dataBindingInfoDir.resolve("dataBindingGenBaseClasses${moduleInfo.buildVariant.camel}/out"),
    moduleInfo.buildPathInfo.dataBindingDependencyInfoDir,
).filter { it.exists() }
```

**DataBinding 目录**:
```kotlin
// KAPT 输出目录
val dataBindingKaptOutputDir = "other/kapt/output"

// BR 类文件
val currentIncrementalLibraryBrFile = File(dataBindingSourcesOutputDir, libraryBrRelativePath)
val currentIncrementalAppBrFile = File(dataBindingSourcesOutputDir, appBrRelativePath)

// Mapper 文件
val dataBindingMapperRelativePath = "$packagePath/DataBinderMapperImpl.java"
val mapperDir = dir(tempCompileDir, "other/mapper")
val dataBindingMapperDelegateFile = file(mapperDir, "DataBinderMapperImpl.java")
val dataBindingMapperFullFile = file(mapperDir, "DataBinderMapperImpl_Full.java")

// 增量 Mapper 计数
val databindingIncCount = context.deployedFiles.count {
    val isIncMapper = it.file.nameWithoutExtension.startsWith("DataBinderMapperImpl_Inc_") &&
                      it.file.extension == "dex" &&
                      !it.file.nameWithoutExtension.contains("$")
    if (!isIncMapper) return@count false
    val isMyPackage = it.relativeFile.parentFile.path.replace("\\", "/") == packagePath
    return@count isMyPackage
}
```

### 2.3 Gradle 兼容

**布局信息目录选择**:
```kotlin
val gradleDataBindingLayoutXmlDir: File = CompilerUtils.matchGradleDir(listOf(
    // AGP 7.2.2 application module
    File(moduleInfo.buildPathInfo.applicationDataBindingIntoTypeDir, "out"),
    // AGP 8.4 application module
    File(moduleInfo.buildPathInfo.applicationDataBindingIntoTypeDir, "package${moduleInfo.buildVariant.camel}Resources/out"),
    // AGP 7.2.2 library module
    File(moduleInfo.buildPathInfo.libraryDataBindingIntoTypeDir, "out"),
    // AGP 8.4 library module
    File(moduleInfo.buildPathInfo.libraryDataBindingIntoTypeDir, "package${moduleInfo.buildVariant.camel}Resources/out"),
),
    default = tempDataBindingLayoutXmlDir,
)
```

**备份机制**:
```kotlin
// 备份 Gradle 目录，避免 Gradle 编译失败
val backupDataBindingLayoutXmlDir = context.backupGradleDir(gradleDataBindingLayoutXmlDir, dryRun = true)

fun reset() {
    tempCompileDir.deleteRecursively()
    context.backupGradleDir(gradleDataBindingLayoutXmlDir) // 备份到 backupDataBindingLayoutXmlDir
}
```

### 2.4 检测逻辑

**ViewBinding 检测**:
```kotlin
fun isUseViewBinding(moduleInfo: ModuleInfo): Boolean {
    if (moduleInfo.isUseViewBinding == true) return true
    
    val gradleViewBindingOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/data_binding_base_class_source_out")
    val isHasViewBinding = gradleViewBindingOutputDir.exists()
    return isHasViewBinding
}
```

**DataBinding 检测**:
```kotlin
fun isUseDataBinding(moduleInfo: ModuleInfo, xmlFile: List<File>? = null): Boolean {
    if (moduleInfo.isUseDataBinding == true) return true
    
    val gradleKaptOutputDir = File(moduleInfo.buildPathInfo.buildDir, "generated/source/kapt/${moduleInfo.buildVariant}")
    val gradleDataBindingOutputGuessDir = File(gradleKaptOutputDir, "androidx/databinding")
    val isHasDataBindingOutput = gradleDataBindingOutputGuessDir.exists()
    if (!isHasDataBindingOutput) return false
    
    if (xmlFile.isNullOrEmpty()) {
        return true
    }
    
    return xmlFile.any(::guessXmlFileHasDataBinding)
}

private fun guessXmlFileHasDataBinding(xmlFile: File): Boolean {
    if (!xmlFile.exists()) return false
    return xmlFile.readText().contains("<layout")
}
```

---

## 三、DataBindingGenBaseClassesCompiler - 基类生成编译器

### 3.1 核心职责

**定义位置**: `DataBindingGenBaseClassesCompiler.kt`

| 职责 | 说明 |
|------|------|
| **布局分割** | 分割 DataBinding 布局文件（移除 `<layout>` 标签） |
| **ViewBinding 生成** | 生成 ViewBinding 类（XXXBinding.java） |
| **布局信息生成** | 生成布局信息文件（layout-xxx.xml） |
| **增量存储** | 存储增量编译所需的中间文件 |

**支持类型**: `Resource`

### 3.2 编译流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    // 1. 检查是否启用 DataBinding/ViewBinding
    val argsManager = DataBindingArgsManager(context, module)
    if (!argsManager.isUseViewBinding && !argsManager.isUseDataBinding) {
        return CompileResult(task, emptyList(), emptyList())
    }
    
    // 2. 重置临时目录
    argsManager.reset()
    
    try {
        // 3. 分割布局文件
        val splitFiles = splitLayoutXml(argsManager, task.files)
        
        // 4. 生成 ViewBinding 类
        generateBaseClasses(argsManager, splitFiles)
        
        // 5. 复制到 Gradle 目录
        copyToGradleDir(argsManager)
        
        // 6. 收集输出
        return getOutput(task, argsManager, module)
    } catch (e: Exception) {
        logger.warn("Compile DataBinding failed: ${e.message}")
        return CompileResult(task, task.files.map { Result.failure(...) }, emptyList())
    }
}
```

### 3.3 布局分割

**方法**: `splitLayoutXml(argsManager, changedXmlFiles)`

```kotlin
private fun splitLayoutXml(argsManager: DataBindingArgsManager, changedXmlFiles: List<CompileFile>): List<File> {
    val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)
    val mergingFileLookupInstance = MergingFileLookup(argsManager.blameLogDir)
    val layoutXmlProcessor = LayoutXmlProcessor(
        argsManager.packageName, 
        gradleFileWriter, 
        mergingFileLookupInstance, 
        argsManager.isUseAndroidX
    )
    
    changedXmlFiles.forEach {
        val relativizableFile = RelativizableFile.fromAbsoluteFile(it.file, argsManager.dataBindingStrippedXmlDir)
        val out = File(argsManager.dataBindingStrippedXmlDir, it.relativeFile.path)
        
        // 处理单个布局文件
        layoutXmlProcessor.processSingleFile(
            relativizableFile, 
            out, 
            argsManager.isUseViewBinding, 
            argsManager.isUseDataBinding
        )
        
        // 写入布局信息文件
        layoutXmlProcessor.writeLayoutInfoFiles(argsManager.tempDataBindingLayoutXmlDir, gradleFileWriter)
    }
    
    val splitFiles = argsManager.tempDataBindingLayoutXmlDir.listFiles()?.toList()
        ?: throw IllegalStateException("Layout info files not generated")
    
    return splitFiles
}
```

**布局分割示例**:

**原始布局** (`activity_main.xml`):
```xml
<layout xmlns:android="http://schemas.android.com/apk/res/android">
    <data>
        <variable name="user" type="com.example.User"/>
    </data>
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">
        <TextView
            android:text="@{user.name}"/>
    </LinearLayout>
</layout>
```

**分割后的布局** (`stripped.dir/layout/activity_main.xml`):
```xml
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:tag="layout/activity_main_0">
    <TextView
        android:tag="binding_1"
        android:text="@{user.name}"/>
</LinearLayout>
```

**布局信息文件** (`layout-activity_main-layout.xml`):
```xml
<Layout directory="layout" file="activity_main.xml" ...>
    <Variables>
        <variable name="user" type="com.example.User"/>
    </Variables>
    <Targets>
        <Target tag="layout/activity_main_0" view="LinearLayout">
            <Expressions/>
        </Target>
        <Target tag="binding_1" view="TextView">
            <Expressions>
                <Expression attribute="android:text" text="user.name"/>
            </Expressions>
        </Target>
    </Targets>
</Layout>
```

### 3.4 ViewBinding 类生成

**方法**: `generateBaseClasses(argsManager, splitFiles)`

```kotlin
private fun generateBaseClasses(argsManager: DataBindingArgsManager, splitFiles: List<File>) {
    val args = LayoutInfoInput.Args(
        outOfDate = splitFiles,
        removed = emptyList(),
        infoFolder = argsManager.tempDataBindingLayoutXmlDir,
        dependencyClassesFolders = argsManager.dependencyClassesFolders,
        artifactFolder = argsManager.artifactFolder,
        logFolder = argsManager.logFolder,
        packageName = argsManager.packageName,
        incremental = argsManager.isIncremental,
        v1ArtifactsFolder = argsManager.v1ArtifactsFolder,
        useAndroidX = argsManager.isUseAndroidX,
        enableViewBinding = argsManager.isUseViewBinding,
        enableDataBinding = argsManager.isUseDataBinding,
    )
    
    val layoutInfoInput = LayoutInfoInput(args)
    val baseDataBinder = BaseDataBinder(layoutInfoInput, getRPackage = null)
    
    val gradleFileWriter = DataBindingBuilder.GradleFileWriter(argsManager.dataBindingSourcesOutputDir.path)
    baseDataBinder.generateAll(gradleFileWriter)
}
```

**生成的 ViewBinding 类** (`ActivityMainBinding.java`):
```java
package com.example;

import androidx.viewbinding.ViewBinding;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ActivityMainBinding implements ViewBinding {
    private final LinearLayout rootView;
    public final TextView textView;
    
    private ActivityMainBinding(LinearLayout rootView, TextView textView) {
        this.rootView = rootView;
        this.textView = textView;
    }
    
    @Override
    public LinearLayout getRoot() {
        return rootView;
    }
    
    public static ActivityMainBinding inflate(LayoutInflater inflater) {
        // ...
    }
    
    public static ActivityMainBinding bind(View rootView) {
        // ...
    }
}
```

### 3.5 输出收集

```kotlin
private fun getOutput(task: CompileTask, argsManager: DataBindingArgsManager, module: ModuleInfo): CompileResult {
    // 1. 收集 Java 文件
    val sourceFiles = argsManager.dataBindingSourcesOutputDir
        .listFilesRecursively()
        .map {
            val outputDir = task.outputDir.resolve("java")
            val outputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, outputDir)
            outputFile.parentFile.mkdirs()
            it.copyTo(outputFile, overwrite = true)
            
            // 存储增量编译所需文件
            if (!argsManager.isUseDataBinding) {
                val generatedOutputFile = it.changeBaseDir(argsManager.dataBindingSourcesOutputDir, argsManager.incrementalBaseClassOutDir)
                it.copyTo(generatedOutputFile, overwrite = true)
            }
            
            CompileOutput(CompileOutput.Type.Java, outputFile, outputDir, relativeModule = module)
        }
    
    // 2. 收集分割后的 XML 文件（仅 DataBinding）
    var xmlFiles = emptyList<CompileOutput>()
    if (argsManager.isUseDataBinding) {
        xmlFiles = argsManager.dataBindingStrippedXmlDir
            .listFilesRecursively()
            .map {
                val outputDir = task.outputDir.resolve("res")
                val outputFile = it.changeBaseDir(argsManager.dataBindingStrippedXmlDir, outputDir)
                outputFile.parentFile.mkdirs()
                it.copyTo(outputFile, overwrite = true)
                CompileOutput(CompileOutput.Type.ResXml, outputFile, outputDir, relativeModule = module)
            }
    }
    
    // 3. 存储增量编译所需的 artifact 文件
    argsManager.artifactFolder.listFiles()?.forEach {
        val outputFile = File(argsManager.incrementalDependencyClassesFolder, it.name)
        it.copyTo(outputFile, overwrite = true)
    }
    
    return CompileResult(task, task.files.map { Result.success(it) }, sourceFiles + xmlFiles)
}
```

---

## 四、DataBindingGenMapperCompiler - Mapper 生成编译器

### 4.1 核心职责

**定义位置**: `DataBindingGenMapperCompiler.kt`

| 职责 | 说明 |
|------|------|
| **注解处理** | 运行 KAPT 生成 DataBindingImpl 类 |
| **BR 类生成** | 生成 BR.java 类 |
| **BR 增量合并** | 增量合并 BR 常量 |
| **Mapper 代理** | 生成 Mapper 代理类 |
| **Include 分析** | 分析布局 include 关系 |

**支持类型**: 基类默认（未显式覆写 `supportedTypes`）

### 4.2 编译流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    argsManager = DataBindingArgsManager(context, module)
    if (!argsManager.isUseDataBinding) {
        return CompileResult(task, emptyList(), emptyList())
    }
    
    try {
        // 1. 生成注解处理器触发文件
        generateAnnotationProcessorTrigger()
        
        // 2. 运行注解处理器
        runAnnotationProcessor(task, module)
        
        // 3. 生成增量 Mapper 代理
        generateIncrementalMapperHolder()
        
        // 4. 合并 Library BR
        mergeLibraryBr()
        
        // 5. 合并 App BR
        mergeAppBr()
        
        // 6. 收集输出
        return getOutput(task, module)
    } catch (e: Exception) {
        logger.warn("Compile DataBinding failed: ${e.message}")
        return CompileResult(task, task.files.map { Result.failure(...) }, emptyList())
    }
}
```

### 4.3 注解处理器触发

**生成触发文件**:
```kotlin
private fun generateAnnotationProcessorTrigger() {
    if (argsManager.isJava) {
        // Java 项目：生成 DataBindingInfo.java
        val triggerFile = argsManager.dataBindingKaptProcessorTrigger
        val annotation = if (argsManager.isUseAndroidX) 
            "androidx.databinding.BindingBuildInfo" 
        else 
            "android.databinding.BindingBuildInfo"
        
        val classString = StringBuilder()
            .appendLine("package ${argsManager.packageName};")
            .appendLine("@$annotation")
            .appendLine("public class DataBindingInfo {}")
        
        triggerFile.writeText(classString.toString())
    } else {
        // Kotlin 项目：生成 DataBindingTrigger.kt
        val ktSourceTriggerFile = argsManager.dataBindingKaptSourceTrigger
        val content = StringBuilder()
            .appendLine("package ${argsManager.packageName}")
            .appendLine("class DataBindingIncTrigger {}")
        
        ktSourceTriggerFile.writeText(content.toString())
    }
}
```

### 4.4 运行注解处理器

**KAPT 编译**:
```kotlin
private fun runAnnotationProcessor(task: CompileTask, module: ModuleInfo) {
    // 1. 准备源文件
    val source = mutableListOf<CompileFile>()
    if (argsManager.isJava) {
        source.add(CompileFile(CompileFile.Type.Java, argsManager.dataBindingKaptProcessorTrigger, ...))
    } else {
        source.add(CompileFile(CompileFile.Type.Kotlin, argsManager.dataBindingKaptSourceTrigger, ...))
    }
    
    // 2. 分析 include 关系
    val includeLayoutInfoFiles = LayoutIncludeAnalyzer(argsManager, logger).findAllIncludePath(task.files)
    includeLayoutInfoFiles.forEach {
        val targetFile = it.changeBaseDir(it.parentFile, argsManager.tempDataBindingLayoutXmlDir)
        targetFile.parentFile.mkdirs()
        it.copyTo(targetFile, overwrite = true)
    }
    
    // 3. 准备注解处理器选项
    val apOptions = prepareAnnotationProcessorOptions(module)
    
    // 4. 准备 Classpath
    val classpath = DataBindingClasspathHelper.getClasspath(context, module, logger)
    classpath.adapterJson.forEach {
        val targetFile = File(argsManager.dataBindingDependencyArtifacts, it.name)
        it.copyTo(targetFile, overwrite = true)
    }
    
    // 5. 运行 KAPT
    val kaptTask = CompileTask(files = source, outputDir = argsManager.dataBindingSourcesOutputDir, parentTask = task)
    val subContext = context.subContext(argsManager.dataBindingKaptOutputDir)
    val options = KotlinCompilerInvoker.Options(
        isEnableKapt = true,
        isCanAutoRetry = false,
        kaptOptions = apOptions,
        kaptDependencies = classpath.kaptDependencies,
        kotlinPlugins = classpath.kotlinPlugins,
        javaSourceDirs = listOf(argsManager.dataBindingSourcesOutputDir),
    )
    
    val kaptResult = KotlinCompilerInvoker.currentInstance.compile(subContext, module, kaptTask, logger, options)
    if (!kaptResult.isAllSuccess) {
        throw RuntimeException("Failed to compile annotation process task")
    }
}
```

**注解处理器选项**:
```kotlin
private fun prepareAnnotationProcessorOptions(module: ModuleInfo): Map<String, String> {
    val artifactType = when (module.moduleType) {
        ModuleInfo.Type.Application -> "APPLICATION"
        ModuleInfo.Type.DynamicFeature -> "FEATURE"
        else -> "LIBRARY"
    }
    
    return mapOf(
        "android.databinding.incremental" to "1",
        "android.databinding.minApi" to module.minSdkVersion.toString(),
        "android.databinding.classLogDir" to argsManager.dataBindingArtifactFolder.path,
        "android.databinding.aarOutDir" to argsManager.dataBindingAarOutDir.path,
        "android.databinding.enableDebugLogs" to "1",
        "android.databinding.dependencyArtifactsDir" to argsManager.dataBindingDependencyArtifacts.path,
        "android.databinding.sdkDir" to context.androidHome.path,
        "android.databinding.enableForTests" to "0",
        "android.databinding.enableV2" to "1",
        "android.databinding.modulePackage" to argsManager.packageName,
        "android.databinding.artifactType" to artifactType,
        "android.databinding.isTestVariant" to "0",
        "android.databinding.baseFeatureInfoDir" to argsManager.dataBindingBaseFeatureInfoDir.path,
        "android.databinding.printEncodedErrorLogs" to "1",
        "android.databinding.layoutInfoDir" to argsManager.tempDataBindingLayoutXmlDir.path,
        "useAndroidX" to argsManager.isUseAndroidX.toString(),
    )
}
```

### 4.5 BR 类增量合并

**Library BR 合并**:
```kotlin
private fun mergeLibraryBr() {
    val lastLibraryBrFile = argsManager.gradleLibraryBrFile
    val currentIncrementalLibraryBrFile = argsManager.currentIncrementalLibraryBrFile
    
    if (!currentIncrementalLibraryBrFile.exists()) {
        logger.debug("skip, because current has no br file.")
        return
    }
    
    // 1. 读取旧 BR 文件
    val lastFieldsMap = createFieldsMapFromBrFile(lastLibraryBrFile)
    
    // 2. 读取新 BR 文件
    val currentIncrementalFieldsMap = createFieldsMapFromBrFile(currentIncrementalLibraryBrFile)
    
    // 3. 合并常量
    var index = lastFieldsMap.size
    currentIncrementalFieldsMap.forEach { (key, _) ->
        if (!lastFieldsMap.containsKey(key)) {
            lastFieldsMap[key] = index++.toString()
        }
    }
    
    // 4. 生成新 BR 文件
    val newLibraryBrFileContent = StringBuilder()
        .append("package com.android.databinding.library.baseAdapters;\n\n")
        .append("public class BR {\n\n")
        .apply {
            lastFieldsMap.forEach { (key, value) ->
                append("public static final int $key = $value;\n\n")
            }
        }
        .append("}")
    
    // 5. 写入文件
    currentIncrementalLibraryBrFile.writeText(newLibraryBrFileContent.toString())
    lastLibraryBrFile.writeText(newLibraryBrFileContent.toString())
}
```

**BR 文件解析**:
```kotlin
private fun createFieldsMapFromBrFile(brFile: File): MutableMap<String, String> {
    val lastFieldsMap = LinkedHashMap<String, String>()
    brFile.forEachLine {
        if (it.trim().startsWith("public static final int")) {
            val content = it.trim().replace("public static final int", "").trim().replace(";", "")
            val splits = content.split(" = ")
            lastFieldsMap[splits[0]] = splits[1]
        }
    }
    return lastFieldsMap
}
```

**BR 合并示例**:

**旧 BR 文件**:
```java
package com.example;

public class BR {
    public static final int _all = 0;
    public static final int user = 1;
}
```

**新 BR 文件**:
```java
package com.example;

public class BR {
    public static final int _all = 0;
    public static final int product = 1;
}
```

**合并后 BR 文件**:
```java
package com.example;

public class BR {
    public static final int _all = 0;
    public static final int user = 1;
    public static final int product = 2;
}
```

### 4.6 Mapper 增量代理

**生成增量 Mapper**:
```kotlin
private fun generateIncrementalMapperHolder() {
    // 1. 获取当前生成的 Mapper 文件
    val currentDataBinderMapperImplFile = File(argsManager.dataBindingSourcesOutputDir, argsManager.dataBindingMapperRelativePath)
    if (!currentDataBinderMapperImplFile.exists()) {
        throw RuntimeException("dataBinderMapper file not exist")
    }
    
    // 2. 创建增量 Mapper 文件
    val index = argsManager.databindingIncCount + 1
    val newName = "DataBinderMapperImpl_Inc_$index"
    val targetIncFile = File(argsManager.mapperDir, "$newName.java")
    val content = currentDataBinderMapperImplFile.readText().replaceFirst("DataBinderMapperImpl", newName)
    targetIncFile.writeText(content)
    
    val targetFileCopyToOut = File(currentDataBinderMapperImplFile.parentFile, targetIncFile.name)
    targetIncFile.copyTo(targetFileCopyToOut)
    currentDataBinderMapperImplFile.delete()
    
    // 3. 创建 Mapper Holder
    val templates = DataBindingTemplates(argsManager.isUseAndroidX)
    val allIncMapper = (1 .. index).map { "DataBinderMapperImpl_Inc_$it" }
    val incMapperArrays = StringBuilder()
    allIncMapper.forEach {
        incMapperArrays.append("\n                                new ${argsManager.packageName}.${it}(),")
    }
    val holderContent = templates.holderTemplate
        .replace("_package_name_holder_", argsManager.packageName)
        .replace("_inc_mapper_array_holder_", incMapperArrays.toString())
    
    val allIncMapperHolderJavaFile = File(currentDataBinderMapperImplFile.parentFile, "DataBinderMapper_IncrementalHolder.java")
    allIncMapperHolderJavaFile.writeText(holderContent)
    
    // 4. 创建代理 Mapper
    val delegateMapperFile = argsManager.dataBindingMapperDelegateFile
    if (!delegateMapperFile.exists()) {
        val delegateMapperContent = templates.mapperContentTemplate.replace("_package_name_holder_", argsManager.packageName)
        delegateMapperFile.writeText(delegateMapperContent)
    }
    
    val targetDelegateMapperFile = File(currentDataBinderMapperImplFile.parentFile, delegateMapperFile.name)
    delegateMapperFile.copyTo(targetDelegateMapperFile)
    
    // 5. 创建完整 Mapper
    val fullMapperFile = argsManager.dataBindingMapperFullFile
    if (!fullMapperFile.exists()) {
        DataBindingTemplates(argsManager.isUseAndroidX).generateFullMapperFile(argsManager.gradleMapperFile, fullMapperFile)
    }
    val targetFullMapperFile = File(currentDataBinderMapperImplFile.parentFile, fullMapperFile.name)
    fullMapperFile.copyTo(targetFullMapperFile)
}
```

**Mapper 增量架构**:

```
首次编译:
DataBinderMapperImpl_Inc_1.java (实际实现)
DataBinderMapper_IncrementalHolder.java (持有 Inc_1)
DataBinderMapperImpl.java (代理到 Holder)

第二次编译:
DataBinderMapperImpl_Inc_1.java (保留)
DataBinderMapperImpl_Inc_2.java (新增)
DataBinderMapper_IncrementalHolder.java (持有 Inc_1, Inc_2)
DataBinderMapperImpl.java (代理到 Holder)

第三次编译:
DataBinderMapperImpl_Inc_1.java (保留)
DataBinderMapperImpl_Inc_2.java (保留)
DataBinderMapperImpl_Inc_3.java (新增)
DataBinderMapper_IncrementalHolder.java (持有 Inc_1, Inc_2, Inc_3)
DataBinderMapperImpl.java (代理到 Holder)
```

---

## 五、LayoutIncludeAnalyzer - Include 关系分析器

### 5.1 核心职责

**定义位置**: `LayoutIncludeAnalyzer.kt`

| 职责 | 说明 |
|------|------|
| **Include 检测** | 检测布局文件中的 `<include>` 标签 |
| **递归分析** | 递归查找所有被 include 的布局 |
| **依赖模块查找** | 在依赖模块中查找布局信息文件 |

### 5.2 分析流程

```kotlin
fun findAllIncludePath(compileDataBindingXmlFiles: List<CompileFile>): List<File> {
    // 1. 转换为布局信息文件
    var layoutInfoFiles = mutableListOf<File>()
    compileDataBindingXmlFiles.forEach { file ->
        val subLayoutInfoFiles = findLayoutInfoFileByLayoutName(file.file.nameWithoutExtension, file.file.parentFile.name)
        layoutInfoFiles.addAll(subLayoutInfoFiles)
    }
    
    // 2. 递归查找 include 的布局信息文件
    val result = mutableListOf<File>()
    while (layoutInfoFiles.isNotEmpty()) {
        val includeLayoutNames = mutableListOf<String>()
        layoutInfoFiles.forEach { file ->
            findIncludeLayouts(file, XmlParser().parse(file).node, includeLayoutNames)
        }
        
        val newIncludeLayoutInfoFiles = mutableListOf<File>()
        includeLayoutNames.forEach { name ->
            val subLayoutInfoFiles = findLayoutInfoFileByLayoutName(name)
            subLayoutInfoFiles.forEach { layoutInfoFile ->
                if (layoutInfoFile !in result) { // 避免死循环
                    newIncludeLayoutInfoFiles.add(layoutInfoFile)
                }
            }
        }
        
        result.addAll(newIncludeLayoutInfoFiles)
        layoutInfoFiles = newIncludeLayoutInfoFiles
    }
    
    return result
}
```

### 5.3 Include 检测

```kotlin
private fun findIncludeLayouts(layoutInfoFile: File, node: Element, result: MutableList<String>) {
    val targetNodes = node
        .childNodes.find { it is Element && it.tagName == "Targets" }
        ?.childNodes
    
    if (targetNodes == null) {
        logger.warn("$layoutInfoFile has no \"Targets\" node")
        return
    }
    
    targetNodes.forEach { targetNode ->
        if (targetNode !is Element) return@forEach
        
        val includeLayout = targetNode.getAttribute("include")
        if (includeLayout.isNotEmpty()) {
            logger.debug("found include layout $includeLayout in $layoutInfoFile")
            result.add(includeLayout)
        }
    }
}
```

### 5.4 布局信息文件查找

```kotlin
private fun findLayoutInfoFileByLayoutName(layoutName: String, parentFileName: String? = null): List<File> {
    // 1. 在当前模块查找
    val finalLayoutName = layoutName + if (parentFileName != null) "-$parentFileName.xml" else "-"
    val layoutInfoFiles = argsManager.backupDataBindingLayoutXmlDir.listFiles()?.filter {
        it.name.startsWith(finalLayoutName)
    }
    if (!layoutInfoFiles.isNullOrEmpty()) {
        return layoutInfoFiles
    }
    
    // 2. 在依赖模块查找
    val dependentModules = argsManager.moduleInfo.moduleDependencies
    dependentModules.forEach { dependantModule ->
        val subModuleInfo = argsManager.context.modules[dependantModule.moduleName] ?: return@forEach
        val subArgsManager = DataBindingArgsManager(argsManager.context, subModuleInfo)
        val layoutXmlDir = if (subArgsManager.backupDataBindingLayoutXmlDir.exists()) {
            subArgsManager.backupDataBindingLayoutXmlDir
        } else {
            subArgsManager.gradleDataBindingLayoutXmlDir
        }
        val subLayoutInfoFile = layoutXmlDir.listFiles()?.filter {
            it.name.startsWith(finalLayoutName)
        }
        if (!subLayoutInfoFile.isNullOrEmpty()) {
            return subLayoutInfoFile
        }
    }
    
    return emptyList()
}
```

---

## 六、设计亮点总结

### 6.1 增量编译优化

| 优化点 | 说明 |
|--------|------|
| **BR 增量合并** | 仅追加新常量，保持旧常量索引不变 |
| **Mapper 增量代理** | 生成增量 Mapper，避免重新编译所有布局 |
| **布局信息缓存** | 缓存布局信息文件，加速 include 分析 |
| **Gradle 目录复用** | 复用 Gradle 编译的布局信息文件 |

### 6.2 Gradle 兼容性

| 兼容点 | 说明 |
|--------|------|
| **AGP 版本兼容** | 支持 AGP 7.2.2 ~ 8.4 |
| **目录结构适配** | 自动选择正确的 Gradle 目录结构 |
| **备份机制** | 备份 Gradle 目录，避免编译失败 |
| **增量存储** | 存储增量编译所需的中间文件 |

### 6.3 容错设计

| 容错点 | 说明 |
|--------|------|
| **Include 循环检测** | 避免 include 循环导致死循环 |
| **依赖模块查找** | 在依赖模块中查找布局信息文件 |
| **异常捕获** | 捕获异常并返回友好错误信息 |
| **日志详细** | 详细的日志输出，方便调试 |

### 6.4 性能优化

| 优化点 | 说明 |
|--------|------|
| **仅处理变更文件** | 仅处理变更的布局文件 |
| **增量 Mapper** | 避免重新编译所有布局 |
| **Classpath 聚合** | 聚合父模块依赖并补充 databinding-adapters setter_store |
| **串行处理** | 以顺序流程处理布局与 include 递归 |

---

## 七、待深入分析的模块

| 模块 | 文件数 | 说明 |
|------|--------|------|
| `compiler/manifest/` | ~3 | Manifest 增量编译 |
| `compiler/obfuscation/` | ~3 | R8 混淆支持 |

**下一步**: 阅读 `compiler/manifest/*.kt` 和 `compiler/obfuscation/*.kt`，完成编译模块分析。

---

**文档状态**: ✅ 已完成  
**下一步**: 阅读阶段 2.5 - compiler/manifest/*.kt + compiler/obfuscation/*.kt
