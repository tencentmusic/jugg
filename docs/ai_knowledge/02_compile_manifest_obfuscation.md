# Jugg 编译系统 - Manifest 和混淆编译器

---

## 一、Manifest 和混淆编译器概览

### 1.1 编译器架构

```
AndroidManifest.xml (多个模块)
    ↓
AndroidManifestCompiler
    ├─ ManifestDiffer (差异分析)
    ├─ AndroidManifestMerger (增量合并)
    └─ XmlParser (XML 解析)
    ↓
合并后的 AndroidManifest.xml

Class 文件
    ↓
ClassMinifyCompiler
    ├─ R8MappingReader (读取 mapping.txt)
    └─ ClassObfuscator (ASM 字节码混淆)
    ↓
混淆后的 Class 文件
```

### 1.2 编译器列表

| 编译器 | 输入类型 | 输出类型 | 职责 |
|--------|---------|---------|------|
| **AndroidManifestCompiler** | AndroidManifest | Res | Manifest 增量合并 |
| **AndroidManifestMerger** | - | - | Manifest 合并逻辑 |
| **ManifestDiffer** | - | - | Manifest 差异分析 |
| **XmlParser** | - | - | XML 解析器 |
| **ClassMinifyCompiler** | Class | Class | Class 文件混淆 |
| **ClassObfuscator** | - | - | ASM 字节码混淆 |
| **R8MappingReader** | - | - | R8 mapping.txt 解析 |

---

## 二、AndroidManifestCompiler - Manifest 编译器

### 2.1 核心职责

**定义位置**: `AndroidManifestCompiler.kt`

| 职责 | 说明 |
|------|------|
| **增量合并** | 增量合并多个模块的 Manifest 文件 |
| **占位符替换** | 替换 Manifest 中的占位符（如 `${applicationId}`） |
| **CRC 检测** | 检测 Manifest 文件是否变更 |
| **APK 分组** | 支持多 APK 项目的 Manifest 合并 |

**支持类型**: `AndroidManifest`

### 2.2 编译流程

```kotlin
override fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
    // 1. 获取基准 Manifest
    val finalMergedManifest = if (deployedManifest.exists()) {
        deployedManifest
    } else {
        applicationModule.buildPathInfo.mergedManifest
    }
    
    // 2. 收集变更的 Manifest 文件
    val changedManifestFileList = task.files.mapNotNull {
        val module = it.module
        
        // 准备占位符
        val manifestPlaceHolders = module.manifestPlaceHolders?.toMutableMap()
        val isApplicationManifest = module.moduleRootDir == context.applicationModule?.moduleRootDir
        if (isApplicationManifest) {
            manifestPlaceHolders?.put("applicationId", packageName)
        }
        if (module.namespace != null) {
            manifestPlaceHolders?.put(ManifestDiffer.JUGG_NAMESPACE_IN_GRADLE, module.namespace)
        }
        
        // CRC 检测
        if (module.moduleRootDir.path == context.tempModule.moduleRootDir.path) {
            val relativeManifestFile = it.oldManifest
            if (relativeManifestFile != null) {
                if (it.file.crc32 == relativeManifestFile.crc32) {
                    logger.debug("library AndroidManifest.xml in not changed, skip.")
                    return@mapNotNull null
                }
            }
            return@mapNotNull ChangedManifestFile(it.file, relativeManifestFile, manifestPlaceHolders)
        } else {
            val relativeManifestFile = findMergedManifestFile(it, module)
            return@mapNotNull ChangedManifestFile(it.file, relativeManifestFile, manifestPlaceHolders)
        }
    }
    
    // 3. 增量合并
    val isNeedUpdate = AndroidManifestMerger(logger).merge(
        finalMergedManifest, 
        changedManifestFileList, 
        outputManifestFile
    )
    
    if (!isNeedUpdate) {
        logger.debug("All AndroidManifest.xml in libraries are not changed after diff, skip merge.")
        return CompileResult(task, task.files.map { Result.success(it) }, emptyList())
    }
    
    // 4. 复制到临时目录供下次编译使用
    deployedManifest.parentFile.mkdirs()
    outputManifestFile.copyTo(deployedManifest, true)
    
    return CompileResult(task, task.files.map { Result.success(it) }, listOf(compileOutput))
}
```

### 2.3 占位符处理

