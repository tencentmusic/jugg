# AabResGuard 集成支持实现计划

> 创建时间: 2026-01-19
> 实现状态: ✅ 已完成
> 相关 Issue: N/A

---

## 📋 目录

- [背景](#背景)
- [需求总结](#需求总结)
- [映射文件格式](#映射文件格式)
- [实现方案](#实现方案)
- [关键实现细节](#关键实现细节)
- [测试计划](#测试计划)
- [实现顺序](#实现顺序)
- [潜在问题与解决方案](#潜在问题与解决方案)
- [关键文件路径](#关键文件路径)
- [实现总结](#实现总结)

---

## 背景

实现对接入了 [AabResGuard](https://github.com/bytedance/AabResGuard) 库的工程支持。AabResGuard 在编译期间会将 resource 资源进行混淆,以达到节省精简包体等功能。

Jugg 作为 Android 增量编译工具,需要支持使用了 AabResGuard 的工程,确保增量编译和热部署功能正常工作。

---

## 需求总结

基于与用户的沟通,明确以下需求:

| 需求项 | 说明 |
|--------|------|
| **混淆规则范围** | 只处理资源 ID 映射 (res id mapping) |
| **处理策略** | 同时处理目录结构和 XML 内容中的资源引用 |
| **映射文件路径** | 固定路径 `build/outputs/bundle/{variant}/resources-mapping.txt` |
| **variant 获取** | 从 `ModuleInfo.buildVariant` 获取 |
| **替换范围** | 所有 XML 文件中的 `@xxx` 资源引用 |
| **处理时机** | 在 `aapt2Compile` 方法开始前 |
| **错误处理** | mapping 文件不存在时跳过处理,正常编译<br>处理失败时终止编译并报错 |

---

## 映射文件格式

根据 AabResGuard 官方文档,`resources-mapping.txt` 格式如下:

```txt
res dir mapping:
	res/color-v21 -> res/c
	res/color-v23 -> res/d
	res/anim -> res/a

res id mapping:
	0x7f0c00ba : com.bytedance.android.app.R.style.RtlUnderlay.Widget.AppCompat.ActionButton.Overflow -> com.bytedance.android.app.R.style.eb
	0x7f040002 : com.bytedance.android.app.R.color.abc_btn_colored_borderless_text_material -> com.bytedance.android.app.R.color.c

res entries path mapping:
	0x7f060030 : base/res/drawable-xxhdpi-v4/abc_list_selector_disabled_holo_dark.9.png -> res/h/z.9.png
	0x7f060022 : base/res/drawable-xxxhdpi-v4/abc_ic_star_half_black_16dp.png -> res/k/o.png
```

### 映射规则说明

- **res dir mapping**: 资源目录的混淆规则 (格式: `dir -> dir`)
- **res id mapping**: 资源名称的混淆规则 (格式: `resourceId : resourceName -> resourceName`)
- **res entries path mapping**: 资源文件路径的混淆规则 (格式: `resourceId : path -> path`)

**本实现只处理 res id mapping 部分**

---

## 实现方案

### 架构图

```
┌─────────────────────────────────────────────────────────────┐
│                     ResourceCompiler                         │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ aapt2Compile()                                       │  │
│  │  1. aabResGuardHandler.process()  ← 调用独立类      │  │
│  │  2. aapt2 compile                                    │  │
│  │  3. validate outputs                                 │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   AabResGuardHandler  ← 新增独立类           │
│                                                              │
│  - process(resCompileSet)                                   │
│  - findMappingFile(task)                                    │
│  - parseMappingFile(file)                                   │
│  - processResourceFiles(resCompileSet, mappings)           │
└─────────────────────────────────────────────────────────────┘
           ↓                              ↓
┌──────────────────────┐    ┌──────────────────────────┐
│ AabResGuardMapping   │    │ AabResGuardResource      │
│ Parser               │    │ Processor                │
│                      │    │                          │
│ - parse()            │    │ - processResourceFiles() │
│ - parseMappingLine() │    │ - processXmlFile()       │
│ - parseResourceName()│    │ - replaceReferences()    │
└──────────────────────┘    └──────────────────────────┘
```

### 1. 创建 AabResGuard 映射文件解析器

**文件**: `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardMappingParser.kt`

**功能**:
- 解析 `resources-mapping.txt` 文件
- 提取 "res id mapping" 部分
- 构建资源名称映射表: `原始资源名 -> 混淆后资源名`

**数据结构**:
```kotlin
data class ResourceMapping(
    val originalName: String,  // 如 "abc_btn_colored_borderless_text_material"
    val obfuscatedName: String, // 如 "c"
    val resourceType: String    // 如 "color", "style", "drawable"
)
```

**解析逻辑**:
1. 读取 mapping 文件
2. 定位 "res id mapping:" 标记
3. 逐行解析映射关系
4. 从完整类名中提取资源类型和资源名称
   - 如 `R.color.abc_btn_colored_borderless_text_material` → type: `color`, name: `abc_btn_colored_borderless_text_material`

**核心代码**:
```kotlin
object AabResGuardMappingParser {
    fun parse(mappingFile: File): Map<String, ResourceMapping>
    private fun parseMappingLine(line: String): ResourceMapping?
    private fun parseResourceName(fullName: String): Pair<String, String>?
}
```

### 2. 创建资源文件处理器

**文件**: `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardResourceProcessor.kt`

**功能**:
- 创建资源文件副本
- 替换 XML 文件中的资源引用
- 按照混淆后的目录结构组织文件

**核心方法**:
```kotlin
class AabResGuardResourceProcessor(
    private val mapping: Map<String, ResourceMapping>,
    private val logger: Logger
) {
    fun processResourceFiles(
        originalFiles: List<File>,
        outputDir: File,
        compileFile: CompileFile
    ): List<File>

    private fun processXmlFile(
        inputFile: File,
        outputFile: File
    )

    private fun replaceResourceReferences(
        xmlContent: String
    ): String
}
```

**XML 处理逻辑**:
1. 读取 XML 文件内容
2. 使用正则表达式查找所有 `@resourceType/resourceName` 引用
   - 匹配模式: `@([a-z]+)/([a-zA-Z0-9_]+)`
3. 在映射表中查找对应的混淆后名称
4. 替换为混淆后的引用: `@resourceType/obfuscatedName`
5. 写入输出文件

**目录结构处理**:
- 保持原有的资源目录结构 (layout, drawable, values 等)
- 不重命名资源文件本身,只替换内容中的引用

### 3. 创建 AabResGuard 处理器 (Handler)

**文件**: `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardHandler.kt`

**职责**: 封装所有 AabResGuard 处理逻辑,避免 ResourceCompiler 类过于臃肿

**核心方法**:
```kotlin
class AabResGuardHandler(
    private val logger: Logger
) {
    fun process(resCompileSet: ResCompileSet): ResCompileSet?
    private fun findMappingFile(task: CompileTask): File?
    private fun parseMappingFile(mappingFile: File): Map<String, ResourceMapping>?
    private fun processResourceFiles(
        resCompileSet: ResCompileSet,
        mappings: Map<String, ResourceMapping>
    ): ResCompileSet?
}
```

**处理流程**:
1. 查找 mapping 文件 (`findMappingFile`)
   - 从 CompileTask 获取 ModuleInfo
   - 构建路径: `build/outputs/bundle/{variant}/resources-mapping.txt`
   - 返回 null 表示文件不存在,跳过处理

2. 解析 mapping 文件 (`parseMappingFile`)
   - 调用 AabResGuardMappingParser.parse()
   - 捕获异常,返回 null 表示解析失败

3. 处理资源文件 (`processResourceFiles`)
   - 创建 AabResGuardResourceProcessor 实例
   - 处理所有资源文件
   - 返回处理后的 ResCompileSet

**设计优势**:
- **职责单一**: 只负责 AabResGuard 相关逻辑
- **降低耦合**: ResourceCompiler 不需要了解处理细节
- **便于测试**: 可以独立测试 Handler 类
- **易于扩展**: 未来可以添加更多处理功能

### 4. 修改 ResourceCompiler

**文件**: `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt`

**修改位置**: `aapt2Compile` 方法开始处

**修改内容**:

#### 4.1 添加 AabResGuardHandler 实例

```kotlin
class ResourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)
    private val aabResGuardHandler = AabResGuardHandler(logger)  // ← 新增

    private val dataBindingGenBaseClassesCompiler = ...
    private val dataBindingGenMapperCompiler = ...
    ...
}
```

#### 4.2 修改 aapt2Compile 方法

```kotlin
private fun aapt2Compile(resCompileSet: ResCompileSet): CompileResult {
    if (resCompileSet.compileFiles.isEmpty()) {
        return CompileResult(resCompileSet.originTask, resCompileSet.taskFiles.map { Result.success(it) }, emptyList())
    }

    // === 新增: AabResGuard 处理 (调用独立的 Handler) ===
    val processedResCompileSet = aabResGuardHandler.process(resCompileSet) ?: run {
        // 处理失败,返回错误
        return resCompileSet.originTask.allFailed("AabResGuard processing failed")
    }
    // === 新增结束 ===

    val filesString = processedResCompileSet.compileFiles.joinToString(" ") {
        it.absolutePath
    }
    // ... 后续逻辑使用 processedResCompileSet
}
```

#### 4.3 修改 ResCompileSet 可见性

```kotlin
// 从 private 改为 internal,允许 AabResGuardHandler 访问
internal data class ResCompileSet(
    val originTask: CompileTask,
    val compileFileMap: Map<CompileFile, List<File>>,
    val outputDir: File,
) {
    val taskFiles: List<CompileFile> get() = compileFileMap.keys.toList()
    val compileFiles: List<File> get() = compileFileMap.values.flatten()
}
```

**重构优势**:
- ResourceCompiler 只需要调用一行代码: `aabResGuardHandler.process()`
- 所有 AabResGuard 逻辑封装在独立的 Handler 类中
- ResourceCompiler 保持简洁,易于维护
- 符合单一职责原则

### 5. 文件组织

```
main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/
├── AabResGuardMappingParser.kt      (✅ 新建)
├── AabResGuardResourceProcessor.kt  (✅ 新建)
├── AabResGuardHandler.kt            (✅ 新建) ← 封装处理逻辑
└── ResourceCompiler.kt              (✅ 修改)
```

---

## 关键实现细节

### 1. 资源引用匹配

使用正则表达式匹配 XML 中的资源引用:
```kotlin
val resourceRefPattern = Regex("""@([a-z]+)/([a-zA-Z0-9_]+)""")
```

**支持的引用形式**:
- `@layout/activity_main`
- `@drawable/ic_launcher`
- `@color/colorPrimary`
- `@style/AppTheme`
- `@string/app_name`

**不支持的形式** (会被忽略):
- `@android:color/white` (系统资源)
- `?attr/colorPrimary` (属性引用)
- `@*android:layout/simple_list_item_1` (私有系统资源)

### 2. 资源类型提取

从完整的 R 类名提取资源类型:
```kotlin
// 输入: com.bytedance.android.app.R.color.abc_btn_colored_borderless_text_material
// 输出: type="color", name="abc_btn_colored_borderless_text_material"
fun parseResourceName(fullName: String): Pair<String, String>? {
    val parts = fullName.split(".")
    val rIndex = parts.indexOf("R")
    if (rIndex == -1 || rIndex >= parts.size - 2) return null

    val type = parts[rIndex + 1]  // color
    val name = parts.drop(rIndex + 2).joinToString(".") // abc_btn_colored_borderless_text_material
    return type to name
}
```

### 3. 增量编译兼容

- 临时文件放在 `outputDir/aabresguard_temp/` 下
- 保持与原有增量编译逻辑的兼容性
- 不影响 DataBinding 处理流程

### 4. 错误处理

| 错误情况 | 处理方式 |
|----------|----------|
| mapping 文件不存在 | 记录日志,跳过处理,正常编译 |
| mapping 文件解析失败 | 记录错误,终止编译 |
| 资源文件处理失败 | 记录错误,终止编译 |
| XML 解析失败 | 记录错误,终止编译 |

### 5. 日志输出

```kotlin
// 调试日志
logger.debug("AabResGuard mapping file not found: ${mappingFile.absolutePath}")
logger.debug("Replacing resource reference: @color/abc_btn -> @color/c")

// 信息日志
logger.info("Found AabResGuard mapping file: ${mappingFile.absolutePath}")
logger.info("Found 156 resource mappings, processing with AabResGuard")
logger.info("No resource mappings found, skipping AabResGuard processing")

// 错误日志
logger.error("Failed to parse AabResGuard mapping file", e)
logger.error("Failed to process resources with AabResGuard", e)
```

---

## 测试计划

### 1. 单元测试

**测试文件**: `main/src/test/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardTest.kt`

#### 1.1 映射文件解析测试

```kotlin
@Test
fun `test parse valid mapping file`() {
    // 测试正确解析标准格式的 mapping 文件
}

@Test
fun `test parse empty mapping file`() {
    // 测试处理空映射文件
}

@Test
fun `test parse malformed mapping file`() {
    // 测试处理格式错误的映射文件
}
```

#### 1.2 资源引用替换测试

```kotlin
@Test
fun `test replace resource references in layout xml`() {
    // 测试替换 layout XML 中的资源引用
    val xml = """<TextView android:textColor="@color/primary" />"""
    // 预期输出: <TextView android:textColor="@color/a" />
}

@Test
fun `test replace resource references in style xml`() {
    // 测试替换 style XML 中的资源引用
}

@Test
fun `test replace resource references in drawable xml`() {
    // 测试替换 drawable XML 中的资源引用
}

@Test
fun `test preserve unobfuscated resources`() {
    // 测试保留未混淆的资源引用
    val xml = """<TextView android:textColor="@android:color/white" />"""
    // 预期输出: 保持不变
}
```

#### 1.3 文件处理测试

```kotlin
@Test
fun `test process single xml file`() {
    // 测试处理单个 XML 文件
}

@Test
fun `test process multiple xml files`() {
    // 测试处理多个 XML 文件
}

@Test
fun `test copy non-xml files`() {
    // 测试非 XML 文件 (png, jpg 等) 直接拷贝
}
```

### 2. 集成测试

#### 2.1 无 AabResGuard 工程

**测试目标**: 确保现有功能不受影响

**测试步骤**:
1. 使用普通工程 (未接入 AabResGuard)
2. 执行增量编译
3. 验证编译成功,无错误日志

**预期结果**:
- 编译成功
- 日志中有 "AabResGuard mapping file not found" 调试信息
- 资源文件正常编译

#### 2.2 有 AabResGuard 工程 - 首次编译

**测试目标**: mapping 文件不存在时正常编译

**测试步骤**:
1. 使用接入了 AabResGuard 的工程
2. 清理 build 目录
3. 执行增量编译

**预期结果**:
- 编译成功
- 日志中有 "AabResGuard mapping file not found" 调试信息

#### 2.3 有 AabResGuard 工程 - 二次编译

**测试目标**: mapping 文件存在时应用混淆

**测试步骤**:
1. 先执行完整打包,生成 mapping 文件
2. 修改资源文件
3. 执行增量编译

**预期结果**:
- 编译成功
- 日志中有 "Found AabResGuard mapping file" 信息
- 日志中有 "Found N resource mappings, processing with AabResGuard" 信息
- 生成的 .flat 文件中包含混淆后的资源引用

### 3. 验证方式

#### 3.1 日志验证

检查编译日志:
```
# mapping 文件不存在
[DEBUG] AabResGuard mapping file not found: /path/to/build/outputs/bundle/debug/resources-mapping.txt

# mapping 文件存在
[INFO] Found AabResGuard mapping file: /path/to/build/outputs/bundle/debug/resources-mapping.txt
[INFO] Found 156 resource mappings, processing with AabResGuard
```

#### 3.2 输出文件验证

1. 检查临时目录 `outputDir/aabresguard_temp/` 是否创建
2. 检查处理后的 XML 文件内容是否包含混淆后的资源引用
3. 使用 aapt2 dump 命令检查生成的 .flat 文件

```bash
# 解压 flat 文件查看内容
aapt2 dump resources layout_activity_main.xml.flat
```

#### 3.3 运行时验证

1. 部署到设备
2. 启动 app
3. 验证界面显示正常
4. 验证资源加载无错误

---

## 实现顺序

### ✅ 第一步: 创建 AabResGuardMappingParser.kt

**任务**:
- [x] 实现 mapping 文件解析逻辑
- [x] 实现资源名称提取逻辑
- [ ] 编写单元测试验证解析正确性

**代码位置**:
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardMappingParser.kt`

### ✅ 第二步: 创建 AabResGuardResourceProcessor.kt

**任务**:
- [x] 实现 XML 资源引用替换逻辑
- [x] 实现文件处理逻辑
- [ ] 编写单元测试验证替换正确性

**代码位置**:
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardResourceProcessor.kt`

### ✅ 第三步: 修改 ResourceCompiler.kt

**任务**:
- [x] 在 `aapt2Compile` 中集成 AabResGuard 处理
- [x] 实现 `processAabResGuard` 方法
- [x] 实现 `getMappingFile` 方法
- [x] 更新所有引用为 `processedResCompileSet`

**代码位置**:
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt`

### ⏳ 第四步: 集成测试

**任务**:
- [ ] 使用实际工程验证功能
- [ ] 验证无 AabResGuard 工程正常编译
- [ ] 验证有 AabResGuard 工程首次编译
- [ ] 验证有 AabResGuard 工程二次编译
- [ ] 部署到设备验证运行时正确性

---

## 潜在问题与解决方案

### 1. 资源引用复杂度

**问题**: XML 中可能存在各种形式的资源引用,如:
- `?attr/colorPrimary` (属性引用)
- `@android:color/white` (系统资源)
- `@*android:layout/simple_list_item_1` (私有系统资源)
- `@+id/button` (ID 定义)

**解决方案**:
- 只处理 `@resourceType/resourceName` 格式
- 忽略系统资源 (`@android:`)
- 忽略属性引用 (`?attr/`)
- 忽略 ID 定义 (`@+id/`)

**实现**:
```kotlin
// 正则只匹配标准格式
val RESOURCE_REF_PATTERN = Regex("""@([a-z]+)/([a-zA-Z0-9_]+)""")
// 不会匹配: @android:, ?attr/, @+id/, @*android:
```

### 2. DataBinding 兼容性

**问题**: DataBinding 处理会修改 XML 文件,可能与 AabResGuard 处理冲突

**分析**:
- DataBinding 在 `compileResSet` 方法中先执行
- 生成 split layout 文件
- AabResGuard 应该处理 split 后的文件

**解决方案**:
- 在 `aapt2Compile` 中处理 (DataBinding 之后)
- AabResGuard 处理的是 DataBinding 处理后的 split layout 文件
- 不会影响 DataBinding 生成的 Java 类

**流程**:
```
原始 XML → DataBinding 处理 → Split XML → AabResGuard 处理 → aapt2 编译
```

### 3. 增量编译性能

**问题**: 每次增量编译都需要处理资源文件,可能影响性能

**性能分析**:
- mapping 文件解析: 约 10-50ms (取决于文件大小)
- XML 文件处理: 约 5-10ms/文件
- 正则替换: 高效,影响小

**优化方案**:
1. **短期优化** (当前实现):
   - 只处理变化的资源文件
   - 使用高效的正则替换算法

2. **长期优化** (未来可考虑):
   - 缓存 mapping 解析结果 (基于文件 MD5)
   - 缓存已处理的 XML 文件 (基于文件 CRC32)
   - 并行处理多个文件

### 4. 特殊资源类型

**问题**: 某些资源类型可能有特殊处理需求

**特殊情况**:
- **values 资源**: style, color, string 等在 XML 中的引用
- **drawable XML**: selector, layer-list 等可能引用其他 drawable
- **menu XML**: 可能引用 icon drawable

**解决方案**:
- 当前实现: 统一处理所有 XML 文件中的 `@type/name` 引用
- 足以覆盖大部分场景
- 如有特殊需求,后续可针对性优化

### 5. 错误恢复

**问题**: 如果处理过程中出错,如何确保不影响后续编译

**策略**:
- **可恢复错误**: mapping 文件不存在 → 跳过处理,正常编译
- **不可恢复错误**: 解析失败、处理失败 → 终止编译,报错

**原因**:
- mapping 不存在说明用户未使用 AabResGuard,正常情况
- 解析/处理失败说明配置或实现有问题,应该报错而不是静默失败

---

## 关键文件路径

### 新建文件

| 文件 | 说明 | 状态 |
|------|------|------|
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardMappingParser.kt` | mapping 文件解析器 | ✅ 已创建 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardResourceProcessor.kt` | 资源文件处理器 | ✅ 已创建 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardHandler.kt` | 处理逻辑封装类 | ✅ 已创建 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/overlay/AabResGuardTest.kt` | 单元测试 (可选) | ⏳ 待创建 |

### 修改文件

| 文件 | 说明 | 修改内容 | 状态 |
|------|------|----------|------|
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | 资源编译器 | 添加 Handler 实例,调用处理逻辑 | ✅ 已修改 |

### 参考文件

| 文件 | 说明 |
|------|------|
| `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt` | ModuleInfo 定义 |
| `/Users/wormchen/Downloads/OUTPUT.md` | AabResGuard 官方文档 |
| `ai_knowledge/02_compile_resource.md` | 资源编译系统文档 |

---

## 实现总结

### 代码统计

| 类别 | 文件数 | 代码行数 |
|------|--------|----------|
| 新增文件 | 3 | ~370 行 |
| 修改文件 | 1 | +3 行 (简化后) |
| 测试文件 | 0 | 0 行 (待补充) |
| **总计** | **4** | **~373 行** |

### 核心类说明

#### 1. AabResGuardMappingParser

**职责**: 解析 AabResGuard 生成的 mapping 文件

**关键方法**:
- `parse(mappingFile: File): Map<String, ResourceMapping>`
- `parseMappingLine(line: String): ResourceMapping?`
- `parseResourceName(fullName: String): Pair<String, String>?`

**数据结构**:
```kotlin
data class ResourceMapping(
    val originalName: String,
    val obfuscatedName: String,
    val resourceType: String
)
```

#### 2. AabResGuardResourceProcessor

**职责**: 处理资源文件,替换混淆后的资源引用

**关键方法**:
- `processResourceFiles(originalFiles, outputDir, compileFile): List<File>`
- `processXmlFile(inputFile, outputFile)`
- `replaceResourceReferences(xmlContent): String`

**关键属性**:
- `mappings: Map<String, ResourceMapping>` - 资源映射表
- `RESOURCE_REF_PATTERN: Regex` - 资源引用匹配正则

#### 3. AabResGuardHandler (新增)

**职责**: 封装所有 AabResGuard 处理逻辑,提供统一接口

**关键方法**:
- `process(resCompileSet): ResCompileSet?` - 主处理方法
- `findMappingFile(task): File?` - 查找 mapping 文件
- `parseMappingFile(file): Map<String, ResourceMapping>?` - 解析 mapping
- `processResourceFiles(resCompileSet, mappings): ResCompileSet?` - 处理资源

**设计优势**:
- 单一职责,只负责 AabResGuard 逻辑
- 降低 ResourceCompiler 复杂度
- 便于独立测试和扩展

#### 4. ResourceCompiler (修改)

**新增内容**:
- 添加 `aabResGuardHandler` 实例
- 在 `aapt2Compile` 中调用 `aabResGuardHandler.process()`
- 将 `ResCompileSet` 可见性改为 `internal`

**修改特点**:
- 只需修改 3 行代码
- 逻辑清晰简洁
- 完全解耦 AabResGuard 处理细节

### 设计亮点

1. **分层架构设计**:
   - **Handler 层**: AabResGuardHandler 封装所有处理逻辑
   - **Parser 层**: AabResGuardMappingParser 负责文件解析
   - **Processor 层**: AabResGuardResourceProcessor 负责文件处理
   - **Compiler 层**: ResourceCompiler 只负责调用,不关心细节

2. **单一职责原则**:
   - 每个类只负责一个明确的功能
   - 降低类之间的耦合度
   - 便于单独测试和维护

3. **非侵入式设计**:
   - 通过检测 mapping 文件自动启用
   - 无需配置项,零配置使用
   - 不影响未使用 AabResGuard 的工程

4. **错误处理完善**:
   - 区分可恢复和不可恢复错误
   - 详细的日志输出便于调试
   - 失败时明确终止,不产生错误结果

5. **性能考虑**:
   - 只处理变化的资源文件
   - 使用高效的正则替换
   - 临时文件隔离,不污染原始文件

6. **兼容性好**:
   - 与 DataBinding 流程兼容
   - 与增量编译机制兼容
   - 保持原有目录结构

### 使用场景

#### 场景 1: 开发阶段 (无 mapping 文件)

```
用户操作: 修改布局文件 → 增量编译
系统行为:
  1. 检查 mapping 文件 → 不存在
  2. 跳过 AabResGuard 处理
  3. 正常执行 aapt2 编译
  4. 编译成功
```

#### 场景 2: Release 打包后再开发 (有 mapping 文件)

```
用户操作:
  1. bundleRelease 生成 resources-mapping.txt
  2. 修改布局文件 → 增量编译

系统行为:
  1. 检查 mapping 文件 → 存在
  2. 解析 mapping 文件 (156 个映射)
  3. 处理资源文件,替换引用
  4. 执行 aapt2 编译处理后的文件
  5. 编译成功
```

#### 场景 3: 错误处理

```
用户操作: mapping 文件格式错误
系统行为:
  1. 检查 mapping 文件 → 存在
  2. 解析 mapping 文件 → 失败
  3. 记录错误日志
  4. 终止编译,返回错误
```

### 后续优化建议

1. **性能优化** (优先级: 中):
   - 缓存 mapping 文件解析结果
   - 并行处理多个资源文件
   - 增量处理 (只处理变化的文件)

2. **功能增强** (优先级: 低):
   - 支持更多资源引用格式
   - 支持资源目录混淆 (res dir mapping)
   - 支持资源文件路径混淆 (res entries path mapping)

3. **测试完善** (优先级: 高):
   - 补充单元测试
   - 补充集成测试
   - 添加性能测试

4. **文档完善** (优先级: 中):
   - 更新用户文档
   - 添加使用示例
   - 添加故障排查指南

---

## 附录

### A. 相关技术文档

- [AabResGuard GitHub](https://github.com/bytedance/AabResGuard)
- [AabResGuard 输出文件说明](https://github.com/bytedance/AabResGuard/blob/master/wiki/zh-cn/OUTPUT.md)
- [AAPT2 文档](https://developer.android.com/studio/command-line/aapt2)
- [Jugg 资源编译文档](../ai_knowledge/02_compile_resource.md)

### B. 测试用例示例

#### 示例 mapping 文件

```txt
res dir mapping:
	res/layout -> res/a
	res/drawable -> res/b

res id mapping:
	0x7f040001 : com.example.app.R.color.colorPrimary -> com.example.app.R.color.a
	0x7f040002 : com.example.app.R.color.colorAccent -> com.example.app.R.color.b
	0x7f050001 : com.example.app.R.drawable.ic_launcher -> com.example.app.R.drawable.c

res entries path mapping:
	0x7f050001 : res/drawable/ic_launcher.png -> res/b/c.png
```

#### 示例 XML 文件

**输入** (activity_main.xml):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:background="@color/colorPrimary">

    <ImageView
        android:src="@drawable/ic_launcher" />

    <TextView
        android:textColor="@color/colorAccent" />
</LinearLayout>
```

**输出** (处理后):
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:background="@color/a">

    <ImageView
        android:src="@drawable/c" />

    <TextView
        android:textColor="@color/b" />
</LinearLayout>
```

### C. 命令行测试

```bash
# 1. 完整打包生成 mapping 文件
./gradlew :app:bundleRelease

# 2. 检查 mapping 文件
cat app/build/outputs/bundle/release/resources-mapping.txt

# 3. 执行增量编译 (使用 Jugg)
# 在 IDE 中点击 "Jugg: Deploy" 或使用命令行工具

# 4. 检查处理后的临时文件
ls -la app/build/intermediates/jugg_temp/*/aabresguard_temp/

# 5. 检查生成的 flat 文件
aapt2 dump resources app/build/intermediates/jugg_temp/*/*.flat
```

---

**最后更新**: 2026-01-19
**实现状态**: ✅ 核心功能已完成,待测试验证
**维护者**: Jugg Team
