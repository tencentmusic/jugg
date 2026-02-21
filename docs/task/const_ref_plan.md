# 常量变化重编译方案

## Context

Jugg 增量编译插件基于 DEX 字节码分析检测类间引用关系，但编译期常量（Java `static final` 基本类型/String、Kotlin `const val`）会被 JVM 内联到引用处字节码中，导致字节码层面无法建立常量引用关系。当常量值变化时，引用方不会被重编译，造成运行时错误。

现有 `KotlinConstRefReader` 依赖 Kotlin 编译缓存 `lookups.tab`（通过反射），只支持 Kotlin 且版本兼容性风险高。新方案采用 **Java/Kotlin 统一的 AST 源码分析**替代，基于 JavaParser（Java）和 kotlin-compiler-embeddable PSI（Kotlin）进行精确的语法树分析，提取常量定义和引用关系，配合 SQLite 缓存和异步调度。

### 参考架构

本方案的解析器设计参考了 `AndroidApiCallAnalysis` 工程中已验证的实现：

| 参考文件 | 本方案对应 | 复用点 |
|---------|-----------|-------|
| `JavaSourceParser.kt` (JavaParser AST) | `ConstDefinitionExtractor` Java 部分 | JavaParser 初始化、CompilationUnit 遍历、嵌套类递归 |
| `KotlinSourceParser.kt` (Kotlin PSI) | `ConstDefinitionExtractor` Kotlin 部分 | KotlinCoreEnvironment 创建、KtFile 解析、顶层声明/companion object 遍历 |
| `SourceFileCacheDatabase.kt` (SQLite) | `ConstRefCacheDatabase` | WAL 模式、last_modified + checksum 双重校验、事务+级联删除 |
| `SourceFileAnalyzer.kt` (统一入口) | `ConstRefAnalyzer` | 按文件扩展名分发解析器、缓存读写流程 |

---

## 一、整体架构

```
文件变更事件
    |
ConstRefScheduler (异步调度+防抖)
    |
ConstRefAnalyzer (分析入口, 按文件类型分发)
    +-- JavaConstParser      (JavaParser AST 提取常量定义+引用)
    +-- KotlinConstParser    (Kotlin PSI 提取常量定义+引用)
    |
ConstRefCacheDatabase (SQLite 缓存, last_modified + crc32)
    |
编译时查询 -> getEffectedFiles(changedFiles) -> 返回需重编译文件
```

**核心思路**: 两阶段 AST 分析
1. **定义阶段**: AST 遍历所有源文件，精确提取常量定义（包名 + 类名 + 常量名 + 类型 + 值）
2. **引用阶段**: AST 遍历每个源文件的 import 声明和表达式节点，精确匹配已知的常量定义

---

## 二、源码分析引擎（AST 方案）

### 2.1 Java 常量解析 (JavaConstParser)

使用 **JavaParser** 库（`com.github.javaparser`），与参考工程 `JavaSourceParser.kt` 相同的技术栈。

**解析器初始化**（复用参考工程模式）:
```kotlin
class JavaConstParser {
    private val parserConfig = ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .setCharacterEncoding(Charsets.UTF_8)

    fun parse(sourceFile: File): JavaConstParseResult { ... }
}
```

**常量定义提取** — 遍历 AST 中的 `FieldDeclaration` 节点:
```kotlin
// 遍历所有字段声明
cu.findAll(FieldDeclaration::class.java).forEach { field ->
    // 检查是否为编译期常量:
    // 1. static final 修饰
    // 2. 类型为基本类型或 String
    // 3. 有编译期可求值的初始化表达式
    if (field.isStatic && field.isFinal) {
        field.variables.forEach { variable ->
            val type = variable.typeAsString
            if (isInlineableType(type) && variable.initializer.isPresent) {
                // 精确提取: 包名、类名（含嵌套层级）、常量名、类型、值
            }
        }
    }
}
```

