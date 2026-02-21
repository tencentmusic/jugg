# Jugg 编译系统 - 资源编译器

---

## 一、资源编译器概览

### 1.1 编译器架构

Jugg 资源编译系统采用**多阶段编译**策略，将资源编译分为四个阶段：

```
资源文件 (XML/PNG/...)
    ↓
ResourceCompiler (AAPT2 Compile)
    ↓
Flat 文件 (.flat)
    ↓
ArscCompiler (AAPT2 Link)
    ↓
部署产物 (resources.arsc + 编译后的资源 + R.java)
```

### 1.2 编译器列表

| 编译器 | 输入类型 | 输出类型 | 职责 |
|--------|---------|---------|------|
| **ResourceCompiler** | Resource | Flat/Java | 资源编译为 Flat 文件，支持 DataBinding/ViewBinding |
| **ResourceOverlayCompiler** | Resource/AndroidManifest | Res/Java | 资源增量编译协调器 |
| **ArscCompiler** | Flat/AndroidManifest | Res/Java | Flat 文件链接为可部署资源 |
| **AssetOverlayCompiler** | Asset/NativeLib | Asset/NativeLib | Assets 和 Native 库复制 |
| **RDexForSubmoduleCompiler** | DexToChangePackageName | Dex | 为子模块生成 R.dex |
| **RJavaFixer** | - | - | 修复 R.java 常量过多问题 |
| **DexPackageRenamer** | - | - | 修改 Dex 文件包名 |
| **RPackageReader** | - | - | 读取 Manifest 包名 |
| **StyleableFileGenerator** | - | - | 生成 Styleable 文件 |
| **DirToFileMapHelper** | - | - | 目录到文件映射助手 |

---

## 二、ResourceCompiler - 资源编译器

### 2.1 核心职责

**定义位置**: `ResourceCompiler.kt`

| 职责 | 说明 |
|------|------|
| **AAPT2 Compile** | 调用 AAPT2 将资源文件编译为 Flat 文件 |
| **DataBinding 支持** | 处理 DataBinding 布局文件 |
| **ViewBinding 支持** | 处理 ViewBinding 布局文件 |
| **目录编译** | 支持编译整个资源目录 |

**支持类型**: `Resource`

