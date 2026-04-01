# Jugg Release 混淆增量编译：完整迭代过程

> 创建日期：2026-04-01  
> 范围：从 mapping.txt 读取能力到 _jugg_fix compatibility stub 的全链路演进  
> 关键词：obfuscate, resguard, minify, release, DexObfuscator, _jugg_fix, R8, mapping.txt, usage.txt

---

## 目录

- [Phase 1：mapping.txt 读取与类名混淆基础能力](#phase-1mappingtxt-读取与类名混淆基础能力)
- [Phase 2：ClassObfuscator 重构与 inline 方法检测](#phase-2classobfuscator-重构与-inline-方法检测)
- [Phase 3：minify 编译支持与 DexObfuscator 替换](#phase-3minify-编译支持与-dexobfuscator-替换)
- [Phase 4：AabResGuard 资源混淆集成](#phase-4aabresguard-资源混淆集成)
- [Phase 5：R8 inline 影响收敛与 _jugg_fix 机制](#phase-5r8-inline-影响收敛与-_jugg_fix-机制)
- [Phase 6：DexObfuscator 注解类型映射修复](#phase-6dexobfuscator-注解类型映射修复)
- [Phase 7：DexObfuscator 字节码级类型引用补全](#phase-7dexobfuscator-字节码级类型引用补全)
- [Phase 8：access flag 宽化与 invoke 指令同步](#phase-8access-flag-宽化与-invoke-指令同步)
- [Phase 9：ExternalSyntheticLambda 编号漂移与接口/父类优先映射](#phase-9externalsyntheticlambda-编号漂移与接口父类优先映射)
- [Phase 10：DexObfuscator R8 synthesized 方法映射修复](#phase-10dexobfuscator-r8-synthesized-方法映射修复)
- [Phase 11：EffectedType 体系与 MINIFY_MEMBER_REMOVED 引入](#phase-11effectedtype-体系与-minify_member_removed-引入)
- [Phase 12：getMinifyInfo 数据源修复（staging → compileFiles → 预混淆）](#phase-12getminifyinfo-数据源修复staging--compilefiles--预混淆)
- [Phase 13：_jugg_fix 内部类引用混淆](#phase-13_jugg_fix-内部类引用混淆)
- [Phase 14：_jugg_fix 完整混淆链路（obfuscate-then-rename）](#phase-14_jugg_fix-完整混淆链路obfuscate-then-rename)
- [Phase 15：usage.txt 接入与 _jugg_fix compatibility stub](#phase-15usagetxt-接入与-_jugg_fix-compatibility-stub)

---

## Phase 1：mapping.txt 读取与类名混淆基础能力

### 背景

Jugg 增量编译产物使用原始类名（如 `com.example.MyClass`），而 release APK 中类已被 R8 混淆为短名（如 `a.b`）。增量 DEX 部署到设备后，ClassLoader 按混淆名加载，导致找不到增量编译的类。

### 变更内容

1. 新增 `R8MappingReader`，提供 `fromFile()` / `fromPath()` / `fromString()` 三种入口，解析 R8 产出的 `mapping.txt`，构建类名、方法名、字段名的双向映射表。
2. 新增 `ClassObfuscator`，基于 ASM `ClassRemapper` + `Remapper` 实现 `.class` 文件级别的名称重映射。ASM 的 `ClassRemapper` 自动覆盖所有类型引用位置（LDC Type、ANEWARRAY、CHECKCAST、异常表等）。
3. `isMinified` 判定条件定义为 `mappingFile?.exists() == true`（`ICompiler.kt`）。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `481087708` | — | [WIP] supports read mapping.txt |
| `4352cca78` | — | add testParseMapping |
| `b99811b2a` | — | R8MappingReader supports method invocation |

### 关联文件

- `main/.../compiler/obfuscation/R8MappingReader.kt`
- `main/.../compiler/obfuscation/ClassObfuscator.kt`

---

## Phase 2：ClassObfuscator 重构与 inline 方法检测

### 背景

直接使用 `R8MappingReader` 做映射的代码分散且内存占用较高。同时 R8 的 inline 优化会将方法体展开到调用方类中，需要检测这些 inline 关系以确定增量编译的影响范围。

### 变更内容

1. 重构为 `ClassObfuscator`，封装映射逻辑并引入缓存机制，统一由 `ClassObfuscator` 代替分散的 `R8MappingReader` 直接调用。
2. `R8MappingReader` 扩展支持 method invocation 解析，用于后续 inline 链分析。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `d0dc3114a` / `6e0819567` | — | [optimize] optimize memory [refactor] use ClassObfuscator instead of R8MappingReader directly |
| `e16f81a91` / `b99811b2a` | — | [WIP] supports find inline effects class; R8MappingReader supports method invocation |

---

## Phase 3：minify 编译支持与 DexObfuscator 替换

### 背景

`ClassObfuscator` 在 `.class` 阶段做混淆，但 desugar 阶段需要接口类的原始名称来查找 classpath。如果在 desugar 之前混淆，接口类已变为混淆名导致 desugar 找不到依赖类而失败。

### 变更内容

1. 新增 `DexObfuscator`，基于 dex2jar 的 `DexFileVisitor` 实现 DEX 级别的名称重映射。将混淆阶段从 class 级别后移到 dex 级别，编译流程变为 `javac → D8 (desugar) → DexObfuscator`，解决 desugar 依赖未混淆接口名的问题。
2. `DexObfuscator` 实现类名、方法名、字段名、超类、接口、方法调用、字段访问、注解中类型引用的重映射，提供文件/字节数组/输入流三种输入方式，内置缓存机制。
3. 新增 `ClassMinifyCompiler`（class 级别）和 `DexMinifyCompiler`（dex 级别）作为编译阶段的混淆编排入口。
4. `CompileOrder` 新增 `minify` 阶段，位于 `source` 和 `dex` 之间。
5. 新增 minify 相关测试。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `b4641e2b2` / `57b59450e` | — | [WIP] supports obfuscator for class |
| `3c0068d22` / `b29e70cca` | — | [feature] supports minify compilation |
| `79309bc2d` / `db367cf57` | — | [test] add minify test |
| `0b8c034e2` / `e37959360` | — | [bugfix] fix desugar failed by interface class not provided, because class has already obfuscation [feature] replace ClassObfuscator by DexObfuscator, minify after dex |

### 关联文档

- `docs/task/DEX_OBFUSCATOR_IMPLEMENTATION.md`

---

## Phase 4：AabResGuard 资源混淆集成

### 背景

接入了 AabResGuard（字节跳动开源）的工程在编译期对资源 ID 做混淆。Jugg 增量编译资源文件时，XML 中的 `@type/name` 引用仍为原始名，而 APK 中资源已被混淆，导致资源加载异常。

### 变更内容

1. 新增 `AabResGuardMappingParser`：解析 `resources-mapping.txt` 中 `res id mapping` 部分，构建 `原始资源名 → 混淆后资源名` 映射。
2. 新增 `AabResGuardResourceProcessor`：替换 XML 文件中 `@type/name` 格式的资源引用为混淆名。
3. 新增 `AabResGuardHandler`：封装整体处理流程，由 `ResourceCompiler.aapt2Compile()` 在 aapt2 编译前调用。
4. mapping 文件路径为 `build/outputs/bundle/{variant}/resources-mapping.txt`，不存在时跳过处理。
5. 在 `aapt2-inclink` 中增加 de-obfuscation 能力。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `fdf62333a` / `ab9d6f63e` | — | [WIP] supports AabResGuard |
| `7120c33dc` / `7d6c3edab` | — | AabResGuardTest test pass |
| `c5a6d20c1` / `9b00c7a31` | — | [bugfix] fix some issues |
| `768a49a32` / `ece0d71a4` | — | [feature] de-obfuscation by aapt2-inclink |
| `b94c0b837` / `b30180759` | — | [feature] de-obfuscation by aapt2-inclink |

### 关联文档

- `docs/task/AabResGuard_Integration_Plan.md`

---

## Phase 5：R8 inline 影响收敛与 _jugg_fix 机制

### 背景

R8 全量构建时会将方法内联到调用方类中。当被内联方法的实现发生变更时，所有持有旧内联副本的调用方类都需要更新。传统做法是将这些类标记为 `SOURCE` 触发源码重编译，导致编译范围级联膨胀。

### 变更内容

此阶段通过三个提交（`b0654df4e` → `ac03afe2c` → `e81cf1297`）完成：

**提交 1（方案骨架）：**
1. 引入 `MinifyInfo` 数据结构承载内联影响信息。
2. `ICompileContext` 新增 `getMinifyInfo()`。
3. `EffectedType.CLASS` 重命名为 `EffectedType.INLINE_IMPL_CHANGE`。
4. `DeployFileManager.getRecompileFiles()` 对 `INLINE_IMPL_CHANGE` 返回空列表，停止走源码级联重编译路径。
5. `DexObfuscator` 增加 `obfuscateWithInlineRedirect()` 雏形。

**提交 2（清理）：**
1. `MinifyInfo` 收敛为最小字段。

**提交 3（Phase 2 实现）：**
1. `MinifyInfo` 恢复 `classFiles` 字段。
2. `DexMinifyCompiler` 新增 `generateJuggFixClasses()`：从原始 `.class` 文件生成 `_jugg_fix` 后缀的副本类 → D8 转 DEX → 作为编译产物并入部署。
3. `DexObfuscator.obfuscateWithInlineRedirect()` 中 `mapType()` 增加重定向逻辑：`原类型 → _jugg_fix 类型 → 再做 mapping 混淆映射`。

**运行机制：**
- `InlineMethodDetector` 标记受 R8 内联影响的类为 `INLINE_IMPL_CHANGE`
- 不触发源码重编译，改为生成 `_jugg_fix` 副本类
- 增量 DEX 中对原类的引用重定向到 `_jugg_fix` 类

### 同期辅助变更

| commit | 日期 | 说明 |
|--------|------|------|
| `efc008877` | — | supports detect inlined methods / removed classes |
| `7e09e4906` | — | supports find source file in classpath |
| `88306f54d` | — | supports redex inlined methods / removed classes |
| `4e9c207a5` | — | keep origin application and app component |
| `116f49529` / `b048976b6` | — | fix super class not obfuscate |
| `1e81fa217` / `80720685f` | — | fix obfuscated class can not redex |
| `5cc8ec84c` / `6d08a6dd3` | — | supports find obfuscated source file in classpath |
| `850f40df4` / `667c3d0a3` | — | auto switch to embed mode for release apk |
| `654a55363` / `9f225ed06` | — | refactor logic of InlineMethodDetector |
| `2f80842dc` | 2026-02-26 | Implement R8 inline method change handling with _jugg_fix class generation and improve minify recompile checks by considering class hierarchy; Fix release compiling will not stop |
| `52cb92b98` / `f0f327567` | — | add release build test |

### 关联文档

- `docs/task/RELEASE_APK_INLINE_SCOPE_FIX_SUMMARY.md`

---

## Phase 6：DexObfuscator 注解类型映射修复

### 背景

release APK 增量编译部署后，EventBus 报 `Subscriber class MainTabActivity has no public methods with the @Subscribe annotation`。

### 根因

`DexObfuscator` 的 `visitMethod()` 返回的 `DexMethodVisitor` 未重写 `visitAnnotation()`，注解类型描述符（如 `Lorg/greenrobot/eventbus/Subscribe;`）未经 `mapType()` 映射。staging DEX 中注解保持原始名，而运行时框架通过混淆名查找注解，匹配失败。

### 修复

在 `DexMethodVisitor` 和 `DexFieldVisitor` 中重写 `visitAnnotation()`，对注解类型描述符的 `name` 参数调用 `mapType()`。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `713c5e919` | 2026-03-26 | [bugfix] Fix runtime crash on release apk reason: annotation not remapped |

### 关联文档

- `docs/task/dex_obfuscator_annotation_type_mapping_fix.md`
- `docs/ai_knowledge/09_plugin_runtime_debug.md` §4.4

---

## Phase 7：DexObfuscator 字节码级类型引用补全

### 背景

release APK 增量编译后出现 `NoClassDefFoundError`，crash 的类未被增量编译和部署，但被增量编译的类在字节码中引用了该类（通过 `const-class`、`filled-new-array`、`try-catch` 异常类型等指令）。

### 根因

dex2jar 的 visitor 模式不像 ASM 的 `ClassRemapper` 自动处理所有类型引用，每个含类型引用的 `DexCodeVisitor` 方法需手动覆写。`DexObfuscator` 遗漏了 `visitConstStmt`（`const-class`）、`visitFilledNewArrayStmt`（`filled-new-array`）、`visitTryCatch`（异常类型描述符）等方法中的类型映射。

### 修复

补全 `DexCodeVisitor` 中所有含类型引用的方法覆写：

| 方法 | 类型引用位置 | 典型 DEX 指令 |
|------|------------|-------------|
| `visitConstStmt(Op, int, Object)` | value 为 DexType 时 | `const-class` |
| `visitFilledNewArrayStmt(Op, int[], String)` | 第三参数为类型描述符 | `filled-new-array` |
| `visitTryCatch(...)` | String[] 为异常类型描述符数组 | `.catch` |
| `visitTypeStmt(Op, int, int, String)` | 第四参数为类型描述符 | `new-instance`/`check-cast` |
| `visitFieldStmt(Op, int, int, Field)` | Field 的 owner/type | `iget`/`sput` 等 |
| `visitMethodStmt(Op, int[], Method)` | Method 的 owner/proto | `invoke-*` |
| `visitMethodStmt(Op, int[], Method, Proto)` | invoke-polymorphic | `invoke-polymorphic` |
| `visitMethodStmt(Op, int[], String, Proto, MethodHandle, Object...)` | bsmArgs 中的类型 | `invoke-custom` |

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `34953648e` | 2026-03-27 | [bugfix] Fix runtime crash on release apk reason: const call not remapped |

### 关联文档

- `docs/ai_knowledge/09_plugin_runtime_debug.md` §4.5

---

## Phase 8：access flag 宽化与 invoke 指令同步

### 背景

R8 全量构建启用 `-allowaccessmodification` 时，会将 `private`/`protected`/`package-private` 成员宽化为 `public`。Jugg 增量编译的 `javac → D8 → DexObfuscator` 链路不做此宽化，导致增量产物中方法 access flags 与 APK 不一致。

### 问题演进

**Crash 1（IllegalAccessError）：**
APK 中 `ExternalSyntheticLambda8` 调用宿主类的 `lambda$onResume$6$...()`，R8 已将该方法宽化为 `public`。增量编译后该方法仍为 `private`，lambda 类无法访问。

**方案 E（无条件宽化）：**
`DexObfuscator` 新增 `widenAccessFlags()`，将所有 `private`/`protected`/`package-private` 成员无条件宽化为 `public`。解决了 IllegalAccessError。

**Crash 2（IncompatibleClassChangeError）：**
R8 对某些 `private` 非 static 方法选择不宽化（接口中的 private 方法、synthetic 方法、命名冲突）。方案 E 无条件宽化将这些方法从 DEX direct section 移到 virtual section，但 APK 中调用者仍使用 `invoke-direct` 指令。

**方案 D（从 APK DB 精确对齐）：**
通过 `DeployDataDatabase.getClassNodes()` 查询 APK 中真实 access flags，在 `DexObfuscator` 中精确对齐。实现正确但依赖 DB 查询，增加了 `ICompileContext`/`BaseCompileContext`/`DeployFileManager` 三层传参。

**方案 E'（最终方案 — 无条件宽化 + invoke 指令同步）：**
1. 无条件宽化所有 `private`/`protected`/`package-private` 为 `public`。
2. 对增量 DEX 内部本类的 `invoke-direct` 指令（非 `<init>`、非 `static`），同步改为 `invoke-virtual`。
3. 外部 APK 中 `private` 方法天然不可能被外部类直接调用，因此宽化不影响外部调用者。
4. 同时处理 `INVOKE_DIRECT` 和 `INVOKE_DIRECT_RANGE` 两种变体。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `0eebfe3fa` | 2026-03-27 | [bugfix] Fix runtime crash on release apk reason: method becomes private after incremental compile |
| `17230ec4e` | 2026-03-27 | [bugfix] Fix runtime crash on release apk crash: IncompatibleClassChangeError — invoke-direct to invoke-virtual |

### 关联文档

- `docs/task/release_incremental_access_flag_mismatch.md` §6-§10
- `docs/ai_knowledge/09_plugin_runtime_debug.md` §4.6, §4.7

---

## Phase 9：ExternalSyntheticLambda 编号漂移与接口/父类优先映射

### 背景

方案 E' 解决 access flag 问题后，出现 `AbstractMethodError`。D8 增量编译时，lambda 表达式被拆分为 `ExternalSyntheticLambdaN` 类，编号 `N` 按声明顺序分配。当源码新增/删除/重排 lambda 时，编号会整体漂移，导致增量编译的 Lambda13 与 APK 全量构建时的 Lambda13 语义不同。

### 根因链路

```
D8 lambda 编号漂移 → mapping.txt 映射到错误的混淆名 → 方法名不匹配接口要求 → AbstractMethodError
```

`DexObfuscator.mapMethod()` 的查找键基于 `ownerDot.methodName(params)`，当漂移后的类在 `methodNameMap` 中无对应条目时，方法名保留原名（如 `run`），而接口要求混淆后的方法名（如 `a`）。

### 方案演进

1. **方案 G（跳过 lambda 映射）**：不可行，lambda 类与接口/宿主类存在双向引用，必须参与混淆。
2. **方案 H（回退 Gradle）**：体验差，lambda 变化频繁。
3. **方案 I（接口签名匹配）**：歧义问题（同宿主多 lambda 实现同接口）。
4. **方案 J（lambda 专用接口推导）**：只解决 lambda 问题。
5. **方案 L（通用接口/父类优先 — 最终方案）**：对所有类的 `visitMethod()` 统一采用"接口/父类优先"策略：
   - 优先从接口和父类的 mapping 条目推导方法名
   - 未命中时回退到类自身的 mapping 条目
   - 都未命中时保留原名
   - 当类自身在 mapping 中时，接口/父类优先的结果与类自身查找结果一致（R8 保证继承链方法名一致性），行为不变
   - 实现仅改 `DexObfuscator.kt`，新增 `mapMethodForCurrentClass()` / `mapMethodNameFromHierarchy()`

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `d9b42ac60` / `aee45477d` | 2026-03-29 | [bugfix] Fix runtime crash on release apk crash: AbstractMethodError — ExternalSyntheticLambdaX class name changed and remapping skipped; solution: priority check interface mapping |

### 关联文档

- `docs/task/release_incremental_access_flag_mismatch.md` §11, §12
- `docs/ai_knowledge/09_plugin_runtime_debug.md` §4.8

---

## Phase 10：DexObfuscator R8 synthesized 方法映射修复

### 背景

方案 L 解决了类不在 mapping 中的情况，但仍存在 R8 synthesized 方法条目导致的映射失败。这类问题出现在类自身在 mapping 中，但特定方法的映射键构建不匹配的场景。

### 问题 10.1：Kotlin stdlib facade 类的 qualified 方法名

R8 为 facade 类（如 `CollectionsKt`）生成的 synthesized 方法条目使用 qualified 原始方法名（如 `xxx.CollectionsKt.listOf`）。`DexObfuscator.init{}` 构建 `methodNameMap` key 时直接拼接类名和方法名，导致 key 为 `kotlin.collections.CollectionsKt.xxx.CollectionsKt.listOf(...)` 而查找时用 `kotlin.collections.CollectionsKt.listOf(...)`，不匹配。

**修复**：`init{}` 中使用 `method.originalName.substringAfterLast('.')` 提取简单方法名构建 key。

### 问题 10.2：synthesized 方法参数的"中间格式"

R8 synthesized 条目中参数类型可能使用中间格式（混淆包名 + 原始简单类名，如 `xxx.ClosedRange`），与 DEX 中的原始名（`kotlin.ranges.ClosedRange`）不匹配。

**修复**：`init{}` 中先构建 `intermediateToOriginal` 辅助映射，通过 `normalizeMethodParams()` 将参数类型规范化为原始名。

### 问题 10.3：synthesized 恒等映射覆盖正常映射

同一方法可能同时有正常条目（`d → a`，真正重命名）和 synthesized 条目（`d → d`，恒等映射）。若 synthesized 条目后处理，恒等映射覆盖了真正重命名。

**修复**：构建 `methodNameMap` 时，当 key 已有映射时，优先保留"真正重命名"的条目（`obfuscatedName != simpleMethodName`），不允许恒等映射覆盖。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `1d508f2df` | 2026-03-29 | [bugfix] Fix runtime crash on release apk crash: NoSuchMethodError |

### 关联文档

- `docs/ai_knowledge/09_plugin_runtime_debug.md` §4.9, §4.10, §4.11

---

## Phase 11：EffectedType 体系与 MINIFY_MEMBER_REMOVED 引入

### 背景

`DeployDataDatabaseSqLiteHelper.checkMaybeMinifiedRemoveClass` 检测到的被 R8 移除的类/成员标记为 `SOURCE`，但这些类不需要源码重编译，只需字节码级别补全（`_jugg_fix`）。同时 `merge()` 函数将 `SOURCE` 强制覆盖为 `INLINE_IMPL_CHANGE`，导致需要源码重编译的类被降级。

### 变更内容

1. `EffectedClassNode.EffectedType` 新增 `MINIFY_MEMBER_REMOVED` 枚举值。
2. `checkMaybeMinifiedRemoveClass` 的 Check 1（整类移除）和 Check 2（成员移除）使用 `MINIFY_MEMBER_REMOVED` 替代 `SOURCE`。
3. `merge()` 优先级修复：当已有节点为 `SOURCE` 时保留 `SOURCE`。
4. `CompileEffectAnalyzer.getMinifyInfo()` 过滤条件从 `== INLINE_IMPL_CHANGE` 改为 `!= SOURCE`，同时选中 `INLINE_IMPL_CHANGE` 和 `MINIFY_MEMBER_REMOVED`。
5. 新增扩展属性 `.minifyMemberRemoved` 用于类型过滤。

**附带修复 — Boot classpath 误判：**
- `java/lang/Object`、`android/view/View` 等 boot classpath 类被 Check 1 误标为 `MINIFY_MEMBER_REMOVED`。增加 `isBootClasspathClass` 过滤。
- `MinifyInfo` 新增 `effectiveInlineEffectedClasses` 属性，只保留有对应 `.class` 文件的条目。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `122e85146` | 2026-03-30 | [bugfix] Fix runtime crash on release apk crash: NoSuchMethodError — add MINIFY_MEMBER_REMOVED type to create _jugg_fix class instead of recompile which may not have source file if it's in .aar |

### 关联文档

- `docs/task/minify_effected_type_fix.md`
- `docs/ai_knowledge/03_deploy_data_generator.md` §5.4-§5.7

---

## Phase 12：getMinifyInfo 数据源修复（staging → compileFiles → 预混淆）

### 背景

`getMinifyInfo()` 需要解析 DEX 文件内的类名来查询 APK 数据库，确定哪些类/成员被 R8 移除。数据源选择经历了三次迭代。

### 迭代 12.1：staging files 的时机问题

原始实现从 `stateTracker.getStagingFiles()` 读取数据。staging 区在 `DexMinifyCompiler` 调用时可能不包含当前编译轮次的 dex 文件，导致检测不到 minify effect。

**修复**：`getMinifyInfo()` 签名变更为接收 `compileFiles: List<CompileFile>` 参数，使用当前编译轮次的文件做分析。

### 迭代 12.2：compileFiles 的未混淆类名问题

`task.files` 是 `DexCompiler` 产出的未混淆 dex（原始类名如 `com/example/MyClass.dex`），而 APK 数据库存储的是混淆后的类名（如 `La/b;`）。原始类名查不到 → 误判为"completely removed class" → 产生大量 false positive。

**修复（方案 D — 预混淆）**：在 `DexMinifyCompiler.process()` 中，调用 `getMinifyInfo()` 前先对 `task.files` 做纯混淆（`obfuscator.obfuscate(bytes)`，不含 inline redirect），用混淆后的 bytes 组装临时 `CompileFile` 传给 `getMinifyInfo()`。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `fde9e8b99` | 2026-03-30 | [bugfix] Fix runtime crash on release apk — getMinifyInfo using staging files, which makes first compile not effected; _jugg_fix classes didn't filter the non-exists class |

### 关联文档

- `docs/task/minify_effected_type_fix.md`（修复 2, 修复 3）
- `docs/task/minify_getminifyinfo_obfuscated_class_fix.md`

---

## Phase 13：_jugg_fix 内部类引用混淆

### 背景

`DexMinifyCompiler.generateJuggFixClasses()` 生成的 `_jugg_fix` DEX 直接 copy 到输出目录，未经过 `DexObfuscator` 处理。`_jugg_fix` 类内部引用的匿名内部类（如 `LogUtil$1`）、字段类型、方法参数类型等保持原始名，而 APK 中这些类已被 R8 混淆。

**Crash**：`NoClassDefFoundError: Failed resolution of: Lcom/tencent/component/utils/LogUtil$1;`

### 修复

在 `generateJuggFixClasses()` 中，将 `_jugg_fix` DEX 的直接 copy 改为先通过 `obfuscator.obfuscate()` 处理。`LogUtil_jugg_fix` 类名不在 `classNameMap` 中，因此 `obfuscate()` 不会修改类名本身，只会映射其内部引用的其他类名。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `4b1b0e6db` | 2026-03-30 | [bugfix] Fix runtime crash on release apk _jugg_fix classes didn't obfuscate |

### 关联文档

- `docs/task/jugg_fix_inner_class_not_obfuscated.md`

---

## Phase 14：_jugg_fix 完整混淆链路（obfuscate-then-rename）

### 背景

Phase 13 解决了内部类引用问题，但 `_jugg_fix` 类自身的方法名/字段名未被混淆。`obfuscate()` 中 `mapMethodForCurrentClass()` 用当前类名（`LogUtil_jugg_fix`）查找 `methodNameMap`，无匹配，方法名保持原始值。增量 DEX 调用方用原始类名查找映射得到混淆名（如 `a`），但 `_jugg_fix` 中方法名仍为原始名（如 `d`），导致 `NoSuchMethodError`。

### 修复

**采用方案 A（obfuscate-then-rename）：**

生成流程从"rename → D8 → copy"改为"D8 → obfuscate → renameDexClassDeclaration"：

```
原始 .class → D8 → obfuscate() → renameDexClassDeclaration()
```

1. **D8**：保持原始名转 DEX。
2. **obfuscate()**：类名是原始名，能正确匹配 mapping，方法名/字段名/内部引用全部正确混淆。
3. **renameDexClassDeclaration()**：仅重命名类声明（类名、方法声明 owner、字段声明 owner），不修改代码体内的方法调用和字段引用 owner。

`_jugg_fix` 成为桥接类：声明名带后缀（如 `La/b/c_jugg_fix;`），内部调用仍指向原始混淆类（如 `La/b/c;`）。`redirectClassMap` 的 redirect 目标改为"混淆后类名 + 后缀"。

**附带修复 — 输出路径（§6.4）：**
D8 原始文件名（如 `LogUtil.dex`）与 obfuscate + rename 后的 DEX 内容不匹配。输出文件名改为从实际混淆后的类名推算（如 `a/b/c_jugg_fix.dex`）。

**附带修复 — field/clinit 剥离（§6.5）：**
`_jugg_fix` 桥接类的 `<clinit>` 会写入原始类的 final static field，触发 `IllegalAccessError`。`renameDexClassDeclaration()` 修改为剥离所有 field 声明（`visitField` 返回 null）和 `<clinit>` 方法（`visitMethod` 对 `<clinit>` 返回 null）。

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `dabf34257` | 2026-04-01 | [bugfix] Fix runtime crash on release apk _jugg_fix classes `<cinit>` failed |

### 关联文档

- `docs/task/jugg_fix_full_obfuscation_analysis.md`

---

## Phase 15：usage.txt 接入与 _jugg_fix compatibility stub

### 背景

`_jugg_fix` 基于原始 `.class` 文件生成，保留了源码中的完整方法图。但 release APK 经过 `-assumenosideeffects + shrink` 后可能裁掉整条调用链（如 `LogUtil.d/v` 日志方法）。`_jugg_fix` 仍会调用 APK 中已不存在的方法，运行时触发 `NoSuchMethodError`。

### 变更内容

1. **新增 `R8UsageReader`**：解析 R8 产出的 `usage.txt`，提供整类删除查询（`isClassRemoved`）和方法删除查询（`isMethodRemoved`）。支持 `fromFile()` / `fromPath()` / `fromString()` 入口。
2. **`ModuleBuildPathInfo` 新增 `usageFile`**：路径为 `build/outputs/mapping/{variant}/usage.txt`，加入 `allBuildPathRelative` 随构建产物同步。
3. **`ICompiler` 新增 `usageFile` getter**：`isMinified` 仍仅由 `mapping.txt` 判定，`usage.txt` 为增强输入。
4. **`DexMinifyCompiler.initIfNeeded()` 扩展**：与 `mapping.txt` 同阶段按需加载 `usageReader`，文件缺失时退化为不裁剪。
5. **`generateJuggFixClasses()` 流程扩展**：在 D8 之前插入 ASM 级别的 compatibility stub 重写。对 `usage.txt` 标记为已删除的方法，保留方法声明但方法体改为默认返回/空实现。未删除的方法保持原有桥接逻辑。

最终 `_jugg_fix` 生成流程：
```
原始 .class
  → rewriteDeletedMethodsAsCompatibilityStubs(usage)
  → D8
  → obfuscate(mapping)
  → renameDexClassDeclaration(..._jugg_fix)
```

### 关联 commits

| commit | 日期 | 说明 |
|--------|------|------|
| `986428161` / `abd167e50` | 2026-04-01 | [bugfix] Fix runtime crash on release apk _jugg_fix classes invokes deleted(not inlined) methods by proguard |

### 关联文档

- `docs/task/jugg_fix_usage_txt_deleted_method_plan.md`
- `docs/ai_knowledge/02_compile_manifest_obfuscation.md` §4（`_jugg_fix` 生成链路描述）

---

## 附录 A：当前 release 混淆相关类一览

| 类 | 文件 | 职责 |
|----|------|------|
| `R8MappingReader` | `compiler/obfuscation/R8MappingReader.kt` | `mapping.txt` 解析与查询 |
| `R8UsageReader` | `compiler/obfuscation/R8UsageReader.kt` | `usage.txt` 解析与查询（整类删除、方法删除、字段删除） |
| `ClassObfuscator` | `compiler/obfuscation/ClassObfuscator.kt` | class 级别重映射（ASM ClassRemapper） |
| `DexObfuscator` | `compiler/obfuscation/DexObfuscator.kt` | dex 级别重映射（dex2jar visitor）+ access flag 宽化 + invoke 指令同步 + 接口/父类优先方法名映射 |
| `ClassMinifyCompiler` | `compiler/obfuscation/ClassMinifyCompiler.kt` | class 级别映射一致性处理 |
| `DexMinifyCompiler` | `compiler/obfuscation/DexMinifyCompiler.kt` | dex 级别映射一致性处理 + `_jugg_fix` 生成 + usage.txt compatibility stub |
| `MinifyInfo` | `compiler/obfuscation/MinifyInfo.kt` | inline 影响信息 + 受影响类文件集合 |
| `InlineMethodDetector` | `deploy/data/InlineMethodDetector.kt` | R8 inline 链分析 |
| `EffectedClassNode` | `deploy/data/EffectedClassNode.kt` | 受影响类模型（SOURCE / INLINE_IMPL_CHANGE / MINIFY_MEMBER_REMOVED） |
| `CompileEffectAnalyzer` | `deploy/core/CompileEffectAnalyzer.kt` | 编译影响分析（含 minify 过滤） |
| `AabResGuardHandler` | `compiler/overlay/AabResGuardHandler.kt` | AabResGuard 资源混淆处理 |
| `AabResGuardMappingParser` | `compiler/overlay/AabResGuardMappingParser.kt` | AabResGuard mapping 解析 |
| `AabResGuardResourceProcessor` | `compiler/overlay/AabResGuardResourceProcessor.kt` | XML 资源引用替换 |

## 附录 B：完整 commit 时间线

| 日期 | commit | 说明 |
|------|--------|------|
| — | `481087708` | [WIP] supports read mapping.txt |
| — | `4352cca78` | add testParseMapping |
| — | `b99811b2a` | R8MappingReader supports method invocation |
| — | `6e0819567` | [refactor] use ClassObfuscator instead of R8MappingReader directly |
| — | `57b59450e` | [WIP] supports obfuscator for class |
| — | `efc008877` | supports detect inlined methods / removed classes |
| — | `7e09e4906` | supports find source file in classpath |
| — | `88306f54d` | supports redex inlined methods / removed classes |
| — | `4e9c207a5` | keep origin application and app component |
| — | `b048976b6` | fix super class not obfuscate |
| — | `80720685f` | fix obfuscated class can not redex |
| — | `6d08a6dd3` | supports find obfuscated source file in classpath |
| — | `667c3d0a3` | auto switch to embed mode for release apk |
| — | `654a55363` | refactor logic of InlineMethodDetector |
| — | `b29e70cca` | supports minify compilation |
| — | `db367cf57` | add minify test |
| — | `e37959360` | replace ClassObfuscator by DexObfuscator, minify after dex |
| — | `ab9d6f63e` | [WIP] supports AabResGuard |
| — | `7d6c3edab` | AabResGuardTest test pass |
| — | `9b00c7a31` | [bugfix] fix some issues |
| — | `ece0d71a4` | de-obfuscation by aapt2-inclink |
| — | `b30180759` | de-obfuscation by aapt2-inclink |
| 2026-02-26 | `2f80842dc` | R8 inline method change handling with _jugg_fix class generation |
| 2026-03-17 | `a7116435c` | [docs] Add comprehensive recompile mechanism to deploy_data_generator doc |
| 2026-03-26 | `713c5e919` | Fix: annotation not remapped |
| 2026-03-27 | `34953648e` | Fix: const call not remapped |
| 2026-03-27 | `0eebfe3fa` | Fix: method becomes private after incremental compile |
| 2026-03-27 | `17230ec4e` | Fix: IncompatibleClassChangeError (invoke-direct vs invoke-virtual) |
| 2026-03-29 | `d9b42ac60` | Fix: AbstractMethodError (ExternalSyntheticLambdaX remapping) |
| 2026-03-29 | `aee45477d` | Fix: AbstractMethodError (ExternalSyntheticLambdaX remapping) |
| 2026-03-29 | `1d508f2df` | Fix: NoSuchMethodError |
| 2026-03-30 | `122e85146` | Fix: add MINIFY_MEMBER_REMOVED type |
| 2026-03-30 | `fde9e8b99` | Fix: getMinifyInfo using staging files + _jugg_fix filter non-exists class |
| 2026-03-30 | `4b1b0e6db` | Fix: _jugg_fix classes didn't obfuscate |
| 2026-04-01 | `dabf34257` | Fix: _jugg_fix classes `<cinit>` failed |
| 2026-04-01 | `986428161` / `abd167e50` | Fix: _jugg_fix invokes deleted(not inlined) methods by proguard |