**可内联类型判断**:
```kotlin
private fun isInlineableType(type: String): Boolean {
    return type in setOf(
        "int", "long", "float", "double", "boolean",
        "byte", "short", "char", "String",
        "Int", "Long", "Float", "Double", "Boolean",
        "Byte", "Short", "Char"
    )
}
```

**常量引用检测** — 遍历 AST 中的 import 声明和表达式:
```kotlin
// 1. 从 CompilationUnit 获取 import 声明
val imports = cu.imports  // ImportDeclaration 列表
// 精确解析: 普通 import、static import、星号 import

// 2. 遍历 FieldAccessExpr 和 NameExpr 节点匹配常量引用
cu.findAll(FieldAccessExpr::class.java).forEach { fieldAccess ->
    val scope = fieldAccess.scope  // 如 "Constants"
    val name = fieldAccess.nameAsString  // 如 "MAX"
    // 根据 import 映射表解析 scope 的 FQ 类名
    // 在常量定义表中匹配 (fqClassName, constName)
}

// 3. 对 static import，直接在 NameExpr 中匹配
cu.findAll(NameExpr::class.java).forEach { nameExpr ->
    // 检查是否匹配 static import 的常量名
}
```

**嵌套类处理**（复用参考工程的递归模式）:
```kotlin
// 与 JavaSourceParser.extractNestedClasses() 相同的递归策略
private fun extractNestedClasses(
    typeDeclaration: TypeDeclaration<*>,
    packageName: String,
    outerClassName: String
): List<ConstDefinition> { ... }
```

### 2.2 Kotlin 常量解析 (KotlinConstParser)

使用 **kotlin-compiler-embeddable** 的 PSI 体系，与参考工程 `KotlinSourceParser.kt` 相同的技术栈。

**解析器初始化**（复用参考工程模式）:
```kotlin
class KotlinConstParser {
    private val disposable = Disposer.newDisposable()
    private val environment: KotlinCoreEnvironment

    init {
        val configuration = CompilerConfiguration()
        environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            configuration,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
    }

    fun parse(sourceFile: File): KotlinConstParseResult { ... }
    fun dispose() { Disposer.dispose(disposable) }
}
```

**常量定义提取** — 遍历 KtFile 的声明节点:
```kotlin
// 解析为 KtFile
val psiManager = PsiManager.getInstance(environment.project)
val virtualFile = LightVirtualFile(fileName, KotlinFileType.INSTANCE, content)
val ktFile = psiManager.findFile(virtualFile) as? KtFile

// 1. 遍历顶层 const val
ktFile.declarations.filterIsInstance<KtProperty>().forEach { property ->
    if (property.hasModifier(KtTokens.CONST_KEYWORD)) {
        // 提取: 包名、文件名Kt类名、常量名、类型、值
    }
}

// 2. 遍历 object / companion object 中的 const val
ktFile.declarations.filterIsInstance<KtClassOrObject>().forEach { klass ->
    // 递归处理嵌套类和 companion object
    extractConstFromClassOrObject(klass, packageName)
}
```

**companion object 处理**:
```kotlin
private fun extractConstFromClassOrObject(
    klass: KtClassOrObject,
    packageName: String
) {
    // 直接在 object 中找 const val
    klass.declarations.filterIsInstance<KtProperty>().forEach { property ->
        if (property.hasModifier(KtTokens.CONST_KEYWORD)) { ... }
    }

    // 处理 companion object
    if (klass is KtClass) {
        klass.companionObjects.forEach { companion ->
            companion.declarations.filterIsInstance<KtProperty>().forEach { property ->
                if (property.hasModifier(KtTokens.CONST_KEYWORD)) {
                    // FQ 类名用外部类名（不含 Companion）
                }
            }
        }
    }

    // 递归嵌套类
    klass.declarations.filterIsInstance<KtClassOrObject>().forEach { nested ->
        extractConstFromClassOrObject(nested, packageName)
    }
}
```