**应用模块占位符**:
```kotlin
val isApplicationManifest = module.moduleRootDir == context.applicationModule?.moduleRootDir
if (isApplicationManifest) {
    val packageName = context.packageName
    manifestPlaceHolders?.put("applicationId", packageName)
}
```

**Namespace 占位符**:
```kotlin
if (module.namespace != null) {
    manifestPlaceHolders?.put(ManifestDiffer.JUGG_NAMESPACE_IN_GRADLE, module.namespace)
}
```

**占位符示例**:
```xml
<!-- 原始 Manifest -->
<manifest package="${applicationId}">
    <application android:name=".MyApplication">
        ...
    </application>
</manifest>

<!-- 替换后 -->
<manifest package="com.example.app">
    <application android:name="com.example.app.MyApplication">
        ...
    </application>
</manifest>
```

---

## 三、ManifestDiffer - Manifest 差异分析器

### 3.1 核心职责

**定义位置**: `ManifestDiffer.kt`

| 职责 | 说明 |
|------|------|
| **差异分析** | 分析新旧 Manifest 的差异 |
| **占位符替换** | 替换 Manifest 中的占位符 |
| **节点匹配** | 匹配新旧 Manifest 的节点 |
| **属性过滤** | 过滤不需要合并的属性 |

### 3.2 差异分析流程

```kotlin
fun diff(changedManifestFile: ChangedManifestFile): ManifestDiffResult {
    // 1. 解析新旧 Manifest
    val newNode = XmlParser().parse(changedManifestFile.newFile)
    val oldNode = changedManifestFile.oldFile?.let { XmlParser().parse(it) }
    
    // 2. 预处理（占位符替换）
    preprocess(newNode, changedManifestFile.placeHolders)
    if (oldNode != null) {
        preprocess(oldNode, changedManifestFile.placeHolders)
    }
    
    // 3. 差异分析
    val holderNode = ManifestDiffResult.DiffElement(doc.createElement("holder"), true)
    diffNode(holderNode, newNode.node, oldNode?.node)
    
    if (holderNode.isNothingToUpdate) {
        return ManifestDiffResult.DiffElement(doc.createElement(MANIFEST_TAG_NAME), false)
    }
    
    return holderNode.changedChildren.first()
}
```

### 3.3 节点差异分析

```kotlin
private fun diffNode(parentDiffElement: ManifestDiffResult.DiffElement, newNode: Element, oldNode: Element?) {
    val isNewNode = oldNode == null
    val currentDiffElement = ManifestDiffResult.DiffElement(newNode, isNewNode)
    
    // 1. 差异分析属性
    currentDiffElement.diffAttributes(oldNode, ignoreAttrs[newNode.nodeName])
    
    // 2. 处理 tools:node 属性
    val toolsNode = currentDiffElement.addedAttributes.find { it.nodeName == "tools:node" }
    if (toolsNode != null) {
        if (toolsNode.nodeValue == "remove") {
            return // 忽略 tools:remove 节点
        }
    }
    currentDiffElement.addedAttributes.removeIf {
        it.nodeName.startsWith("tools:") || it.nodeName == "xmlns:tools"
    }
    
    // 3. 匹配并差异分析子节点
    val nodeMatcher = ManifestNodeMatcher(newNode.childNodes, oldNode?.childNodes)
    newNode.childNodes.forEach { newChildNode ->
        if (newChildNode.nodeType != Node.ELEMENT_NODE) return@forEach
        if (newChildNode.nodeName in ignoreNodes) return@forEach
        
        val relativeNode: Node? = nodeMatcher.findRelativeChild(newChildNode)
        diffNode(currentDiffElement, newChildNode as Element, relativeNode as? Element)
    }
    
    // 4. 添加到父节点
    if (isNewNode) {
        parentDiffElement.changedChildren.add(currentDiffElement)
    } else if (currentDiffElement.changedAttributes.isNotEmpty()) {
        parentDiffElement.changedChildren.add(currentDiffElement)
    } else if (currentDiffElement.changedChildren.isNotEmpty()) {
        parentDiffElement.changedChildren.add(currentDiffElement)
    }
}
```

### 3.4 占位符替换

**android:name 处理**:
```kotlin
if (it.nodeName == "android:name") {
    val name = it.nodeValue
    if (name != null && name.startsWith(".") && packageName != null) {
        it.nodeValue = packageName + name
        return
    }
}
```

