# 方案: 引入 `usage.txt` 以为 `_jugg_fix` 中已被删除的方法生成兼容 stub

## 背景

当前 release/minify 场景下，`DexMinifyCompiler.generateJuggFixClasses()` 是基于原始 `.class` 文件生成 `_jugg_fix` 桥接类，然后再走 `D8 -> obfuscate() -> renameDexClassDeclaration()`。

这条链路已经解决了：

- `_jugg_fix` 内部类引用未混淆
- `_jugg_fix` 方法名/字段名与调用方不一致
- `_jugg_fix` 输出路径错误
- `_jugg_fix` 桥接类的 field / `<clinit>` 非法写入

但对于 **release 中已被 R8/ProGuard 删除的方法**，当前 `_jugg_fix` 仍会从原始 `.class` 中把它们完整生成出来。对于 `LogUtil.d/v` 这类被 `-assumenosideeffects + shrink` 删除的方法，这会让 `_jugg_fix` 重新携带一组 **APK 运行时并不存在的成员实现**，与线上成员集合不一致。

本次方案的目标是：

**把 `usage.txt` 纳入增量编译输入，在生成 `_jugg_fix` 时识别 release 中已删除的方法，并将其重写为 compatibility stub（保留签名、挖空实现），既避免桥接类继续执行线上不存在的旧实现，也避免调用点因缺少方法声明而直接 `NoSuchMethodError`。**

---

## 目标

### 目标 1：`usage.txt` 初始化时机与 `R8MappingReader` 保持一致

- 与 `mapping.txt` 一样，作为 release/minify 场景的附加构建产物按需加载
- 不在 debug / 无 mapping 场景强制依赖 `usage.txt`
- 保持「文件存在时启用，不存在时降级」的行为

### 目标 2：`ModuleBuildPathInfo` 支持拉取 `usage.txt`

- 本地构建路径模型中增加 `usage.txt` 的标准定位
- 远端 classpath / build 产物同步链路能把 `usage.txt` 一并拉回本地

### 目标 3：`_jugg_fix` 生成时将已删除方法重写为 compatibility stub

- 对 `usage.txt` 标记为已删除的方法，`_jugg_fix` 中 **保留方法声明，但方法体改为默认返回/空实现**
- 保持其余未删除方法继续走当前桥接逻辑
- 不改变现有 `mapping.txt` 驱动的混淆/重定向主流程

---

## 非目标

- **不**尝试根据 `usage.txt` 恢复被删方法的完整语义；compatibility stub 只保证签名可解析、方法体安全返回
- **不**在本方案内改造 `DeployDataDatabaseSqLiteHelper` 的 minify 检测口径
- **不**在首期按方法级粒度撤销 `DexObfuscator` 的 class-level redirect；兼容性由 `_jugg_fix` stub 承担
- 首期方案仅要求“已删除方法不再保留原始实现”；字段级删除信息可先保留为 reader 能力，后续按需要接入 `_jugg_fix`

---

## 现状代码落点

### 1. `mapping.txt` 的当前初始化与消费

- `ICompiler.kt`
  - `val mappingFile get() = applicationModule?.buildPathInfo?.mappingFile`
  - `val isMinified get() = mappingFile?.exists() == true`
- `DexMinifyCompiler.kt`
  - `initIfNeeded()` 中按需加载 `mappingFile`
  - `obfuscator = DexObfuscator.fromMappingFile(mappingFile)`
- `R8MappingReader.kt`
  - 提供 `fromFile()` / `fromPath()` / `fromString()` 三种入口

### 2. 构建产物同步链路

- `ModuleBuildPathInfo` 的 `allBuildPathRelative` 决定哪些构建产物会被本地 / 远端同步
- `FetchClasspathCommand.getRsyncArguments()` / `SyncLocalClasspathCommand` 基于 `allBuildPathRelative` 拼接 include filter
- `ClasspathBackupHelper.fetch()` 会把同步回来的目录重新包装成新的 `ModuleBuildPathInfo`

### 3. `_jugg_fix` 生成链路

- `DexMinifyCompiler.generateJuggFixClasses(minifyInfo, outputDir)`
- 现状流程：
  1. 从 `minifyInfo.classFiles` 取原始 `.class`
  2. 直接交给 `DexFileMaker.dex()` 生成 DEX
  3. `obfuscator.obfuscate()`
  4. `renameDexClassDeclaration()` 改为 `_jugg_fix`