**常量引用检测** — 遍历 KtFile 的 import 和表达式:
```kotlin
// 1. 解析 import 指令
ktFile.importDirectives.forEach { import ->
    val fqName = import.importedFqName?.asString()
    val isAllUnder = import.isAllUnder  // 星号导入
    // 建立 "简短名称 -> FQ 类名" 映射
}

// 2. 遍历 KtDotQualifiedExpression 匹配 "ClassName.CONST"
ktFile.accept(object : KtTreeVisitorVoid() {
    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
        val receiver = expression.receiverExpression
        val selector = expression.selectorExpression as? KtNameReferenceExpression
        if (receiver is KtNameReferenceExpression && selector != null) {
            // 根据 import 映射表解析 receiver 的 FQ 类名
            // 在常量定义表中匹配
        }
        super.visitDotQualifiedExpression(expression)
    }

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        // 匹配直接导入的常量名（星号导入或顶层常量）
        super.visitReferenceExpression(expression)
    }
})
```

### 2.3 六种引用模式的 AST 检测方案

| 模式 | Java AST 检测 | Kotlin PSI 检测 |
|------|-------------|----------------|
| 显式引用 `Constants.MAX` | `FieldAccessExpr`: scope=NameExpr("Constants"), name="MAX" | `KtDotQualifiedExpression`: receiver="Constants", selector="MAX" |
| 静态导入 `import static ...MAX` | `ImportDeclaration.isStatic` + `NameExpr` 匹配 | N/A（Kotlin 无 static import） |
| 星号导入 `import ...Constants.*` | `ImportDeclaration.isAsterisk` + 展开所有常量名匹配 `NameExpr` | `KtImportDirective.isAllUnder` + 展开匹配 `KtNameReferenceExpression` |
| 伴生对象 `Config.DEFAULT` | N/A（Java 无 companion） | PSI 遍历时将 companion object 的常量映射为外部类名 |
| 顶层常量 `import com.example.MAX` | N/A（Java 无顶层常量） | `KtFile.declarations` 中的顶层 `KtProperty(const)` 按 `FileNameKt` 类处理 |
| 同包引用 | 比较 `cu.packageDeclaration` 与常量定义的包名 | 比较 `ktFile.packageFqName` 与常量定义的包名 |

### 2.4 AST vs 正则的优势

| 场景 | 正则方案 | AST 方案 |
|------|---------|---------|
| 嵌套类中的常量 | 需复杂状态机跟踪大括号层级 | AST 天然递归处理 `TypeDeclaration` / `KtClassOrObject` |
| 注释/字符串中的误匹配 | 需预处理去除注释（易出错） | AST 自动忽略注释和字符串字面量 |
| 多行常量声明 | 正则匹配跨行困难 | AST 节点完整覆盖 |
| 泛型/注解修饰的常量 | 正则需穷举修饰符组合 | AST 直接访问 `isStatic`/`isFinal`/`hasModifier` |
| import 语句解析 | 正则易与代码中的类似文本混淆 | AST 提供 `ImportDeclaration` / `KtImportDirective` 专用节点 |
| 维护成本 | 正则随场景增加指数级复杂 | AST 的 Visitor 模式天然可扩展 |

---

## 三、缓存系统

### 3.1 数据库表结构

参考 `SourceFileCacheDatabase.kt` 的设计，使用 WAL 模式和 CRC32 校验：