### 2.2 编译流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    // 1. 分组：单文件 vs 目录
    val singleResCompileSet = ResCompileSet(...)
    val dirResCompileSet = dirToFilesMap.map { ... }
    
    // 2. 编译每个分组
    val compileResultSet = compileFilesSet.map {
        compileResSet(it, module)
    }
    
    return compileResultSet.reduce { acc, compileResult -> acc + compileResult }
}
```

**分组策略**:
- **单文件**: 直接编译
- **目录**: 使用 MD5 哈希作为输出目录名，避免冲突

### 2.3 DataBinding/ViewBinding 处理

**流程**:
```kotlin
private fun compileResSet(resCompileSet: ResCompileSet, module: ModuleInfo): CompileResult {
    // 1. 处理 DataBinding/ViewBinding
    val dataBindingResult = processDataBinding(resCompileSet, module)
    
    // 2. 获取分割后的布局文件
    val splitLayoutFiles = dataBindingResult.outputs.filter { it.type == CompileOutput.Type.ResXml }
    val javaFiles = dataBindingResult.outputs.filter { it.type == CompileOutput.Type.Java }
    
    // 3. 替换原始布局文件
    val processedResCompileSet = updateResCompileSet(resCompileSet, splitLayoutFiles)
    
    // 4. AAPT2 编译
    val flatResult = aapt2Compile(processedResCompileSet)
    
    return flatResult.copy(outputs = flatResult.outputs + javaFiles)
}
```

**DataBinding 检测**:
```kotlin
private fun processDataBinding(resCompileSet: ResCompileSet, module: ModuleInfo): CompileResult {
    // 1. 提取 layout 文件
    val layoutFiles = resCompileSet.compileFileMap.flatMap { (compileFile, xmlFiles) ->
        xmlFiles.filter { it.parentFile.name.startsWith("layout") }
    }
    
    // 2. ViewBinding 处理
    val viewBindingResult = dataBindingGenBaseClassesCompiler.compile(databindingTask)
    
    // 3. DataBinding 检测
    val isRunDataBinding = DataBindingArgsManager.isUseDataBinding(module, files)
    if (!isRunDataBinding) {
        return viewBindingResult
    }
    
    // 4. DataBinding 处理
    val dataBindingResult = dataBindingGenMapperCompiler.compile(databindingTask)
    return dataBindingResult
}
```

### 2.4 AAPT2 编译

**命令构建**:
```kotlin
private fun aapt2Compile(resCompileSet: ResCompileSet): CompileResult {
    val filesString = resCompileSet.compileFiles.joinToString(" ") {
        it.absolutePath
    }
    
    // --legacy: 兼容多重替换格式
    val command = "compile --legacy -o ${resCompileSet.outputDir} $filesString"
    val result = aapt2Invoker.invoke(command)
    
    // 检查输出文件
    val outputs = resCompileSet.compileFiles.map {
        val fileName = it.flatFileName
        val outputFile = File(resCompileSet.outputDir, fileName)
        CompileOutput(CompileOutput.Type.Flat, outputFile, resCompileSet.outputDir)
    }
    
    return CompileResult(resCompileSet.originTask, details, outputs)
}
```

**Flat 文件命名规则**:
```kotlin
private val File.flatFileName: String get() {
    val folderName = file.parentFile!!.name
    val extension = if (folderName.startsWith("values")) ".arsc"
        else if (file.extension.isEmpty()) ""
        else ".${file.extension}"
    return "${folderName}_${file.nameWithoutExtension}$extension.flat"
}
```

**示例**:
- `layout/activity_main.xml` → `layout_activity_main.xml.flat`
- `values/strings.xml` → `values_strings.arsc.flat`

---

## 三、ResourceOverlayCompiler - 资源增量编译协调器

### 3.1 核心职责

**定义位置**: `ResourceOverlayCompiler.kt`

| 职责 | 说明 |
|------|------|
| **编译协调** | 协调 ResourceCompiler 和 ArscCompiler |
| **Manifest 合并** | 合并 AndroidManifest.xml |
| **资源过滤** | 过滤不需要部署的资源 |
| **APK 分组编译** | 支持多 APK 项目 |

**支持类型**: `Resource`, `AndroidManifest`

### 3.2 编译流程

```kotlin
override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
    // 1. 分离 Manifest 和资源文件
    val androidManifestTask = CompileTask(...)
    val resourceTask = CompileTask(...)
    
    // 2. 合并 AndroidManifest.xml
    var androidManifestResult = androidManifestCompiler.doApkCompile(androidManifestTask, apkFileUnit)
    
    // 3. 编译资源为 Flat 文件
    var resourceResult = resourceCompiler.compile(resourceTask)
    
    // 4. 链接 Flat 文件
    val arscTask = CompileTask(compileFiles, task.outputDir, task)
    val arscResult = arscCompiler.doApkCompile(arscTask, apkFileUnit)
    
    // 5. 过滤资源
    val finalOutputs = filterResources(arscResult.outputs, task.files, isNeedOutputManifest)
    
    return CompileResult(task, details, finalOutputs + databindingOutputs)
}
```

### 3.3 资源过滤

**目的**: 避免部署不必要的资源，减少 APK 重新打包

**过滤规则**:

| 资源类型 | 过滤条件 | 原因 |
|---------|---------|------|
| **Manifest.java** | 总是过滤 | Android Studio 也不生成 |
| **AndroidManifest.xml** | 无变更时过滤 | 避免触发 APK 重新打包 |
| **AAPT2 额外生成的资源** | 有覆盖文件时过滤 | 避免重复资源 |

**AAPT2 额外资源检测**:
```kotlin
private fun filterResources(...): List<CompileOutput> {
    val finalOverlays = resource.toMutableList()
    
    resourceNameToPathMap.forEach { (resourceName, outputs) ->
        outputs.forEach { output ->
            // 检查是否为 AAPT2 额外创建的资源
            val isCreateByAapt2 = !filePathSet.contains(relativePath)
            if (!isCreateByAapt2) return@forEach
            
            // 查找覆盖文件
            val overrideSourceXmlFiles = guessSourceXmlFiles.mapNotNull { compileFile ->
                val guessOverrideSourceXmlFile = File(compileFile.baseDir, relativePath)
                if (guessOverrideSourceXmlFile.exists()) {
                    return@mapNotNull guessOverrideSourceXmlFile
                } else {
                    return@mapNotNull null
                }
            }.toSet()
            
            if (overrideSourceXmlFiles.isNotEmpty()) {
                // 有覆盖文件，忽略 AAPT2 生成的资源
                finalOverlays.remove(output)
            }
        }
    }
    
    return finalOverlays
}
```

---

## 四、ArscCompiler - AAPT2 链接编译器

### 4.1 核心职责

**定义位置**: `ArscCompiler.kt`

| 职责 | 说明 |
|------|------|
| **AAPT2 Link** | 链接 Flat 文件为可部署资源 |
| **增量链接** | 使用 AAPT2 inclink 命令实现增量链接 |
| **R.java 生成** | 生成 R.java 文件 |
| **R.java 修复** | 修复 R.java 常量过多问题 |
| **动态特性模块支持** | 支持动态特性模块的资源链接 |

**支持类型**: `Flat`, `AndroidManifest`

### 4.2 AAPT2 Daemon 管理

**Daemon 缓存**:
```kotlin
private val aapt2InvokerMap: ConcurrentHashMap<String, Aapt2DaemonInvoker> = ConcurrentHashMap()
```

**加载表 (Load Table)**:
```kotlin
private fun loadTable(apkFileUnit: ApkFileUnit): Boolean {
    // 1. 获取资源 APK
    val resApkFile: File = getResApk(apkFileUnit)
    
    // 2. 生成 Styleable 文件
    val styleableFile = StyleableFileGenerator(logger).generateStyleableFile(context, context.tempCompileDir, apkFileUnit)
    
    // 3. 构建 inclink --load 命令
    val command = if (context.isSingleApk || apkFileUnit.isBaseApk) {
        """
        inclink --load --warn-manifest-validation
        --styleables ${styleableFile?.absolutePath ?: "no_styleables_file"}
        -o no_need_output_path_on_load
        -I ${context.androidJar}
        --manifest no_need_manifest_on_load
        ${resApkFile}
        """.trimMargin().replace("\n", " ")
    } else {
        // 动态特性模块需要依赖 Base APK
        val baseResApk = getResApk(baseApk)
        """
        inclink --load --warn-manifest-validation
        --styleables ${styleableFile?.absolutePath ?: "no_styleables_file"}
        -o no_need_output_path_on_load
        -I ${context.androidJar}
        -I ${baseResApk.absolutePath}
        --manifest no_need_manifest_on_load
        ${resApkFile}
        """.trimMargin().replace("\n", " ")
    }
    
    // 4. 调用 AAPT2
    val result = aapt2Invoker.invoke(command)
    
    // 5. 缓存 Daemon
    aapt2InvokerMap[apkFileUnit.apkFile.path] = aapt2Invoker
    return true
}
```

**资源 APK 获取**:
```kotlin
private fun getResApk(apkFileUnit: ApkFileUnit): File {
    val deployedArsc = context.deployedFiles.find {
        it.apkPath == apkFileUnit.apkFile.path && it.relativeFile.path == ARSC_FILE_NAME
    }
    val isNeedLoadLatestResApk = deployedArsc != null
    
    if (isNeedLoadLatestResApk) {
        // APK 已部署过，需要加载最新资源
        var manifestFile = context.deployedFiles.find {
            it.apkPath == apkFileUnit.apkFile.path && it.relativeFile.path == "AndroidManifest.xml"
        }?.file
        
        if (manifestFile == null) {
            manifestFile = File(context.tempCompileDir, "AndroidManifest.xml")
            resApkFile.extractFile("AndroidManifest.xml", manifestFile)
        }
        
        // 打包 Manifest 和 resources.arsc 为临时 APK
        val latestResApkFile = File(context.tempCompileDir, "${apkFileUnit.getUniquePath("res")}.apk")
        zipFiles(listOf(manifestFile, deployedArsc!!.file), latestResApkFile)
        resApkFile = latestResApkFile
    }
    
    return resApkFile
}
```

### 4.3 增量链接

**命令构建**:
```kotlin
private fun incLinkCompile(...): List<CompileOutput> {
    val rFileDir = File(outputDir, apkFileUnit.getUniquePath("rjava"))
    val overlayDir = File(outputDir, apkFileUnit.getUniquePath("overlays"))
    
    val flatFilesArg = flatFiles.joinToString(separator = "\n") { it.absolutePath }
    
    val command = if (context.isSingleApk || apkFileUnit.isBaseApk) {
        """
        inclink -o $overlayDir --output-to-dir
        --java $rFileDir
        --manifest $manifestName
        $flatFilesArg
        """.trimMargin().replace("\n", " ")
    } else {
        // 动态特性模块需要自定义包名
        """
        inclink -o $overlayDir --output-to-dir
        --java $rFileDir
        --manifest $manifestName
        --custom-package ${apkFileUnit.resourcePackage}
        --allow-reserved-package-id
        $flatFilesArg
        """.trimMargin().replace("\n", " ")
    }
    
    val result = aapt2Invoker.invoke(command)
    
    // 收集输出
    val rFiles = rFileDir.listFilesRecursively().map {
        CompileOutput(CompileOutput.Type.Java, it, rFileDir)
    }
    val overlays = overlayDir.listFilesRecursively().map {
        CompileOutput(CompileOutput.Type.Res, it, overlayDir, apkFileUnit.apkFile.path)
    }
    
    return rFiles + overlays
}
```

### 4.4 R.java 修复

**问题**: R.java 常量过多导致编译失败（常量字段存储在 uint16，最大约 32756）

**解决方案**: 拆分 R 类为多个类并继承

```kotlin
override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
    // ... 增量链接 ...
    
    val javaFile = result.find { it.type == CompileOutput.Type.Java }?.file
    if (javaFile?.exists() == true) {
        rJavaFixer.fixIfNeeded(javaFile)
    }
    
    // ...
}
```

### 4.5 动态特性模块支持

**Base APK 更新传播**:
```kotlin
private var isBaseApkArscUpdate = false
private var baseApkUpdateFlatFiles = listOf<File>()