问题点在于：**当前是整类照搬原始 `.class`，没有基于 release 最终成员集合做裁剪。**

---

## 方案总览

### 总体思路

新增一个与 `R8MappingReader` 平行的 `R8UsageReader`，负责读取 `usage.txt` 中“被删除的类/成员”信息。

`DexMinifyCompiler` 在与 `mapping.txt` 同一初始化阶段加载 `usage.txt`。当生成 `_jugg_fix` 时，先把原始 `.class` 经过一次 **基于 usage 的 compatibility stub 重写**，再送入现有的 `D8 -> obfuscate() -> rename` 流程。

即：

```text
原始 .class
  -> rewriteDeletedMethodsAsCompatibilityStubs(usage)
  -> D8
  -> obfuscate(mapping)
  -> renameDexClassDeclaration(..._jugg_fix)
```

这样 `_jugg_fix` 的最终行为会更贴近 release APK：

- 未被删除的方法：继续生成并参与桥接
- 已被删除的方法：保留声明，但方法体改成默认返回/空实现，不再执行 release 中已不存在的旧实现

---

## 详细设计

## 1. 新增 `usage.txt` 路径接入

### 1.1 `ModuleBuildPathInfo` 增加 `usageFile`

文件：`main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`

新增：

- `val usageFile get() = File(buildDir, "outputs/mapping/$buildVariant/usage.txt")`

并将其加入：

- `allBuildPathRelative`

这样本地 / 远端构建产物同步时，会和 `mapping.txt` 一起把 `usage.txt` 拉回。

### 1.2 对序列化链路的影响

本次建议 **不修改** `ModuleBuildPathInfo` 的构造参数，只新增计算属性：

- `BuildPathInfoSerializer.kt`：无需改动
- `ProjectInfoSerializerInGradle.kt`：无需改动
- `ClasspathBackupHelper.kt`：无需改动

原因：`usageFile` 与 `mappingFile` 一样，都是由 `buildDir + variant` 推导出的派生路径，不需要额外持久化。

### 1.3 编译上下文增加 `usageFile` 访问入口

文件：`main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`

新增：

- `val usageFile get() = applicationModule?.buildPathInfo?.usageFile`

说明：

- `isMinified` 仍然只由 `mappingFile` 判定
- `usageFile` 只是一个增强输入，不改变“是否属于 minified 构建”的定义

---

## 2. 新增 `R8UsageReader`

### 2.1 新文件