**${...} 占位符处理**:
```kotlin
@Suppress("RegExpRedundantEscape")
private val regex = "\\$\\{[^}]+\\}".toRegex()

if (placeHolders != null) {
    it.nodeValue = regex.replace(it.nodeValue) { matchResult ->
        val key = matchResult.value.substring(2, matchResult.value.length - 1)
        return@replace placeHolders[key] ?: matchResult.value
    }
}
```

**示例**:
```xml
<!-- 原始 -->
<activity android:name=".MainActivity" />
<meta-data android:value="${API_KEY}" />

<!-- 替换后 (packageName = "com.example.app", API_KEY = "abc123") -->
<activity android:name="com.example.app.MainActivity" />
<meta-data android:value="abc123" />
```

### 3.5 节点匹配

**唯一键生成**:
```kotlin
val Node.uniqueKey: String get() {
    if (nodeName in uniqueNodeName) { // manifest, application, uses-sdk, queries
        return nodeName
    }
    
    val name = this["android:name"]
    if (name != null) {
        return "$nodeName:$name"
    }
    
    val host = this["android:host"]
    val scheme = this["android:scheme"]
    if (host != null || scheme != null) {
        return "$nodeName:$host:$scheme"
    }
    
    val mimeType = this["android:mimeType"]
    if (mimeType != null) {
        return "$nodeName:$mimeType"
    }
    
    // 使用所有属性和子节点作为唯一键
    val nameSet = mutableSetOf<String>()
    attributes.forEach {
        nameSet.add("${it.nodeName}:${it.nodeValue};")
    }
    childNodes.forEach {
        if (it.nodeType != Node.ELEMENT_NODE) return@forEach
        // ... 构建子节点唯一键
    }
    return nameSet.sorted().joinToString("&")
}
```

**节点匹配器**:
```kotlin
class ManifestNodeMatcher(
    private val newNodes: NodeList,
    private val oldNodes: NodeList?,
) {
    private val matchPair = mutableMapOf<Node, Node?>()
    
    init {
        if (oldNodes == null) return
        
        val oldNodeWithDeclareNameMap = mutableMapOf<String, Element>()
        oldNodes.forEach {
            if (it.nodeType != Node.ELEMENT_NODE) return@forEach
            oldNodeWithDeclareNameMap[it.uniqueKey] = it as Element
        }
        
        newNodes.forEach { newNode ->
            if (newNode.nodeType != Node.ELEMENT_NODE) return@forEach
            val relativeOldNode = oldNodeWithDeclareNameMap[newNode.uniqueKey]
            matchPair[newNode] = relativeOldNode
        }
    }
    
    fun findRelativeChild(node: Node): Node? {
        return matchPair[node]
    }
}
```

### 3.6 忽略规则

**忽略节点**:
```kotlin
private val ignoreNodes = setOf(
    "uses-sdk" // 子模块也可以声明，但合并弊大于利
)
```

**忽略属性**:
```kotlin
private val ignoreAttrs = mapOf(
    "manifest" to setOf("android:versionCode", "android:versionName", "package"),
)
```

---

## 四、AndroidManifestMerger - Manifest 合并器

### 4.1 核心职责

**定义位置**: `AndroidManifestMerger.kt`

| 职责 | 说明 |
|------|------|
| **增量合并** | 将差异应用到基准 Manifest |
| **属性更新** | 更新节点属性 |
| **节点插入** | 插入新节点 |
| **递归合并** | 递归合并子节点 |

### 4.2 合并流程

```kotlin
fun merge(mergedManifestFile: File, changedManifestFiles: List<ChangedManifestFile>, outputFile: File): Boolean {
    // 1. 解析基准 Manifest
    val fullNode = XmlParser().parse(mergedManifestFile)
    
    // 2. 差异分析
    val diffElements = changedManifestFiles.map {
        val diffResult = ManifestDiffer().diff(it)
        diffResult.diffElement
    }
    
    if (diffElements.all { it.isNothingToUpdate }) {
        return false
    }
    
    // 3. 合并差异
    merge(fullNode, diffElements)
    
    // 4. 写入文件
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(fullNode.printXml())
    return true
}
```

### 4.3 节点合并