```sql
-- 数据库 PRAGMA (参考 SourceFileCacheDatabase)
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA cache_size=-64000;  -- 64MB cache
PRAGMA temp_store=MEMORY;
PRAGMA busy_timeout=5000;
PRAGMA foreign_keys=ON;

-- 文件缓存 (双重校验: last_modified 快速检查 + crc32 精确检查)
CREATE TABLE file_cache (
    file_path TEXT PRIMARY KEY,
    last_modified INTEGER NOT NULL,
    checksum INTEGER,             -- CRC32 (与参考工程一致, 比 MD5 快)
    analyzed_at INTEGER NOT NULL
);

-- 常量定义 (文件中定义的所有编译期常量)
CREATE TABLE const_definitions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path TEXT NOT NULL,
    fq_class_name TEXT NOT NULL,    -- 如 "com.example.Constants"
    const_name TEXT NOT NULL,        -- 如 "MAX"
    const_type TEXT NOT NULL,        -- 如 "int", "String"
    const_value TEXT,                -- 如 "100", "\"test\""
    FOREIGN KEY (file_path) REFERENCES file_cache(file_path) ON DELETE CASCADE
);
CREATE UNIQUE INDEX idx_const_def_unique ON const_definitions(fq_class_name, const_name);
CREATE INDEX idx_const_def_file ON const_definitions(file_path);

-- 常量引用 (文件中引用的外部常量)
CREATE TABLE const_references (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    ref_file_path TEXT NOT NULL,     -- 引用方文件
    def_fq_class_name TEXT NOT NULL, -- 被引用的常量所在类
    const_name TEXT NOT NULL,        -- 被引用的常量名
    FOREIGN KEY (ref_file_path) REFERENCES file_cache(file_path) ON DELETE CASCADE
);
CREATE INDEX idx_ref_def_class ON const_references(def_fq_class_name);
CREATE INDEX idx_ref_file ON const_references(ref_file_path);
```

### 3.2 缓存策略

与参考工程 `SourceFileCacheDatabase.get()` 一致的双重校验流程：

```
文件变更 -> 检查 last_modified
    +-- 相同 -> 缓存命中，跳过
    +-- 不同 -> 计算 CRC32
        +-- CRC32 相同 -> 仅更新 last_modified（如 touch / 编辑器格式化未改内容）
        +-- CRC32 不同 -> 删除旧数据(级联删除)，重新分析
```

### 3.3 查询接口

```kotlin
// 核心查询: 给定变更文件列表，返回需要重编译的文件路径
fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef>

data class EffectedConstRef(
    val refFilePath: String,        // 需要重编译的文件
    val defFqClassName: String,     // 常量所在类
    val constName: String,          // 常量名
)
```

查询逻辑:
1. 在 `const_definitions` 中查找变更文件定义的所有常量
2. 在 `const_references` 中查找引用了这些常量的所有文件
3. 返回去重后的文件列表

---

## 四、异步调度系统 (ConstRefScheduler)

### 4.1 核心行为

```
+-----------------------------------------------------------+
|  文件 A 保存 -> 记录 pending, 标记 currentEditing=A          |
|  文件 A 再次保存 -> 更新 pending 时间 (防抖)                  |
|  文件 B 保存 -> 触发 A 的异步分析, currentEditing=B          |
|  编译触发 -> awaitAnalysis() 等待所有 pending 完成           |
+-----------------------------------------------------------+
```

### 4.2 关键接口

```kotlin
class ConstRefScheduler(
    analyzer: ConstRefAnalyzer,
    database: ConstRefCacheDatabase,
    logger: Logger,
    coroutineScope: CoroutineScope,
) {
    // 文件保存时调用 (来自 DeployFileManager.addChangedFile)
    fun onFileSaved(filePath: String)

    // 编译前调用，等待所有变更文件分析完成
    fun awaitAnalysis(filePaths: List<String>, timeoutMs: Long = 5000)

    // 首次 Gradle 编译后全量建立索引 (后台)
    fun initializeFullScan(sourceDirs: List<File>)

    // 查询受影响文件
    fun getEffectedFiles(changedFilePaths: Collection<String>): List<EffectedConstRef>
}
```

### 4.3 全量扫描策略

首次启动时（Gradle 编译完成后），在后台执行全量扫描：
1. 遍历所有 module 的 sourceDirs
2. 先提取所有文件的常量定义（Phase 1）
3. 再检测所有文件的常量引用（Phase 2 - 需要 Phase 1 的定义表）
4. 有缓存的文件直接跳过（CRC32 校验）