override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
    val flatFiles = task.files.filter { it.type == CompileFile.Type.Flat }.map { it.file }.toMutableList()
    
    if (apkFileUnit.isFeatureApk && isBaseApkArscUpdate) {
        // Base APK 资源更新，需要联合编译
        flatFiles.addAll(baseApkUpdateFlatFiles)
    }
    
    // ... 增量链接 ...
    
    if (apkFileUnit.isBaseApk) {
        // Base APK 总是先编译
        isBaseApkArscUpdate = javaFile?.exists() == true
        baseApkUpdateFlatFiles = flatFiles
    }
    
    return CompileResult(...)
}
```

---

## 五、AssetOverlayCompiler - Assets 编译器

### 5.1 核心职责

**定义位置**: `AssetOverlayCompiler.kt`

| 职责 | 说明 |
|------|------|
| **Assets 复制** | 复制 Assets 文件到输出目录 |
| **Native 库复制** | 复制 Native 库到输出目录 |
| **目录支持** | 支持复制整个目录 |

**支持类型**: `Asset`, `NativeLib`

### 5.2 编译流程

```kotlin
override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
    val outputs = mutableListOf<CompileOutput>()
    val details = mutableListOf<Result<CompileFile, CompileError>>()
    
    task.files.forEach {
        // 1. 确定输出子目录
        val outputSubDir = when (it.type) {
            CompileFile.Type.Asset -> "assets"
            CompileFile.Type.NativeLib -> "lib"
            else -> throw JuggInternalException.unrecognizedType(it.type.toString())
        }
        
        // 2. 确定输出类型
        val outputType = when (it.type) {
            CompileFile.Type.Asset -> CompileOutput.Type.Asset
            CompileFile.Type.NativeLib -> CompileOutput.Type.NativeLib
            else -> throw JuggInternalException.unrecognizedType(it.type.toString())
        }
        
        val outputDir = File(task.outputDir, apkFileUnit.getUniquePath(outputSubDir))
        
        // 3. 复制文件
        try {
            if (it.file.isDirectory) {
                val dirToFilesMap = DirToFileMapHelper.createDirToResFileMap(listOf(it), logger)
                dirToFilesMap.values.firstOrNull()?.forEach { subFile ->
                    val outputFile = subFile.copyToBaseDir(it.baseDir, outputDir)
                    outputs.add(CompileOutput(outputType, outputFile, task.outputDir, apkFileUnit.apkFile.path))
                }
            } else {
                val outputFile = it.file.copyToBaseDir(it.baseDir, outputDir)
                outputs.add(CompileOutput(outputType, outputFile, task.outputDir, apkFileUnit.apkFile.path))
            }
            details.add(Result.success(it))
        } catch (e: Exception) {
            details.add(Result.failure(CompileError(it, listOf(0L to "copy file failed"))))
        }
    }
    
    return CompileResult(task, details, outputs)
}
```

---

## 六、RJavaFixer - R.java 修复器

### 6.1 问题背景

**定义位置**: `RJavaFixer.kt`

**问题**: 
- 常量字段存储在 uint16，单个内部类最大约 32756 个字段
- 大型项目 R.java 常量过多导致编译失败

**解决方案**: 拆分 R 类为多个类并继承

### 6.2 修复流程

```kotlin
fun fixIfNeeded(rFile: File) {
    // 1. 分析 R.java 结构
    val rJavaData = analyze(rFile)
    val isNeedFix = rJavaData.isNeedFix()
    
    if (!isNeedFix) return
    
    // 2. 拆分类
    val rRewriteData = split(rJavaData)
    
    // 3. 写入文件
    writeToFile(rJavaData, rRewriteData, rFile)
}
```

### 6.3 分析阶段

```kotlin
private fun analyze(rFile: File): RJavaData {
    val lines = rFile.readLines()
    val classes = mutableListOf<RClassData>()
    
    var className = "null"
    var classDeclareLine = 0
    var fieldLines = ArrayList<Int>(0)
    
    lines.forEachIndexed { index, line ->
        if (line.startsWith("  public static final class ")) {
            // 存储上一个类
            if (className != "null") {
                classes.add(RClassData(className, classDeclareLine, fieldLines))
            }
            
            // 记录新类
            className = line.substringAfter("  public static final class")
                .substringBefore("{").trim()
            classDeclareLine = index
            fieldLines = ArrayList(10240)
        } else if (line.startsWith("    public static final int ")) {
            // 仅处理 int 字段，int[] 不需要拆分
            fieldLines.add(index)
        }
    }
    
    return RJavaData(lines, classes)
}
```

### 6.4 拆分阶段

```kotlin
private fun split(rJavaData: RJavaData): RRewriteData {
    val rewriteLines: MutableMap<Int, String> = mutableMapOf()
    val removeLines: MutableSet<Int> = mutableSetOf()
    val newLines: MutableList<String> = mutableListOf()
    
    val needSplitClasses = rJavaData.classes.filter { it.isNeedSplit }
    needSplitClasses.forEach { rClassData ->
        var remainFieldLines = rClassData.fieldLines
        var index = 1
        
        while (remainFieldLines.size > MAX_CONSTANT_FIELDS_COUNT) {
            val splitSize = min(MAX_CONSTANT_FIELDS_COUNT, remainFieldLines.size - MAX_CONSTANT_FIELDS_COUNT)
            remainFieldLines = remainFieldLines.subList(0, remainFieldLines.size - splitSize)
            
            val splitFieldLines = remainFieldLines.subList(remainFieldLines.size - splitSize, remainFieldLines.size)
            val splitClassName = "${rClassData.name}$index"
            
            if (index == 1) {
                // 原类继承第一个拆分类
                rewriteLines[rClassData.classDeclareLine] = rJavaData.lines[rClassData.classDeclareLine]
                    .replace("{", "extends $splitClassName {")
                newLines.add("  public static class $splitClassName {\n")
            } else {
                // 后续拆分类继承前一个拆分类
                val lastSplitClassName = "${rClassData.name}${index - 1}"
                newLines.add("  public static class $splitClassName extends $lastSplitClassName {\n")
            }
            
            newLines.add("    private $splitClassName() {}")
            
            // 移动字段到拆分类
            splitFieldLines.forEach { lineIndex ->
                removeLines.add(lineIndex)
                newLines.add(rJavaData.lines[lineIndex])
            }
            newLines.add("  }\n")
            
            index++
        }
    }
    
    return RRewriteData(rewriteLines, removeLines, newLines)
}
```

**拆分示例**:
```java
// 原始 R.java
public final class R {
    public static final class drawable {
        public static final int icon1 = 0x7f020000;
        public static final int icon2 = 0x7f020001;
        // ... 35000 个字段 ...
    }
}