```kotlin
private fun merge(fullNode: Element, diffElement: ManifestDiffResult.DiffElement) {
    // 1. 更新属性
    diffElement.changedAttributes.forEach {
        if (it.nodeName.startsWith("tools:")) {
            logger.debug("ignore tools attribute \"${it.nodeName}\"")
            return@forEach
        }
        if (fullNode.nodeName == "manifest" && it.nodeName == "package") {
            logger.debug("ignore package name update (${it.nodeValue})")
            return@forEach
        }
        if (fullNode.nodeName == "application" && it.nodeName == "android:name") {
            logger.debug("ignore application name update (${it.nodeValue})")
            return@forEach
        }
        fullNode.setAttribute(it.nodeName, it.nodeValue)
        logger.debug("update attribute \"${it.nodeName}\"=\"${it.nodeValue}\" for node <${fullNode.uniqueKey}>")
    }
    
    // 2. 合并子节点
    val nodeMatcher = ManifestNodeMatcher(diffElement.node.childNodes, fullNode.childNodes)
    diffElement.changedChildren.forEach { diffChildNode ->
        val relativeNode = nodeMatcher.findRelativeChild(diffChildNode.node)
        if (relativeNode == null) {
            if (diffChildNode.isNewNode) {
                // 插入新节点
                val newNode = fullNode.importChildNotDeep(diffChildNode.node, isExcludeToolsAttribute = true)
                logger.debug("insert new node ${newNode.uniqueKey} for node <${fullNode.uniqueKey}>")
                merge(newNode, diffChildNode)
            }
        } else if (relativeNode.nodeType == Node.ELEMENT_NODE) {
            // 递归合并
            merge(relativeNode as Element, diffChildNode)
        }
    }
}
```

### 4.4 合并示例

**基准 Manifest**:
```xml
<manifest package="com.example.app">
    <application android:name=".MyApplication">
        <activity android:name=".MainActivity" />
    </application>
</manifest>
```

**库模块 Manifest**:
```xml
<manifest package="com.example.library">
    <application>
        <activity android:name=".LibraryActivity" />
        <service android:name=".LibraryService" />
    </application>
</manifest>
```

**合并后 Manifest**:
```xml
<manifest package="com.example.app">
    <application android:name=".MyApplication">
        <activity android:name=".MainActivity" />
        <activity android:name="com.example.library.LibraryActivity" />
        <service android:name="com.example.library.LibraryService" />
    </application>
</manifest>
```

---

## 五、ClassMinifyCompiler - Class 混淆编译器

### 5.1 核心职责

**定义位置**: `ClassMinifyCompiler.kt`

| 职责 | 说明 |
|------|------|
| **二次混淆** | 对增量编译的 Class 文件进行二次混淆 |
| **Mapping 读取** | 读取 R8 mapping.txt 文件 |
| **字节码重映射** | 使用 ASM 重映射类名/方法名/字段名 |
| **一致性保证** | 确保增量编译产物与原 APK 混淆一致 |

**支持类型**: `Class`

### 5.2 编译流程

```kotlin
override fun compile(task: CompileTask): CompileResult {
    // 1. 初始化混淆器
    initIfNeeded(task)?.let { failedResult ->
        return failedResult
    }
    
    // 2. 处理 Class 文件
    return process(task)
}

private fun initIfNeeded(task: CompileTask): CompileResult? {
    if (!task.isNeedCompile) {
        return task.wrapToResult()
    }
    
    // 查找 mapping 文件
    val mappingFile = context.mappingFile
    if (!context.isMinified || mappingFile == null || !mappingFile.exists()) {
        if (context.isReleaseApk) {
            logger.warn("This appears to be a release build, but mapping file not found, skip obfuscation.")
        } else {
            logger.debug("No mapping file found, skip obfuscation.")
        }
        return task.wrapToResult()
    }
    
    // 初始化混淆器
    if (!::obfuscator.isInitialized) {
        logger.debug("Loading mapping file: ${mappingFile.absolutePath}")
        obfuscator = ClassObfuscator.fromMappingFile(mappingFile)
        val stats = obfuscator.getMappingStats()
        logger.debug("Mapping loaded: ${stats.classCount} classes, ${stats.fieldCount} fields, ${stats.methodCount} methods")
    }
    
    return null
}
```

### 5.3 Class 文件混淆

```kotlin
private fun process(task: CompileTask): CompileResult {
    val details = mutableListOf<Result<CompileFile, CompileError>>()
    val outputs = mutableListOf<CompileOutput>()
    
    for (compileFile in task.files) {
        try {
            val result = obfuscateClassFile(compileFile, task.outputDir, obfuscator)
            if (result != null) {
                details.add(Result.success(compileFile))
                outputs.add(result)
            } else {
                // 无映射，原样输出
                val output = copyClassFile(compileFile, task.outputDir)
                details.add(Result.success(compileFile))
                outputs.add(output)
            }
        } catch (e: Exception) {
            logger.debug("Failed to obfuscate ${compileFile.file.name}", e)
            details.add(Result.failure(CompileError(compileFile, listOf(-1L to (e.message ?: "Unknown error")))))
        }
    }
    
    return CompileResult(task, details, outputs)
}
```