建议新增文件：

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8UsageReader.kt`

### 2.2 API 形态与 `R8MappingReader` 对齐

建议提供：

- `fromFile(file: File)`
- `fromPath(path: Path)`
- `fromString(content: String)`

这样后续测试写法、调用习惯和 `R8MappingReader` 保持一致。

### 2.3 reader 输出模型

建议先聚焦到 `_jugg_fix` 真正需要的最小信息：

```kotlin
RemovedClassUsage(
    originalClassName: String,
    removedMethods: Set<RemovedMethodSignature>,
    removedFields: Set<String> = emptySet(),
    isClassRemoved: Boolean = false,
)
```

其中 `RemovedMethodSignature` 建议至少包含：

- `name`
- `parameterTypes`

首期可不依赖返回值做唯一匹配，因为 Java/Kotlin 重载只由参数区分；但 reader 内部保留返回值字段也可以，便于后续扩展。

### 2.4 解析策略

`usage.txt` 需要支持两类信息：

- **整类删除**：`com.example.Foo`
- **成员删除**：`com.example.Foo.bar(...)` / `com.example.Foo.FIELD`

解析时统一归一到 **原始类名维度**，因为 `_jugg_fix` 的输入也是原始 `.class` 文件。

首期可优先满足：

- 类名查询：`isClassRemoved(className)`
- 方法查询：`isMethodRemoved(className, methodName, parameterTypes)`
- 批量查询：`getRemovedMethods(className)`

### 2.5 缓存策略

建议与现有 mapping 初始化方式保持同一层级语义：

- `DexMinifyCompiler.initIfNeeded()` 第一次进入时加载
- 以 `absolutePath + lastModified()` 作为缓存 key
- 文件未变化时复用 reader 实例

这里不要求一定把缓存做在 `R8UsageReader` 内部；也可以像 `DexMinifyCompiler` 当前持有 `obfuscator` 一样，由 compiler 层懒加载并复用。

---

## 3. `DexMinifyCompiler` 初始化扩展

文件：`main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt`

### 3.1 新增状态

建议新增：

- `private var usageReader: R8UsageReader? = null`
- 可选：`private var usageReaderCacheKey: String? = null`

### 3.2 初始化时机

在 `initIfNeeded()` 中，与 `mappingFile` 加载放在同一阶段：

1. 判断 `context.isMinified && mappingFile.exists()`
2. 初始化 `obfuscator`
3. 读取 `context.usageFile`
4. 若 `usage.txt` 存在，则初始化 `usageReader`
5. 若不存在，则仅记录 debug/warn，并继续走现有逻辑

这样满足“**usage 初始化时机和 `R8MappingReader.kt` 一致**”的要求：

- 都是 release/minified 场景按需加载
- 都在 obfuscation 入口阶段完成初始化
- 都支持文件级复用而不提前常驻

### 3.3 缺失时的退化行为

- `usage.txt` 缺失：保持当前行为，不裁剪 `_jugg_fix` 方法
- `usage.txt` 解析失败：记录日志，退化为“不裁剪”
- 不能因为 `usage.txt` 缺失而阻断 release 增量编译

---

## 4. `_jugg_fix` 生成时按 usage 重写已删方法

### 4.1 重写位置

建议在 **class 阶段** 重写，而不是 DEX 阶段：

- 输入仍然是原始 `.class`
- 使用 ASM 做方法级 stub 重写最直接
- 重写后再交给 D8，避免后续再做 DEX 级 method body patch
- 可复用项目里现有 ASM 依赖（`com.sickworm.intellij.jugg.org.objectweb.asm.*`）

### 4.2 新增 helper

建议在 `DexMinifyCompiler.kt` 内新增私有 helper，例如：

- `rewriteDeletedMethodsAsCompatibilityStubs(className: String, classBytes: ByteArray, usageReader: R8UsageReader): ByteArray`

实现方式：

1. `ClassReader` 读取原始 `.class`
2. `ClassWriter` 输出新字节码
3. 自定义 `ClassVisitor.visitMethod()`
4. 若当前普通方法命中 `usageReader.isMethodRemoved(...)`，则保留方法签名，但将方法体重写为默认返回/空实现的 compatibility stub
5. 其余方法照常保留

### 4.3 方法匹配规则

建议匹配 key 统一为：

- 原始类名（dot format）
- 原始方法名
- 原始参数列表（dot format）

并统一处理：

- 普通方法
- 构造方法 `<init>`（如 `usage.txt` 有记录则可一并过滤）
- `<clinit>` 不需要由 usage 决定，因为现有 `renameDexClassDeclaration()` 已经统一剥离

### 4.4 与现有 `_jugg_fix` 流程整合

`generateJuggFixClasses()` 调整为：

1. 遍历 `minifyInfo.classFiles`
2. 对每个 classFile 读取原始 bytes
3. 若 `usageReader` 存在，则先执行 `rewriteDeletedMethodsAsCompatibilityStubs(...)`
4. 将重写后的 class bytes 写入临时目录（或内存落盘）
5. 把重写后的 `.class` 文件列表交给 `DexFileMaker.dex()`
6. 后续仍走现有 `obfuscate() -> renameDexClassDeclaration()`

这样不会破坏已经稳定的混淆/桥接流程，只是在其前面插入一层 **usage-aware compatibility stub rewrite**。

### 4.5 为什么不在 `DexObfuscator` 里做

不建议把“删除方法”逻辑放进 `DexObfuscator`：

- `DexObfuscator` 的职责是 **重映射**，不是 **结构裁剪**
- `_jugg_fix` 的方法剥离只服务于生成阶段，不应污染普通增量 DEX 混淆路径
- 如果放到 DEX 层做删除，改动范围更大，测试成本更高

---

## 5. `MinifyInfo` 是否需要扩展

本次建议 **先不改** `CompileEffectAnalyzer.getMinifyInfo()` 和 `MinifyInfo` 结构。

理由：

- `usage.txt` 是 release 构建产物的静态信息，不依赖 deploy DB 实时计算
- `_jugg_fix` 的 deleted-method 裁剪只发生在 `DexMinifyCompiler.generateJuggFixClasses()`
- 将 usage 直接作为 `DexMinifyCompiler` 的增强输入，更符合职责边界

也就是说：

- `MinifyInfo` 仍负责“哪些类需要 `_jugg_fix`”
- `R8UsageReader` 负责“这些类里哪些方法需要改写成 compatibility stub”

如果后续发现字段级删除、method-level redirect、fallback 策略也需要共享 usage 信息，再考虑把 usage 结果上提到 `MinifyInfo`。

---

## 6. TDD 计划

根据现有测试索引，建议优先复用 / 新增以下测试：

### 6.1 `R8UsageReader` 单测

建议新增：

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8UsageReaderTest.kt`