// 修复后 R.java
public final class R {
    public static final class drawable extends drawable1 {
        public static final int icon1 = 0x7f020000;
        // ... 30000 个字段 ...
    }
    
    public static class drawable1 extends drawable2 {
        private drawable1() {}
        public static final int icon30001 = 0x7f027530;
        // ... 5000 个字段 ...
    }
    
    public static class drawable2 {
        private drawable2() {}
        // 如果还有更多字段...
    }
}
```

### 6.5 常量配置

```kotlin
companion object {
    /**
     * 常量字段存储在 uint16，单个内部类最大约 32756
     * Jugg 使用 30000 作为容错值（32700 也可以）
     */
    const val MAX_CONSTANT_FIELDS_COUNT = 30000
}
```

---

## 七、RDexForSubmoduleCompiler - 子模块 R.dex 生成器

### 7.1 核心职责

**定义位置**: `RDexForSubmoduleCompiler.kt`

| 职责 | 说明 |
|------|------|
| **R.dex 生成** | 为子模块生成独立的 R.dex |
| **包名修改** | 修改 R.dex 的包名为子模块包名 |
| **增量生成** | 仅在 R 文件更新时重新生成 |

**支持类型**: `DexToChangePackageName`, `Java`, `Kotlin`, `Resource`

### 7.2 生成流程

```kotlin
override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
    // 1. 检查是否需要生成
    val isNeedGenerate = rDexOutputFile.exists() && 
                         !generatedModules.contains(module.name) && 
                         (module != context.tempModule)
    if (!isNeedGenerate) return CompileResult(...)
    
    // 2. 获取包名
    val packageName = run {
        if (module.namespace != null) {
            module.namespace
        } else {
            RPackageReader(manifestFile, logger).readPackageName()
        }
    }
    
    if (packageName == context.packageName) {
        // 主模块不需要生成 R.dex
        return CompileResult(...)
    }
    
    // 3. 转换 Dex 文件
    val sourceRDexFiles = rDexOutputDir.listFiles()?.filter { it.isFile && it.extension == "dex" }
    val destRDexFiles = sourceRDexFiles.map { sourceFile ->
        val (destDexFile, _) = DexPackageRenamer(sourceFile, packageName)
            .generate(task.outputDir, context.tempModule.buildPathInfo.javaClassPath)
        CompileOutput(CompileOutput.Type.Dex, destDexFile, task.outputDir)
    }
    
    generatedModules.add(module.name)
    return CompileResult(task, emptyList(), destRDexFiles)
}
```

### 7.3 R 文件更新检测

```kotlin
override fun doCompile(task: CompileTask): CompileResult {
    // 如果输入包含 DexToChangePackageName 类型，说明 R 文件更新
    val isRFileUpdated = task.files.any { it.type == CompileFile.Type.DexToChangePackageName }
    if (isRFileUpdated) {
        logger.debug("R file has update, going to regenerate R.dex for all modules")
        generatedModules.clear()
    }
    return super.doCompile(task)
}
```

---

## 八、DexPackageRenamer - Dex 包名修改器

### 8.1 核心职责

**定义位置**: `DexPackageRenamer.kt`

| 职责 | 说明 |
|------|------|
| **包名修改** | 修改 Dex 文件中的包名 |
| **Class 生成** | 生成对应的 Class 文件（用于 Classpath） |
| **注解处理** | 处理 EnclosingClass/MemberClasses 注解 |

### 8.2 生成流程

```kotlin
fun generate(dexOutputDir: File, classPathDir: File): Pair<File, File> {
    val outputFile = File(dexOutputDir, newPackageName.packageNameToPath + dexFile.name)
    val outputClasspathFile = File(classPathDir, newPackageName.packageNameToPath + dexFile.nameWithoutExtension + ".class")
    
    val reader = DexFileReader(dexFile.readBytes())
    val writer = ChangePackageWriter(newPackageName)
    reader.accept(writer, 0)
    
    // 先写 Class 文件（因为数据会被清空）
    outputClasspathFile.parentFile?.mkdirs()
    outputClasspathFile.writeBytes(writer.toClassByteArray())
    
    // 再写 Dex 文件
    outputFile.parentFile?.mkdirs()
    outputFile.writeBytes(writer.toDexByteArray())
    
    return outputFile to outputClasspathFile
}
```

### 8.3 包名替换

**类名替换**:
```kotlin
override fun visit(access_flags: Int, className: String, superClass: String?, interfaceNames: Array<out String>?): DexClassVisitor {
    val classPackageSigPrefix = className.substringBeforeLast("/")
    val newClassPackageSigPrefix = "L" + newPackageName.replace(".", "/")
    val newClassSigName = className.replace(classPackageSigPrefix, newClassPackageSigPrefix)
    val newSuperClass = superClass?.replace(classPackageSigPrefix, newClassPackageSigPrefix)
    
    return writerClassVisitor.visit(access_flags, newClassSigName, newSuperClass, interfaceNames)
}
```

**注解替换**:
```kotlin
override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
    when (name) {
        "Ldalvik/annotation/EnclosingClass;" -> {
            // 替换外部类引用
            return object : DexAnnotationVisitor(superVisitor) {
                override fun visit(name: String?, value: Any?) {
                    when (value) {
                        is DexType -> {
                            val newDesc = value.desc.replace(classPackageSigPrefix, newClassPackageSigPrefix)
                            super.visit(name, DexType(newDesc))
                        }
                        else -> super.visit(name, value)
                    }
                }
            }
        }
        "Ldalvik/annotation/MemberClasses;" -> {
            // 替换内部类引用
            return object : DexAnnotationVisitor(superVisitor) {
                override fun visitArray(name: String?): DexAnnotationVisitor {
                    return object : DexAnnotationVisitor(super.visitArray(name)) {
                        override fun visit(name: String?, value: Any?) {
                            when (value) {
                                is DexType -> {
                                    val newDesc = value.desc.replace(classPackageSigPrefix, newClassPackageSigPrefix)
                                    super.visit(name, DexType(newDesc))
                                }
                                else -> super.visit(name, value)
                            }
                        }
                    }
                }
            }
        }
        else -> return superVisitor
    }
}
```

**方法和字段替换**:
```kotlin
override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
    val newMethod = if (method.owner.startsWith(classPackageSigPrefix)) {
        Method(
            method.owner.replace(classPackageSigPrefix, newClassPackageSigPrefix),
            method.name,
            Proto(
                method.proto.parameterTypes.map { it.replace(classPackageSigPrefix, newClassPackageSigPrefix) }.toTypedArray(),
                method.proto.returnType,
            )
        )
    } else null
    
    return if (newMethod != null) {
        super.visitMethod(accessFlags, newMethod)
    } else {
        super.visitMethod(accessFlags, method)
    }
}
```

### 8.4 Class 文件生成

**目的**: 为 Kotlin 编译器提供 Classpath

```kotlin
fun toClassByteArray(): ByteArray {
    val node: ClassDefItem = writer.cp.classDefs.values.first()
    val cw = ClassWriter(0)
    
    // 1. 访问类
    cw.visit(Opcodes.V1_7, node.accessFlags, className, null, superClassName, interfaces)
    cw.visitSource(node.sourceFile?.stringData?.string, null)
    
    // 2. 访问内部类
    node.classAnnotations?.annotations?.find { it.annotation.type.descriptor.stringData.string == "Ldalvik/annotation/MemberClasses;" }
        ?.let { ... }
    
    // 3. 访问字段
    node.classData?.staticFields?.forEach { cw.visitField(...) }
    node.classData?.instanceFields?.forEach { cw.visitField(...) }
    
    // 4. 访问方法（生成空实现）
    node.classData?.directMethods?.forEach { visitMethod(it) }
    node.classData?.virtualMethods?.forEach { visitMethod(it) }
    
    cw.visitEnd()
    return cw.toByteArray()
}
```

**方法空实现**:
```kotlin
val visitMethod: (ClassDataItem.EncodedMethod) -> Unit = {
    val mv = cw.visitMethod(it.accessFlags, it.method.name.stringData.string, descriptor, null, null)
    when (it.method.proto.ret.descriptor.stringData.string) {
        "V" -> { mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 1) }
        "I", "Z" -> { mv.visitInsn(Opcodes.ICONST_0); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(1, 1) }
        "F" -> { mv.visitInsn(Opcodes.FCONST_0); mv.visitInsn(Opcodes.FRETURN); mv.visitMaxs(1, 1) }
        "L" -> { mv.visitInsn(Opcodes.LCONST_0); mv.visitInsn(Opcodes.LRETURN); mv.visitMaxs(2, 1) }
        "D" -> { mv.visitInsn(Opcodes.DCONST_0); mv.visitInsn(Opcodes.DRETURN); mv.visitMaxs(2, 1) }
        else -> { mv.visitInsn(Opcodes.ACONST_NULL); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1, 1) }
    }
    mv.visitEnd()
}
```

---

## 九、辅助工具类

### 9.1 RPackageReader - 包名读取器

**定义位置**: `RPackageReader.kt`

**功能**: 从 AndroidManifest.xml 读取包名

```kotlin
fun readPackageName(): String? {
    if (!manifestFile.exists()) return null
    
    val factory = DocumentBuilderFactory.newInstance()
    val builder = factory.newDocumentBuilder()
    
    manifestFile.inputStream().use {
        val doc = builder.parse(it)
        val nodeList = doc.getElementsByTagName("manifest")
        if (nodeList.length == 0) return null
        
        val rPackage = nodeList.item(0).attributes?.getNamedItem("package")?.nodeValue
        return rPackage
    }
}
```

### 9.2 DirToFileMapHelper - 目录映射助手

**定义位置**: `DirToFileMapHelper.kt`

**功能**: 创建目录到文件的映射，支持增量编译

```kotlin
fun createDirToResFileMap(compileFiles: List<CompileFile>, logger: Logger): Map<File, List<File>> {
    return compileFiles
        .filter { it.file.isDirectory }
        .associate { compileFile ->
            val allResFiles = compileFile.file.listFilesRecursively()
            val relativeOldResDirectory = compileFile.oldRes
            val relativeOldFiles = relativeOldResDirectory?.listFilesRecursively()
            
            if (relativeOldResDirectory == null || relativeOldFiles.isNullOrEmpty()) {
                // 无旧版本，编译所有文件
                return@associate compileFile.file to allResFiles
            } else {
                // 对比 CRC，仅编译变更文件
                val checksumMap = relativeOldFiles.associate {
                    it.relativeTo(relativeOldResDirectory).path to it.crc32
                }
                val filteredResFiles = allResFiles.filter {
                    val relativePath = it.relativeTo(compileFile.file).path
                    val oldChecksum = checksumMap[relativePath] ?: return@filter true
                    return@filter it.crc32 != oldChecksum
                }
                return@associate compileFile.file to filteredResFiles
            }
        }
}
```

### 9.3 StyleableFileGenerator - Styleable 文件生成器

**定义位置**: `StyleableFileGenerator.kt`

**功能**: 生成 Styleable 文件，用于 AAPT2 inclink

**生成流程**:
```kotlin
fun generateStyleableFile(context: ICompileContext, outputDir: File, apkFileUnit: ApkFileUnit): File? {
    // 1. 查找 R.jar 或 R.class
    val rFiles = modules.mapNotNull { it.buildPathInfo.rFilePath }
    
    if (rFiles.isNotEmpty()) {
        return generateStyleableFile(rFiles, outputDir)
    } else {
        // 低版本 AGP 没有 R.jar，从 Java Classpath 读取
        val packageName = RPackageReader(manifestFile, logger).readPackageName()
        return generateStyleableFile2(javaClassPath, packageName, outputDir)
    }
}
```

**Styleable 解析**:
```kotlin
private fun doGenerateStyleableFile(providers: List<InputStreamProvider>, outputDir: File): File {
    val styleablesMerger = StyleablesMerger(logger)
    
    providers.forEach { provider ->
        provider.use { ins ->
            val classReader = ClassReader(ins)
            val asmClassNode = ClassNode()
            classReader.accept(asmClassNode, 0)
            
            asmClassNode.fields.forEach {
                if (it is FieldNode) {
                    styleablesMerger.acceptVariable(it.name, it.desc)
                }
            }
        }
    }
    
    // 写入文件
    val outputFile = File(outputDir, "styleables.txt")
    BufferedOutputStream(outputFile.outputStream()).use { outs ->
        styleablesMerger.getResult().forEach {
            outs.write("${it.name}:".toByteArray())
            outs.write(it.attrs.joinToString(",").toByteArray())
            outs.write("\n".toByteArray())
        }
    }
    
    return outputFile
}
```

**Styleable 合并**:
```kotlin
private class StyleablesMerger(private val logger: Logger) {
    private val styleables = mutableMapOf<String, Styleables>()
    private var currentStyleableName: String? = null
    