**混淆单个 Class 文件**:
```kotlin
private fun obfuscateClassFile(
    compileFile: CompileFile,
    outputDir: File,
    obfuscator: ClassObfuscator
): CompileOutput? {
    val inputFile = compileFile.file
    val baseDir = compileFile.baseDir
    
    // 获取混淆后的输出路径
    val obfuscatedPath = obfuscator.getObfuscatedClassPath(inputFile, baseDir)
    val outputFile = File(outputDir, obfuscatedPath)
    
    // 读取并混淆
    val inputBytes = inputFile.readBytes()
    val outputBytes = obfuscator.obfuscate(inputBytes)
    
    if (outputBytes != null) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(outputBytes)
        logger.debug("Obfuscated: ${inputFile.name} -> ${outputFile.name}")
        return CompileOutput(CompileOutput.Type.Class, outputFile, outputDir)
    }
    
    return null
}
```

---

## 六、ClassObfuscator - ASM 字节码混淆器

### 6.1 核心职责

**定义位置**: `ClassObfuscator.kt`

| 职责 | 说明 |
|------|------|
| **字节码重映射** | 使用 ASM 重映射类名/方法名/字段名 |
| **Mapping 索引** | 构建高效的映射索引 |
| **路径计算** | 计算混淆后的输出路径 |
| **统计信息** | 提供映射统计信息 |

### 6.2 映射索引构建

```kotlin
init {
    val classMap = mutableMapOf<String, String>()
    val fieldMap = mutableMapOf<String, String>()
    val methodMap = mutableMapOf<String, String>()
    
    mappingReader.forEachClass { _, classMapping ->
        // 类名映射 (internal format)
        val originalInternal = classMapping.originalName.replace('.', '/')
        val obfuscatedInternal = classMapping.obfuscatedName.replace('.', '/')
        classMap[originalInternal] = obfuscatedInternal
        
        // 字段映射
        classMapping.fields.forEach { field ->
            val key = "${classMapping.originalName}.${field.originalName}"
            fieldMap[key] = field.obfuscatedName
        }
        
        // 方法映射
        classMapping.methods.forEach { method ->
            val key = "${classMapping.originalName}.${method.originalName}(${method.parameters})"
            methodMap[key] = method.obfuscatedName
        }
    }
    
    classNameMap = classMap
    fieldNameMap = fieldMap
    methodNameMap = methodMap
}
```

### 6.3 字节码混淆

```kotlin
fun obfuscate(classBytes: ByteArray): ByteArray? {
    return obfuscate(classBytes.inputStream())
}

fun obfuscate(inputStream: InputStream): ByteArray? {
    val classReader = ClassReader(inputStream)
    val classWriter = ClassWriter(classReader, 0)
    
    val remapper = ObfuscationRemapper()
    val classRemapper = ClassRemapper(classWriter, remapper)
    
    classReader.accept(classRemapper, ClassReader.EXPAND_FRAMES)
    
    return if (remapper.hasRemapped) {
        classWriter.toByteArray()
    } else {
        null
    }
}
```

### 6.4 ASM Remapper 实现

**类名重映射**:
```kotlin
override fun map(internalName: String): String {
    val mapped = classNameMap[internalName]
    if (mapped != null) {
        hasRemapped = true
        return mapped
    }
    return internalName
}
```

**字段名重映射**:
```kotlin
override fun mapFieldName(owner: String, name: String, descriptor: String): String {
    val ownerDot = owner.replace('/', '.')
    val key = "$ownerDot.$name"
    val mapped = fieldNameMap[key]
    if (mapped != null) {
        hasRemapped = true
        return mapped
    }
    return name
}
```

**方法名重映射**:
```kotlin
override fun mapMethodName(owner: String, name: String, descriptor: String): String {
    // 跳过特殊方法
    if (name == "<init>" || name == "<clinit>") {
        return name
    }
    
    val ownerDot = owner.replace('/', '.')
    val params = descriptorToParams(descriptor)
    val key = "$ownerDot.$name($params)"
    val mapped = methodNameMap[key]
    if (mapped != null) {
        hasRemapped = true
        return mapped
    }
    return name
}
```