覆盖点：

1. `fromString()` 能解析整类删除
2. `fromString()` 能解析方法删除
3. 方法参数类型可正确规范化
4. 类不存在 / 方法不存在时返回 false
5. 空文件 / 注释 / 非法行可容错

### 6.2 `ModuleBuildPathInfo` 路径测试

可新增到已有测试文件，或补一个轻量测试：

- 验证 `usageFile` 路径为 `build/outputs/mapping/<variant>/usage.txt`
- 验证 `allBuildPathRelative` 已包含 `usage.txt`

### 6.3 `DexMinifyCompiler` 行为测试

优先复用 `DexMinifyCompilerPhase2Test.kt`，或新增更聚焦测试：

- `DexMinifyCompilerUsagePruneTest.kt`

覆盖点：

1. `usage.txt` 标记删除的方法仍会保留在生成的 `_jugg_fix` DEX 中，但方法体会被重写为 compatibility stub
2. 未删除的方法仍然保留原有桥接逻辑
3. `usage.txt` 缺失时回退到当前行为
4. `usage.txt` 存在但目标类无删除方法时，不改变输出

如果需要更低成本验证，可直接构造最小 class bytes + usage string，不强依赖真实 Android Demo APK。

---

## 风险与边界

### 风险 1：`usage.txt` 的格式可能随 R8/AGP 版本存在差异

应对：

- reader 先做最小闭环：支持当前项目已验证的 usage 输出格式
- 测试使用 `fromString()` 覆盖多个样例
- 对无法识别的行忽略，不阻断编译

### 风险 2：当前仍是 class-level redirect，compatibility stub 只保证“可调用”不保证“原语义”

这是一个**有意识的边界**：

- 本方案目标是让 `_jugg_fix` 不再执行 release 中已删除的方法实现，同时避免调用点因缺少方法声明而 crash
- compatibility stub 只提供默认返回/空实现，不代表恢复了 release 构建时被优化删除的真实语义
- 如果开发者本次增量修改依赖这些被删方法的副作用或返回值，仍应视为与线上最终 APK 语义不兼容，必要时回退完整 Gradle 构建验证

### 风险 3：字段级删除尚未接入 `_jugg_fix` 剪枝

首期先完成方法级闭环，因为当前直接 crash 点主要来自方法调用。

但 reader 结构建议为字段删除预留扩展位，后续若发现 `_jugg_fix` 仍因 deleted field 访问而不一致，可沿相同模式补齐。

---

## 推荐落地顺序

### Phase 1：输入链路打通

1. `ModuleBuildPathInfo` 增加 `usageFile`
2. `allBuildPathRelative` 纳入 `usage.txt`
3. `ICompiler` 增加 `usageFile` getter
4. `DexMinifyCompiler.initIfNeeded()` 加入 `usageReader` 初始化

### Phase 2：reader 与测试

1. 新增 `R8UsageReader.kt`
2. 新增 `R8UsageReaderTest.kt`
3. 覆盖 usage 基础格式、方法查询、异常容错

### Phase 3：`_jugg_fix` compatibility stub 重写

1. 在 `generateJuggFixClasses()` 前插入 ASM method stub rewrite
2. 复用 / 新增 `DexMinifyCompiler` 测试验证删除方法仍保留声明，但方法体已被重写为 compatibility stub
3. 验证 `usage.txt` 缺失时的退化路径

---

## 预期结果

方案完成后，release/minify 增量编译在面对 `LogUtil.d/v` 这类已被线上 APK 删除的方法时：

- `mapping.txt` 继续负责名字映射与 `_jugg_fix` 桥接
- `usage.txt` 负责提供“最终哪些方法已经不存在”的事实依据
- `_jugg_fix` 会为这些方法保留声明，但方法体改成默认返回/空实现的 compatibility stub
- 生成出来的桥接类既能承接 class-level redirect，又不会再执行 release 已删除成员的旧实现

---

## 执行状态

- [x] TDD 失败测试编写完成
- [x] 业务代码实现
- [x] 测试全部通过
- [x] ai_knowledge 文档同步
