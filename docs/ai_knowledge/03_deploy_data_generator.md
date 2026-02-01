# Jugg 部署系统 - 增量影响分析与类结构变更检测

> 文档版本: v1.0
> 创建时间: 2026-02-01
> 涵盖模块: deploy/data/*.kt

---

## 一、模块概览

### 1.1 核心职责

`DeployDataGenerator` 是 Jugg 增量部署的核心大脑,负责在编译单个文件后分析类结构变化,找出受影响的其他类并决定是否需要重新编译。这是实现精确增量编译的关键机制。

| 核心能力 | 说明 |
|---------|------|
| **类结构比较** | 对比新旧 Class 的字段、方法、接口变化 |
| **影响分析** | 找出引用了变更方法/字段的所有类 |
| **部署策略决策** | 判断是 hot_reload (代码热替换) 还是 hot_fix (需重启) |
| **增量数据库管理** | 维护已部署 APK 和增量变更的双层缓存 |
| **Desugar 支持** | 检测 Java 8+ 特性的 Desugar 依赖 |

### 1.2 架构位置

```
编译完成 (CompileOutput)
    ↓
DeployDataGenerator.buildDeployData()
    ├─ 1. 解析新编译的 Dex (ApkParser)
    ├─ 2. 从数据库读取旧 ClassNode
    ├─ 3. 逐类比较 (ClassNodeComparator)
    │   ├─ 判断结构是否变化
    │   ├─ 识别变更的方法/字段
    │   └─ 决定 hot_reload vs hot_fix
    ├─ 4. 影响分析 (DeployDataDatabase)
    │   ├─ 查找方法引用 (methodRefs)
    │   ├─ 查找字段引用 (fieldRefs)
    │   ├─ 查找子类 (subclassRefs)
    │   └─ 检测 Minify 删除的类
    ├─ 5. 内联方法检测 (InlineMethodDetector)
    │   └─ 检测 R8/ProGuard 内联导致的影响
    └─ 6. 生成部署数据 (JuggDeployData)
        ├─ newClasses (新增类)
        ├─ hotReloadModifiedClasses (可热替换)
        ├─ hotFixModifiedClasses (需重启)
        └─ effectedSourceAndClassNodes (受影响需重编译)
```

---

## 二、ClassNodeComparator - 类结构比较器

### 2.1 核心算法

**定义位置**: `ClassNodeComparator.kt`

**工作原理**: 对比新旧 ClassNode 的各个维度,识别结构性变更。

```kotlin
class ClassNodeComparator(
    private val oldClassNode: ClassNode,
    private val newClassNode: ClassNode,
) {
    fun compare(): ClassNodeDiffResult {
        // 1. 比较父类
        if (oldClassNode.superClass != newClassNode.superClass) {
            modifiedParentClass.add(oldClassNode.superClass to newClassNode.superClass)
        }

        // 2. 比较接口 (使用高效的 LinkedList 差集算法)
        val addedInterfaces = LinkedList(newClassNode.interfaceNames)
        val deletedInterfaces = LinkedList(oldClassNode.interfaceNames)
        removeUnion(addedInterfaces, deletedInterfaces)

        // 3. 比较字段
        val addedFields = LinkedList(newClassNode.fields)
        val deletedFields = LinkedList(oldClassNode.fields)
        removeUnion(addedFields, deletedFields)

        // 4. 比较方法
        val addedMethods = LinkedList(newClassNode.methods)
        val deletedMethods = LinkedList(oldClassNode.methods)
        removeUnion(addedMethods, deletedMethods)

        // 5. 比较有效影响方法 (排除 abstract/private 变化)
        val effectMethods = calculateEffectMethods()

        return ClassNodeDiffResult(...)
    }
}
```

### 2.2 差集算法优化

**关键设计**: 使用 LinkedList 而非 HashSet,因为在大多数情况下,类的方法/字段顺序基本不变,LinkedList 的顺序遍历更高效。

```kotlin
private fun <T> removeUnion(list1: LinkedList<T>, list2: LinkedList<T>) {
    list1.iterator().let { iterator ->
        while (iterator.hasNext()) {
            val newElement = iterator.next()
            val oldElement = list2.find { it == newElement }
            if (oldElement != null) {
                iterator.remove()  // 从新列表移除
                list2.remove(oldElement)  // 从旧列表移除
            }
        }
    }
    // 最终 list1 = 新增元素, list2 = 删除元素
}
```

### 2.3 热替换能力判断

**关键方法**: `ClassNodeDiffResult.isCanHotReload`

**规则**: 满足以下所有条件才可 hot_reload:

```kotlin
val isCanHotReload
    get() = modifiedParentClass.isEmpty() &&          // 父类未变
            addedInterfaces.isEmpty() &&              // 未新增接口
            deletedInterfaces.isEmpty() &&            // 未删除接口
            deletedFields.isEmpty() &&                // 未删除字段
            deletedMethods.isEmpty() &&               // 未删除方法
            addedFields.filter {                      // 新增字段仅限非静态基本类型
                val isStatic = (it.access and DexConstants.ACC_STATIC) != 0
                if (!isStatic) return@filter false
                val isPrimitive = it.type in listOf("Z", "B", "C", "S", "I", "J", "F", "D")
                if (isPrimitive) return@filter false
                return@filter true  // 非静态非基本类型字段不可 hot_reload
            }.isEmpty()
```

**设计亮点**:
- JVMTI 的 `RedefineClasses` 只能修改方法体,不能变更类结构
- 允许新增静态基本类型字段 (如 `static final int`)
- 不允许新增非静态字段或对象类型静态字段

### 2.4 有效影响方法过滤

**问题**: 并非所有方法变更都需要触发重编译

**解决**: `MethodNode.isEffectedChanged()` 过滤无关变更:

```kotlin
fun MethodNode.isEffectedChanged(other: MethodNode): Boolean {
    if (name != other.name || desc != other.desc) return false

    // 忽略 abstract 变化 (不影响字节码)
    val isAbstract = (access and DexConstants.ACC_ABSTRACT) != 0
    val isOtherAbstract = (other.access and DexConstants.ACC_ABSTRACT) != 0
    if (isAbstract != isOtherAbstract) return true

    // 忽略 private 方法 (不被外部引用)
    val isPrivate = (access and DexConstants.ACC_PRIVATE) != 0
    if (isPrivate) return true

    return false
}
```

**场景示例**:
1. Redex 优化将 interface 默认方法移到实现类 → abstract 标记变化 → 可忽略
2. 内部实现修改为 private 方法 → 外部不可见 → 可忽略

---

## 三、DeployDataDatabase - 双层数据库架构

### 3.1 架构设计

**定义位置**: `DeployDataDatabase.kt`

```
DeployDataDatabase (总协调器)
    ├─ 应用维度数据库 (每个 applicationId 一个 SQLite)
    │   ├─ DeployDataDatabaseSqLiteHelper (APK 基线数据)
    │   │   ├─ ClassNode 表
    │   │   ├─ MethodRef 引用表
    │   │   ├─ FieldRef 引用表
    │   │   ├─ SubclassRef 继承表
    │   │   └─ ResInfo 资源信息表
    │   └─ 增量更新策略 (变化 >= 20% 时全量重建)
    └─ IncrementalDeployDataDatabase (内存增量缓存)
        ├─ deployedClasses: Map<className, ClassNode>
        ├─ methodRefs: Map<methodKey, List<refClassName>>
        ├─ fieldRefs: Map<fieldKey, List<refClassName>>
        └─ subclassRefs: Map<superClass, List<subclass>>
```

**设计亮点**:
1. **双层架构**: APK 基线 (SQLite) + 增量变更 (内存),查询时优先内存
2. **应用隔离**: 多 APK 项目各自独立数据库,避免数据冲突
3. **智能重建**: 变化超过 20% 时全量重建,避免增量更新性能下降

### 3.2 初始化流程

**方法**: `init(apks: List<ApkInfo>, deployedItems: List<DeployItem>)`

```kotlin
fun init(apks: List<ApkInfo>, deployedItems: List<DeployItem>): List<ParsedApkUpdateResult> {
    // 1. 按 applicationId 分组
    apks.groupBy { it.applicationId }.forEach { (applicationId, apkFileUnits) ->
        val dbFile = File(dbDir, "$applicationId.db")
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)

        // 2. 解析 APK 内容
        val apkEntries = ApkParser().parseEntries(apkFile)
        val diffResult = helper.diffApk(apkEntries)

        // 3. 判断增量 vs 全量
        val allChangedDexFileSize = diffResult.removedDexFiles.size +
                                    diffResult.addedDexFiles.size +
                                    diffResult.updatedDexFiles.size
        val isFullUpdate = allChangedDexFileSize > 3 ||
                          (dexFileSize > 0 && allChangedDexFileSize >= dexFileSize * 0.2)

        if (isFullUpdate) {
            helper.recreateDatabase()  // 全量重建
        }

        // 4. 保存数据
        helper.saveParsedApkBatch(parsedList, diffs)
    }

    // 5. 初始化增量缓存
    incDeployedDatabase.init(deployedItems)

    // 6. 清理过期数据库
    clearDeprecatedDatabases()
}
```

**触发时机**:
- APK 首次安装
- APK 版本更新
- Gradle 全量编译后

### 3.3 影响分析算法

**方法**: `getEffectedSourceAndClass()`

**核心逻辑**: 多维度查找受影响的类

```kotlin
fun getEffectedSourceAndClass(
    changedMethodRefs: List<MethodNode>,    // 删除/修改的方法
    changedFieldRefs: List<FieldNode>,      // 删除的字段
    changedAbstractClasses: List<ClassNode>, // 新增抽象方法的类
    maybeMinifiedRemoveClasses: ParsedDex?,  // 可能被混淆删除的类
): List<EffectedClassNode> {

    // 1. 方法引用影响 (含子类继承链)
    changedMethodRefs.forEach { methodNode ->
        // 1.1 查找直接引用
        methodRefs[methodNode.matchKey]?.forEach { className ->
            effectClassNodes[className] = ...
        }

        // 1.2 查找子类覆写影响
        var classesToCheck = setOf(methodNode.owner)
        while (classesToCheck.isNotEmpty()) {
            classesToCheck.forEach { superClass ->
                subclassRefs[superClass]?.forEach { subclass ->
                    // 为子类生成虚拟 MethodNode
                    val subclassMethodNode = MethodNode(subclass, ...)
                    // 如果子类未覆写,继续向下查找
                    if (!subclassNode.methods.contains(methodNode)) {
                        classesToCheck.add(subclass)
                    }
                }
            }
        }
    }

    // 2. 字段引用影响
    changedFieldRefs.forEach { fieldNode ->
        fieldRefs[fieldNode.matchKey]?.forEach { className ->
            effectClassNodes[className] = ...
        }
    }

    // 3. 抽象类影响 (子类必须实现新增方法)
    var toCheckAbstractClasses = changedAbstractClasses.toList()
    while (toCheckAbstractClasses.isNotEmpty()) {
        toCheckAbstractClasses.forEach { superClass ->
            subclassRefs[superClass.className]?.forEach { subclassName ->
                val subclass = getClassNode(subclassName)
                if (!subclass.isAbstract) {
                    // 非抽象子类必须重新编译
                    effectClassNodes[subclassName] = ...
                }
            }
        }
    }

    // 4. Minify 删除类影响
    helper.getEffectedClassNodesForMinify(maybeMinifiedRemoveClasses)

    return effectClassNodes.values.toList()
}
```

**算法亮点**:
1. **继承链递归**: 父类方法变更会影响所有子类的调用点
2. **抽象类传播**: 新增抽象方法会强制所有非抽象子类重新编译
3. **Minify 检测**: 检测 R8/ProGuard 删除的类引用

### 3.4 引用索引结构

**核心数据结构**:

```kotlin
// 方法引用: "com/example/Foo.bar(I)V" -> ["com/example/Caller1", "com/example/Caller2"]
val methodRefs: Map<String, List<String>>

// 字段引用: "com/example/Foo.count" -> ["com/example/Reader1"]
val fieldRefs: Map<String, List<String>>

// 子类引用: "com/example/Base" -> ["com/example/Child1", "com/example/Child2"]
val subclassRefs: Map<String, List<String>>
```

**索引构建**: 由 `ApkParser.parse()` 在解析 Dex 时构建

---

## 四、DeployDataGenerator - 部署数据生成器

### 4.1 核心方法

**方法签名**:
```kotlin
fun buildDeployData(
    items: List<DeployItem>,                      // 编译产物
    isWarmUp: Boolean = false,                    // 是否预热
    isNeedCheckRecompile: Boolean = true,         // 是否检查重编译
    isNeedCheckRecompileMinifyRemovedClass: Boolean = false,  // 是否检查混淆删除
    isCompilingEffectedSourceFiles: Boolean = false,  // 是否正在编译受影响文件
): JuggDeployData
```

### 4.2 处理流程

```kotlin
fun buildDeployData(...): JuggDeployData {
    // 1. 解析新编译的 Dex
    val changedDex = items.filter { it.type == CompileOutput.Type.Dex }
    val parsedDex = ApkParser().parseDex(changedDex, isSkipOfficialClass = !isNeedCheckRecompileMinifyRemovedClass)

    val changedClasses = parsedDex.classDeployItems

    // 2. 从数据库读取旧 ClassNode
    val oldClassNodes = deployDataDatabase.getClassNodes(
        changedClasses.flatMap { it.classNodes.map(ClassNode::className) }
    )

    // 3. 逐类比较并分类
    val newClasses = mutableListOf<ClassDeployItem>()
    val hotReloadModifiedClasses = mutableListOf<ClassDeployItem>()
    val hotFixModifiedClasses = mutableListOf<ClassDeployItem>()
    val changedMethodRef = mutableListOf<MethodNode>()
    val changedFieldRef = mutableListOf<FieldNode>()
    val changedAbstractClasses = mutableListOf<ClassNode>()

    changedClasses.forEach { classDeployItem ->
        // 3.1 判断是否为多 Dex / 库 Dex
        if (classDeployItem.isMultipleDex || classDeployItem.isLibraryDex) {
            hotFixModifiedClasses.add(classDeployItem)
            return@forEach
        }

        classDeployItem.classNodes.forEach { newClassNode ->
            val oldClassNode = oldClassNodes[newClassNode.className]

            // 3.2 新增类
            if (oldClassNode == null) {
                newClasses.add(classDeployItem)
                return@forEach
            }

            // 3.3 比较类结构
            val result = ClassNodeComparator(oldClassNode, newClassNode).compare()

            if (result.isCanHotReload) {
                hotReloadModifiedClasses.add(classDeployItem)
            } else {
                hotFixModifiedClasses.add(classDeployItem)
            }

            // 3.4 收集影响数据 (排除 R 子类)
            if (!newClassNode.isRSubClass) {
                changedMethodRef.addAll(result.effectMethods)
                changedFieldRef.addAll(result.deletedFields)
                if (result.isAddedAbstractMethodForNonAbstractClass) {
                    changedAbstractClasses.add(newClassNode)
                }
            }
        }
    }

    // 4. 处理资源首次部署
    var overlays = changedOverlays
    val isFullRes = isWarmUp || (overlays.isNotEmpty() && !deployDataDatabase.isDeployedOverlaysBefore())
    if (isFullRes) {
        overlays = deployDataDatabase.addFullRes(overlays, isNeedRes = true, isNeedAsset = false)
    }

    // 5. 影响分析
    val effectedSourceAndClassNodes = if (isNeedCheckRecompile) {
        val checkMinifiedRemoveClass = if (isNeedCheckRecompileMinifyRemovedClass) parsedDex else null
        val effectedNodes = deployDataDatabase.getEffectedSourceAndClass(
            changedMethodRef, changedFieldRef, changedAbstractClasses, checkMinifiedRemoveClass
        ).toMutableList()

        // 6. 内联方法检测 (仅在非重编译模式下)
        if (!isCompilingEffectedSourceFiles) {
            val inlineDetector = InlineMethodDetector(mappingFile, logger)
            val inlineEffectedNodes = inlineDetector.findInlineEffectedClasses(checkMinifiedRemoveClass)
            merge(effectedNodes, inlineEffectedNodes)
        }

        effectedNodes
    } else {
        emptyList()
    }

    // 7. 收集需要更新到 APK 的文件
    val updateApkFiles = mutableListOf<DeployItem>()
    if (changedOverlays.any { it.name == "AndroidManifest.xml" }) {
        updateApkFiles += changedOverlays.filter { it.name == "AndroidManifest.xml" }
        overlays.find { it.name == "resources.arsc" }?.let { updateApkFiles += it }
    }
    updateApkFiles += changedLibs

    // 8. 生成最终部署数据
    return JuggDeployData(
        apks = deployDataDatabase.getApkInfos(),
        newClasses = newClasses,
        hotFixModifiedClasses = hotFixModifiedClasses,
        hotReloadModifiedClasses = hotReloadModifiedClasses,
        effectedSourceAndClassNodes = effectedSourceAndClassNodes,
        overlays = overlays,
        parsedDex = parsedDex,
        isFullRes = isFullRes,
        isWarmUp = isWarmUp,
        updateApkFiles = updateApkFiles,
    )
}
```

### 4.3 R 子类特殊处理

**问题**: Android 资源 R 类的子类 (如 `R$drawable`) 在 Gradle Submodule 场景下会频繁变化

**解决方案**:
```kotlin
val isRSubClass = className.substringAfterLast("/").startsWith("R$")
if (isRSubClass) {
    // 不收集 R 子类的影响方法/字段
    logger.debug("class $className is R subclass, don't add effected methods and fields.")
    return@classNodes
}
```

**原因**:
1. R 子类只包含常量字段 (如 `public static final int icon = 0x7f080001`)
2. RDexForSubmoduleCompiler 会为每个 submodule 添加大量字段
3. RFileFixer 会删除大量字段 (未被引用的资源 ID)
4. 如果收集影响,会导致几乎所有引用 R 的源文件都被触发重编译

### 4.4 Desugar 信息获取

**方法**: `getDesugarInfo(classFiles: List<CompileFile>, apkFile: File): DesugarInfo`

**作用**: 检测 Java 8+ 特性 (Lambda, Stream, 默认方法) 的 Desugar 依赖

```kotlin
fun getDesugarInfo(classFiles: List<CompileFile>, apkFile: File): DesugarInfo {
    val files = classFiles.map { it.file }
    val parser = ClassFileParser(files)
    parser.parse()

    // 1. 查找所有包含默认方法的接口
    val allInterfacesWithDefaultMethod = deployDataDatabase.getAllInterfacesWithDefaultMethod(
        parser.interfaces.toList(),
        parser.staticInvocationRefs.toList()
    )

    // 2. 获取核心库重写映射 (如 java.time -> j$.time)
    val coreLibraryRewriteClassMap = deployDataDatabase.getCoreLibraryRewriteClassMap(apkFile)

    return DesugarInfo(
        allInterfacesWithDefaultMethod,
        coreLibraryRewriteClassMap,
        isNeedRewriteCoreLibrary = coreLibraryRewriteClassMap.isNotEmpty(),
        desugaredLibraryConfiguration = null
    )
}
```

**Desugar 默认方法检测**:
- 接口 `com/example/Foo` 如果包含默认方法,会生成 `com/example/Foo$-CC` 类
- 通过检测 `-CC` / `-CC2` 后缀类判断接口是否有默认方法

---

## 五、InlineMethodDetector - 内联方法检测

### 5.1 问题背景

**R8/ProGuard 内联优化**:
```java
// 原始代码
class Utils {
    static int add(int a, int b) { return a + b; }
}

class Caller {
    void test() {
        int result = Utils.add(1, 2);  // 调用工具方法
    }
}

// R8 内联后
class Caller {
    void test() {
        int result = 1 + 2;  // 方法被内联
    }
}
```

**增量编译问题**:
- 修改 `Utils.add()` 的实现
- Jugg 只重新编译 `Utils` 类
- `Caller` 中的代码已被内联,不会自动更新
- 导致运行时仍使用旧逻辑

### 5.2 检测算法

**核心逻辑**: 通过 ProGuard mapping 文件分析内联关系

```kotlin
class InlineMethodDetector(
    private val mappingFile: File?,
    private val logger: Logger
) {
    fun findInlineEffectedClasses(parsedDex: ParsedDex?): List<EffectedClassNode> {
        if (mappingFile == null || parsedDex == null) return emptyList()

        val inlineGraph = parseMappingFile(mappingFile)
        val changedClasses = parsedDex.classDeployItems.flatMap { it.classNodes }

        val effectedNodes = mutableListOf<EffectedClassNode>()

        changedClasses.forEach { changedClass ->
            changedClass.methods.forEach { method ->
                // 查找被内联到哪些类
                val inlinedTo = inlineGraph.getInlinedTo(changedClass.className, method.name, method.desc)
                inlinedTo.forEach { targetClass ->
                    effectedNodes.add(EffectedClassNode(
                        className = targetClass,
                        source = getSourceFile(targetClass),
                        effectedByClasses = listOf(changedClass.className),
                        type = EffectedClassNode.EffectedType.INLINE
                    ))
                }
            }
        }

        return effectedNodes
    }
}
```

**Mapping 文件格式**:
```
# ProGuard mapping file
com.example.Utils -> a:
    1:1:int add(int,int):10:10 -> b
    # 表示 Utils.add 被混淆为 a.b

com.example.Caller -> c:
    1:1:void test():5:5 -> d
    # inlined from com.example.Utils.add:10:10
    # 表示 Caller.test 内联了 Utils.add
```

### 5.3 应用场景

**何时启用**:
```kotlin
if (!isCompilingEffectedSourceFiles) {
    // 仅在主编译流程中检测,受影响文件重编译时跳过
    val inlineEffectedNodes = inlineDetector.findInlineEffectedClasses(parsedDex)
    merge(effectedNodes, inlineEffectedNodes)
}
```

**原因**:
- 受影响文件重编译时,逻辑未变化,内联的代码仍然有效
- 仅在主文件修改时需要检测内联影响

---

## 六、JuggDeployData - 部署数据结构

### 6.1 数据模型

```kotlin
data class JuggDeployData(
    val apks: List<ApkInfo>,                          // APK 信息
    val newClasses: List<ClassDeployItem>,            // 新增类 → hot_fix
    val hotFixModifiedClasses: List<ClassDeployItem>, // 需重启的修改类 → hot_fix
    val hotReloadModifiedClasses: List<ClassDeployItem>, // 可热替换的修改类 → hot_reload
    val effectedSourceAndClassNodes: List<EffectedClassNode>, // 受影响需重编译的类
    val overlays: List<DeployItem>,                   // 资源/Assets 覆盖
    val parsedDex: ParsedDex,                         // 解析后的 Dex 数据
    val isFullRes: Boolean,                           // 是否全量资源部署
    val isWarmUp: Boolean,                            // 是否预热模式
    val updateApkFiles: List<DeployItem>,             // 需要更新到 APK 的文件
)
```

### 6.2 EffectedClassNode - 受影响类节点

```kotlin
data class EffectedClassNode(
    val className: String,              // 类名
    val source: String,                 // 源文件名 (如 "MainActivity.kt")
    val effectedByClasses: List<String>, // 被哪些类影响
    val type: EffectedType              // 影响类型
)

enum class EffectedType {
    SOURCE,   // 源码引用影响
    INLINE,   // 内联方法影响
    MINIFY    // 混淆删除影响
}
```

### 6.3 部署策略映射

| 分类 | 部署方式 | 是否重启 | 说明 |
|------|---------|---------|------|
| `newClasses` | hot_fix (fullSwap) | Activity 重启 | 新增类需要加载器注册 |
| `hotFixModifiedClasses` | hot_fix (fullSwap) | Activity 重启 | 结构变更无法热替换 |
| `hotReloadModifiedClasses` | hot_reload (codeSwap) | 否 | 仅方法体修改,JVMTI 热替换 |
| `effectedSourceAndClassNodes` | 触发重编译 | - | 间接影响,需重新编译 |

---

## 七、完整流程示例

### 7.1 场景: 修改工具类方法

**初始状态**:
```kotlin
// Utils.kt
class Utils {
    fun calculate(x: Int): Int = x * 2
}

// MainActivity.kt
class MainActivity {
    fun test() {
        val result = Utils().calculate(10)  // 引用 Utils.calculate
    }
}
```

**修改操作**:
```kotlin
// Utils.kt (修改实现)
class Utils {
    fun calculate(x: Int): Int = x * 3  // 改为 x3
}
```

**Jugg 处理流程**:

```
1. 编译 Utils.kt → Utils.class → Utils.dex

2. DeployDataGenerator.buildDeployData()
   ├─ 解析 Utils.dex → newClassNode
   ├─ 从数据库读取 oldClassNode
   ├─ ClassNodeComparator 比较
   │   ├─ 方法签名未变: calculate(I)I
   │   ├─ 仅方法体变化
   │   └─ 判断: isCanHotReload = true
   ├─ 分类: hotReloadModifiedClasses.add(Utils)
   ├─ 收集影响: changedMethodRef = [Utils.calculate(I)I]
   └─ DeployDataDatabase.getEffectedSourceAndClass()
       ├─ 查询 methodRefs["Utils.calculate(I)I"]
       └─ 找到: ["MainActivity"]

3. 生成 JuggDeployData
   ├─ hotReloadModifiedClasses = [Utils]
   └─ effectedSourceAndClassNodes = [MainActivity]

4. IncrementalCompilerHelper 处理
   ├─ 检查 effectedSourceAndClassNodes
   ├─ 读取 MainActivity.kt
   ├─ 重新编译 MainActivity
   └─ 递归调用 buildDeployData()

5. 最终部署
   ├─ codeSwap([Utils.dex, MainActivity.dex])
   └─ JVMTI 热替换两个类
```

### 7.2 场景: 新增字段

**修改操作**:
```kotlin
// Utils.kt (新增字段)
class Utils {
    var count: Int = 0  // 新增字段
    fun calculate(x: Int): Int = x * 2
}
```

**Jugg 处理流程**:

```
1. ClassNodeComparator 比较
   ├─ addedFields = [count: Int]
   ├─ 判断: isCanHotReload = false (新增非静态字段)
   └─ 分类: hotFixModifiedClasses.add(Utils)

2. 生成 JuggDeployData
   ├─ hotFixModifiedClasses = [Utils]
   └─ effectedSourceAndClassNodes = []  (字段新增不触发重编译)

3. 最终部署
   ├─ fullSwap([Utils.dex])
   └─ 重启 Activity
```

---

## 八、设计亮点总结

### 8.1 性能优化

| 优化点 | 说明 |
|--------|------|
| **双层数据库** | APK 基线 (SQLite) + 增量缓存 (内存),查询优先内存 |
| **智能重建** | 变化超过 20% 时全量重建,避免增量维护成本过高 |
| **LinkedList 差集** | 利用类结构顺序稳定性,避免 HashSet 开销 |
| **R 类过滤** | 跳过 R 子类的影响分析,避免无意义的大范围重编译 |
| **引用索引** | 预构建方法/字段/子类引用表,O(1) 查询影响范围 |

### 8.2 精确性保证

| 保证点 | 说明 |
|--------|------|
| **继承链递归** | 父类方法变更会递归检查所有子类的调用点 |
| **抽象类传播** | 新增抽象方法强制所有实现类重编译 |
| **内联检测** | 检测 R8/ProGuard 内联导致的隐式依赖 |
| **Minify 检测** | 检测混淆删除的类引用,避免运行时 NoClassDefFoundError |
| **有效影响过滤** | 忽略 abstract/private 等无实际影响的变更 |

### 8.3 容错设计

| 容错点 | 说明 |
|--------|------|
| **数据库隔离** | 多 APK 项目各自独立数据库,互不影响 |
| **过期清理** | 自动清理已卸载应用的数据库 |
| **异常日志** | 影响分析失败时仅警告,不阻断编译 |
| **死循环保护** | 继承链查询最多 1000 层,避免死循环 |

---

## 九、与其他模块的协作

### 9.1 输入数据来源

| 模块 | 提供数据 | 用途 |
|------|---------|------|
| **ApkParser** | 解析 Dex 文件,构建 ClassNode 和引用索引 | 提供新旧类结构对比的原始数据 |
| **DeployFileManager** | 已部署的文件列表 | 初始化增量数据库 |
| **CompileOutput** | 编译产物 (Dex/Res/Asset) | 触发影响分析的输入 |

### 9.2 输出数据消费

| 模块 | 消费数据 | 用途 |
|------|---------|------|
| **JuggDeployer** | JuggDeployData | 决定部署策略 (codeSwap vs fullSwap) |
| **IncrementalCompilerHelper** | effectedSourceAndClassNodes | 触发受影响文件的重编译 |
| **OverlayUpdateBuilder** | newClasses, hotReloadModifiedClasses, hotFixModifiedClasses | 构建 Overlay 更新数据 |

### 9.3 在编译-部署流程中的位置

```
编译阶段 (IncrementalCompilerHelper)
    ↓
【THIS】DeployDataGenerator.buildDeployData()
    ├─ 生成 JuggDeployData
    └─ 返回 effectedSourceAndClassNodes
    ↓
编译阶段 (递归编译受影响文件)
    ↓
【THIS】DeployDataGenerator.buildDeployData() (第二轮)
    └─ effectedSourceAndClassNodes = [] (不再递归)
    ↓
部署阶段 (JuggDeployer)
    ├─ 根据 JuggDeployData 决定策略
    └─ 调用 codeSwap() / fullSwap()
    ↓
【THIS】DeployDataGenerator.commitDeployedData()
    └─ 更新增量数据库
```

---

## 十、关键配置与调优

### 10.1 数据库重建阈值

**配置位置**: `DeployDataDatabase.processApkWithHelper()`

```kotlin
val isFullUpdate = allChangedDexFileSize > 3 ||
                  (dexFileSize > 0 && allChangedDexFileSize >= dexFileSize * 0.2)
```

**调优建议**:
- 小项目: 可提高到 30%,减少重建频率
- 大项目: 保持 20%,避免增量维护成本过高

### 10.2 影响分析开关

**参数**:
- `isNeedCheckRecompile`: 是否检查受影响文件 (默认 true)
- `isNeedCheckRecompileMinifyRemovedClass`: 是否检查混淆删除 (默认 false)
- `isCompilingEffectedSourceFiles`: 是否正在编译受影响文件 (默认 false)

**场景**:
```kotlin
// 场景 1: 用户手动触发全量编译
buildDeployData(..., isNeedCheckRecompile = false)  // 跳过影响分析

// 场景 2: Release 构建 (开启混淆)
buildDeployData(..., isNeedCheckRecompileMinifyRemovedClass = true)

// 场景 3: 递归编译受影响文件
buildDeployData(..., isCompilingEffectedSourceFiles = true)  // 跳过内联检测
```

---

## 十一、常见问题与解决方案

### 11.1 问题: 修改工具类后,调用方未更新

**原因**:
1. 方法被 R8 内联,调用方未被识别为受影响文件
2. InlineMethodDetector 未正确解析 mapping 文件

**解决**:
```kotlin
// 检查 mapping 文件是否存在且格式正确
val mappingFile = context.mappingFile
logger.info("Mapping file: $mappingFile, exists: ${mappingFile?.exists()}")

// 检查内联检测是否执行
if (!isCompilingEffectedSourceFiles) {
    val inlineEffectedNodes = inlineDetector.findInlineEffectedClasses(parsedDex)
    logger.info("Inline effected nodes: $inlineEffectedNodes")
}
```

### 11.2 问题: R 类变化导致大量文件重编译

**原因**:
- R 子类被纳入影响分析
- RFileFixer 删除未使用的资源 ID 触发重编译

**解决**:
- 已通过 `isRSubClass` 过滤 R 子类
- 如仍有问题,检查是否为 R 主类 (不含 `$` 的 R 类)

### 11.3 问题: 数据库过大导致性能下降

**原因**:
- 项目长期迭代,数据库积累大量历史数据
- 增量更新未触发全量重建

**解决**:
```kotlin
// 方案 1: 降低重建阈值
val isFullUpdate = allChangedDexFileSize > 2 || (... >= dexFileSize * 0.15)

// 方案 2: 定期清理数据库
deployDataDatabase.clearDeployedData()
deployDataDatabase.init(apks, emptyList())
```

---

## 十二、文档状态

**状态**: ✅ 已完成
**覆盖文件**:
- `DeployDataGenerator.kt` (258 行)
- `ClassNodeComparator.kt` (191 行)
- `DeployDataDatabase.kt` (530 行)
- `InlineMethodDetector.kt` (未完整列出)

**下一步**:
- 补充 `InlineMethodDetector` 完整实现
- 补充 `ApkParser` 引用索引构建逻辑
- 补充 Minify 检测算法详细说明

---

**文档版本**: v1.0
**创建时间**: 2026-02-01
**作者**: AI Assistant
**审核状态**: 待审核