    fun acceptVariable(name: String, type: String) {
        when (type) {
            "[I" -> {
                // Styleable 数组
                styleables[name] = Styleables(name, mutableListOf())
                currentStyleableName = name
            }
            "I" -> {
                // Styleable 属性
                val attrName = name.substring(currentStyleableName.length + 1)
                styleables[currentStyleableName]!!.attrs.add(attrName)
            }
        }
    }
    
    fun getResult(): List<Styleables> = styleables.values.toList()
}
```

---

## 十、设计亮点总结

### 10.1 增量编译优化

| 优化点 | 说明 |
|--------|------|
| **AAPT2 Daemon** | 复用 AAPT2 Daemon，避免重复启动 |
| **增量链接** | 使用 `inclink` 命令实现增量链接 |
| **CRC 对比** | 对比文件 CRC，仅编译变更文件 |
| **资源 APK 缓存** | 缓存已部署的资源 APK，加速加载 |

### 10.2 多 APK 支持

| 特性 | 说明 |
|------|------|
| **APK 分组编译** | 按 APK 分组编译资源 |
| **动态特性模块** | 支持动态特性模块的资源链接 |
| **Base APK 依赖** | 动态特性模块依赖 Base APK 的资源 |
| **R.dex 生成** | 为每个子模块生成独立的 R.dex |

### 10.3 容错设计

| 容错点 | 说明 |
|--------|------|
| **R.java 修复** | 自动拆分 R.java 常量过多的类 |
| **Daemon 重启** | AAPT2 Daemon 失败时自动重启 |
| **资源过滤** | 过滤不必要的资源，避免 APK 重新打包 |
| **Styleable 降级** | Styleable 生成失败时降级处理 |

### 10.4 DataBinding/ViewBinding 支持

| 特性 | 说明 |
|------|------|
| **自动检测** | 自动检测 DataBinding/ViewBinding 使用 |
| **布局分割** | 分割 DataBinding 布局文件 |
| **Java 生成** | 生成 DataBinding/ViewBinding Java 文件 |
| **增量处理** | 仅处理变更的布局文件 |

### 10.5 性能优化

| 优化点 | 说明 |
|--------|------|
| **预热机制** | 提前加载 AAPT2 Daemon |
| **并发编译** | 支持多 APK 并发编译 |
| **缓存复用** | 复用已编译的 Flat 文件 |
| **目录 MD5** | 使用 MD5 哈希避免目录名冲突 |

---

## 十一、待深入分析的模块

| 模块 | 文件数 | 说明 |
|------|--------|------|
| `compiler/databinding/` | ~5 | DataBinding/ViewBinding 支持 |
| `compiler/manifest/` | ~3 | Manifest 增量编译 |
| `compiler/obfuscation/` | ~3 | R8 混淆支持 |

**下一步**: 阅读 `compiler/databinding/*.kt`，深入理解 DataBinding/ViewBinding 编译流程。

---

**文档状态**: ✅ 已完成  
**下一步**: 阅读阶段 2.4 - compiler/databinding/*.kt