### 4.4 KotlinCoreEnvironment 生命周期

`KotlinCoreEnvironment` 是重量级对象，需要注意生命周期管理（参考 `KotlinSourceParser` 的模式）：
- 在 `ConstRefAnalyzer` 级别创建单例，所有 Kotlin 文件共享
- 全量扫描完成后保持存活，供增量分析使用
- `ConstRefScheduler.dispose()` 时释放（调用 `Disposer.dispose(disposable)`）

---

## 五、与编译流程集成

### 5.1 集成点

**1. DeployFileManager.addChangedFile()** -- 文件变更时触发异步分析

```kotlin
// 现有代码
coroutineScope.launch {
    sourceFileManager.updateFiles(newFiles, emptyList())
}
// 新增
constRefScheduler.onFileSaved(file.absolutePath)
```

**2. IncrementalCompilerHelper.compile()** -- 编译前等待分析完成

```kotlin
// 在编译开始前:
val changedFilePaths = undeployedFiles
    .filter { it.type in listOf(CompileFile.Type.Java, CompileFile.Type.Kotlin) }
    .map { it.file.absolutePath }
constRefScheduler.awaitAnalysis(changedFilePaths)
```

**3. DeployFileManager.getRecompileFiles()** -- 查询受影响文件

```kotlin
// 现有流程中 buildDeployData 之后，获取 effectedSourceFiles 之前:
val constRefEffectedFiles = constRefScheduler.getEffectedFiles(
    compiledFiles.map { it.file.absolutePath }
)
// 将 constRefEffectedFiles 转换为 EffectedClassNode 并合并到结果中
```

**4. DeployFileManager.init()** -- 初始化时建立全量索引

```kotlin
// 在 init 方法中:
constRefScheduler.initializeFullScan(
    moduleInfos.values.flatMap { it.sourceDirs }
)
```

### 5.2 替换现有方案

移除以下内容：
- `KotlinConstRefReader.kt` 中的 lookups.tab 解析逻辑（不再使用）
- `KotlinConstRefDatabase.kt`（被 `ConstRefCacheDatabase` 替代）
- `DeployDataGenerator.updateKotlinConstRefs()` 方法
- `DeployFileManager.updateKotlinConstRefs()` 方法
- `IncrementalCompilerHelper.compile()` 中第一轮编译后的 `updateKotlinConstRefs` 调用

---

## 六、依赖变更

### 需要添加的依赖

```gradle
// main/build.gradle
// JavaParser 需从 testImplementation 提升为 implementation
implementation "com.github.javaparser:javaparser-core:3.26.4"

// kotlin-compiler-embeddable 已有（现有 implementation 依赖）
// 无需额外添加
```

注意：仅需 `javaparser-core`，不需要 `javaparser-symbol-solver-core`（常量分析不需要符号解析）。

---

## 七、文件清单

### 新增文件 (main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/)

| 文件 | 职责 | 关键技术 |
|------|------|---------|
| `ConstRefModels.kt` | 数据模型 (ConstDefinition, ConstReference, EffectedConstRef, ParseResult) | data class |
| `JavaConstParser.kt` | Java 常量定义提取 + 引用检测 | JavaParser AST, FieldDeclaration, ImportDeclaration |
| `KotlinConstParser.kt` | Kotlin 常量定义提取 + 引用检测 | Kotlin PSI, KtProperty, KtImportDirective, KtTreeVisitorVoid |
| `ConstRefAnalyzer.kt` | 分析入口，按文件类型分发解析器 | 参考 SourceFileAnalyzer 的分发模式 |
| `ConstRefCacheDatabase.kt` | SQLite 缓存数据库 | 参考 SourceFileCacheDatabase 的 WAL + CRC32 模式 |
| `ConstRefScheduler.kt` | 异步调度+防抖+同步等待 | coroutine, mutex |

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `deploy/DeployFileManager.kt` | 添加 ConstRefScheduler 实例；addChangedFile 中调用 onFileSaved；getRecompileFiles 中合并常量引用结果 |
| `compiler/IncrementalCompilerHelper.kt` | compile() 前调用 awaitAnalysis；移除 updateKotlinConstRefs 调用 |
| `deploy/data/DeployDataGenerator.kt` | 移除 kotlinConstRefDatabase 相关逻辑 |
| `main/build.gradle` | 添加 javaparser-core implementation 依赖 |