**方法描述符转换**:
```kotlin
private fun descriptorToParams(descriptor: String): String {
    val params = mutableListOf<String>()
    var i = 1 // 跳过开头的 '('
    
    while (i < descriptor.length && descriptor[i] != ')') {
        when (descriptor[i]) {
            'B' -> { params.add("byte"); i++ }
            'C' -> { params.add("char"); i++ }
            'D' -> { params.add("double"); i++ }
            'F' -> { params.add("float"); i++ }
            'I' -> { params.add("int"); i++ }
            'J' -> { params.add("long"); i++ }
            'S' -> { params.add("short"); i++ }
            'Z' -> { params.add("boolean"); i++ }
            'V' -> { params.add("void"); i++ }
            'L' -> {
                val end = descriptor.indexOf(';', i)
                val className = descriptor.substring(i + 1, end).replace('/', '.')
                params.add(className)
                i = end + 1
            }
            '[' -> {
                var arrayDepth = 0
                while (descriptor[i] == '[') {
                    arrayDepth++
                    i++
                }
                val baseType = when (descriptor[i]) {
                    'B' -> { i++; "byte" }
                    'C' -> { i++; "char" }
                    // ... 其他类型
                    'L' -> {
                        val end = descriptor.indexOf(';', i)
                        val className = descriptor.substring(i + 1, end).replace('/', '.')
                        i = end + 1
                        className
                    }
                    else -> { i++; "unknown" }
                }
                params.add(baseType + "[]".repeat(arrayDepth))
            }
            else -> i++
        }
    }
    
    return params.joinToString(",")
}
```

**示例**:
- `(Ljava/lang/String;I)V` → `java.lang.String,int`
- `([I[[Ljava/lang/Object;)Z` → `int[],java.lang.Object[][],boolean`

---

## 七、R8MappingReader - Mapping 文件解析器

### 7.1 核心职责

**定义位置**: `R8MappingReader.kt`

| 职责 | 说明 |
|------|------|
| **Mapping 解析** | 解析 R8/ProGuard mapping.txt 文件 |
| **映射查询** | 提供类名/方法名/字段名映射查询 |
| **双向查询** | 支持原始名→混淆名和混淆名→原始名查询 |
| **统计信息** | 提供映射统计信息 |

### 7.2 数据结构

```kotlin
data class ClassMapping(
    val originalName: String,
    val obfuscatedName: String,
    val fields: List<FieldMapping>,
    val methods: List<MethodMapping>
)

data class FieldMapping(
    val type: String,
    val originalName: String,
    val obfuscatedName: String
)

data class MethodMapping(
    val returnType: String,
    val originalName: String,
    val parameters: String,
    val obfuscatedName: String,
    val lineRange: IntRange?
)
```

### 7.3 Mapping 文件格式

**标准 R8/ProGuard mapping.txt 格式**:
```
com.example.MyClass -> a.b.c:
    int myField -> a
    java.lang.String myMethod(int,java.lang.String) -> b
    void onCreate(android.os.Bundle) -> onCreate
```

**解析逻辑**:
```kotlin
private fun convertClassNaming(classNaming: ClassNamingForNameMapper): ClassMapping {
    val fields = mutableListOf<FieldMapping>()
    val methods = mutableListOf<MethodMapping>()
    
    // 获取字段映射
    classNaming.allFieldNamings().forEach { memberNaming ->
        val signature = memberNaming.originalSignature as? MemberNaming.FieldSignature ?: return@forEach
        fields.add(
            FieldMapping(
                type = signature.type,
                originalName = signature.name,
                obfuscatedName = memberNaming.renamedName
            )
        )
    }
    
    // 获取方法映射
    classNaming.allMethodNamings().forEach { memberNaming ->
        val signature = memberNaming.originalSignature as? MemberNaming.MethodSignature ?: return@forEach
        methods.add(
            MethodMapping(
                returnType = signature.type,
                originalName = signature.name,
                parameters = signature.parameters.joinToString(","),
                obfuscatedName = memberNaming.renamedName,
                lineRange = null
            )
        )
    }
    
    return ClassMapping(
        originalName = classNaming.originalName,
        obfuscatedName = classNaming.renamedName,
        fields = fields,
        methods = methods
    )
}
```

### 7.4 查询接口

**类名查询**:
```kotlin
// 原始名 → 混淆名
fun getObfuscatedClassName(originalName: String): String? {
    return mapper.classNameMappings.entries.find { it.value.originalName == originalName }?.key
}

// 混淆名 → 原始名
fun getOriginalClassName(obfuscatedName: String): String? {
    return mapper.getClassNaming(obfuscatedName)?.originalName
}
```

**详细映射查询**:
```kotlin
// 通过混淆名查询
fun getClassMapping(obfuscatedName: String): ClassMapping? {
    val classNaming = mapper.getClassNaming(obfuscatedName) ?: return null
    return convertClassNaming(classNaming)
}

// 通过原始名查询
fun getClassMappingByOriginalName(originalName: String): ClassMapping? {
    val obfuscatedName = getObfuscatedClassName(originalName) ?: return null
    return getClassMapping(obfuscatedName)
}
```

**前缀查询**:
```kotlin
// 查找所有原始名以指定前缀开头的类
fun findClassesByOriginalPrefix(prefix: String): List<ClassMapping> {
    return mapper.classNameMappings.values
        .filter { it.originalName.startsWith(prefix) }
        .map { convertClassNaming(it) }
}

// 查找所有混淆名以指定前缀开头的类
fun findClassesByObfuscatedPrefix(prefix: String): List<ClassMapping> {
    return mapper.classNameMappings
        .filter { it.key.startsWith(prefix) }
        .map { convertClassNaming(it.value) }
}
```

---

## 八、设计亮点总结

### 8.1 Manifest 增量合并

| 亮点 | 说明 |
|------|------|
| **差异分析** | 仅合并变更的部分，避免全量合并 |
| **占位符替换** | 支持 `${applicationId}` 等占位符 |
| **节点匹配** | 智能匹配新旧节点，支持多种匹配策略 |
| **CRC 检测** | 检测文件是否变更，避免不必要的合并 |
| **工具属性过滤** | 自动过滤 `tools:*` 属性 |

### 8.2 Class 二次混淆

| 亮点 | 说明 |
|------|------|
| **一致性保证** | 确保增量编译产物与原 APK 混淆一致 |
| **高效索引** | 构建映射索引，提升查询效率 |
| **ASM 字节码操作** | 使用 ASM 进行字节码级别的重映射 |
| **智能降级** | 无 mapping 文件时自动跳过混淆 |
| **统计信息** | 提供详细的映射统计信息 |

### 8.3 容错设计

| 容错点 | 说明 |
|--------|------|
| **Manifest 缺失** | 基准 Manifest 缺失时提示降级 |
| **Mapping 缺失** | Release 构建缺失 mapping 时警告 |
| **占位符缺失** | 占位符缺失时保持原值 |
| **节点匹配失败** | 匹配失败时作为新节点插入 |

### 8.4 性能优化

| 优化点 | 说明 |
|--------|------|
| **CRC 快速检测** | 使用 CRC 快速检测文件变更 |
| **映射索引** | 构建 HashMap 索引，O(1) 查询 |
| **懒加载** | 仅在需要时加载 mapping 文件 |
| **增量合并** | 仅合并变更的节点 |

---

## 九、总结

### 9.1 Manifest 编译器

**核心功能**:
- 增量合并多个模块的 Manifest 文件
- 智能占位符替换（`${applicationId}`, `${namespace}`, `.MainActivity` 等）
- 差异分析和节点匹配
- CRC 检测避免不必要的合并

**关键技术**:
- 节点唯一键生成（`android:name`, `android:host`, `android:scheme` 等）
- 递归差异分析和合并
- 工具属性过滤（`tools:*`, `xmlns:tools`）
- 忽略规则（`uses-sdk`, `versionCode`, `versionName` 等）

### 9.2 Class 混淆编译器

**核心功能**:
- 对增量编译的 Class 文件进行二次混淆
- 读取 R8 mapping.txt 文件
- 使用 ASM 重映射类名/方法名/字段名
- 确保增量编译产物与原 APK 混淆一致

**关键技术**:
- R8 mapping.txt 解析（基于 R8 ClassNameMapper）
- ASM ClassRemapper 字节码重映射
- 方法描述符转换（JVM 格式 → 简单格式）
- 高效映射索引（HashMap）

---

**文档状态**: ✅ 已完成  
**下一步**: 完成阶段 2 的剩余步骤，或开始阶段 3 - 部署模块分析