### 删除文件

| 文件 | 原因 |
|------|------|
| `deploy/data/KotlinConstRefDatabase.kt` | 被 ConstRefCacheDatabase 替代 |
| `compiler/source/kotlin/KotlinConstRefReader.kt` | 不再使用 lookups.tab 反射方案 |

### 新增测试文件

| 文件 | 覆盖内容 |
|------|---------|
| `JavaConstParserTest.kt` | Java 各种常量定义模式、嵌套类、static import、星号导入 |
| `KotlinConstParserTest.kt` | Kotlin const val、companion object、顶层常量、object |
| `ConstRefCacheDatabaseTest.kt` | CRUD、缓存命中/失效、CRC32 校验 |
| `ConstRefSchedulerTest.kt` | 防抖、异步/同步等待 |
| `ConstRefIntegrationTest.kt` | 端到端: 修改常量 -> 检测影响 -> 重编译 |

---

## 八、关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 分析方法 | **AST 语法树分析** | JavaParser + Kotlin PSI 精确解析，不会被注释/字符串/嵌套/多行等边界情况干扰；参考工程已验证可行性和性能 |
| Java 解析库 | JavaParser (`javaparser-core`) | 参考工程 `JavaSourceParser.kt` 已验证；轻量、无需符号解析 |
| Kotlin 解析库 | kotlin-compiler-embeddable PSI | 参考工程 `KotlinSourceParser.kt` 已验证；Jugg 已有该依赖 |
| 统一性 | Java/Kotlin 解析器独立实现，共享数据模型和缓存 | 两种语言的 AST 结构差异大，强行统一反而增加复杂度；通过共享 `ConstDefinition`/`ConstReference` 模型和 `ConstRefCacheDatabase` 保持一致性 |
| 存储 | 独立 SQLite DB | 与现有 DeployDataDatabase 解耦，独立演进 |
| 缓存校验 | last_modified + CRC32 | 参考 SourceFileCacheDatabase 验证过的方案；CRC32 比 MD5 快，对此场景精度足够 |
| 引用解析 | 基于 AST import 节点 + 表达式节点 | 比正则的 import 解析更精确，不会匹配注释中的 import 语句 |
| 误报策略 | 宁多勿漏 | 多重编译只影响速度，漏掉则运行时错误 |

---

## 九、验证计划

1. **单元测试**: 分别测试 JavaConstParser、KotlinConstParser、缓存系统、调度器
2. **集成测试**: 使用 demo 项目 (`testcase/constref/` 下已有 Constants.kt, User.kt, Admin.kt 等)
   - 修改 `Constants.MAX` -> 验证 User.kt, Admin.kt 被标记为需重编译
   - 修改 `Config.DEFAULT` -> 验证 Service.kt 被标记
   - 修改 `UnrelatedClass` -> 验证不触发额外重编译
3. **边界情况测试**:
   - 嵌套类中的常量定义和引用
   - companion object 中的 const val
   - 顶层 Kotlin const val
   - 星号导入 + 静态导入
   - 同包引用（无需 import）
   - 注释中出现 `Constants.MAX`（不应被检测为引用）
   - 字符串中出现 `"Constants.MAX"`（不应被检测为引用）
4. **性能测试**: 1000 文件全量扫描 < 5s，单文件增量 < 50ms，查询 < 10ms
5. **编译测试**: 通过现有的 `JuggCompileTest` 和 `SourceCompileTest` 验证编译流程不受影响
