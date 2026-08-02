# Release 增量编译后 IllegalAccessError / AbstractMethodError 分析方案

> 创建时间：2026-03-27
> 状态：进行中（方案 E' 已解决 IllegalAccessError/IncompatibleClassChangeError → 新发现 AbstractMethodError，根因为 D8 Lambda 编号漂移）

---

## 1. 问题描述

在 release APK 上对 `MainTabActivity.java` 做增量编译部署后，出现三种 crash：

### Crash 1: IllegalAccessError（最关键线索）
```
java.lang.IllegalAccessError: Method 'boolean com.tencent.wemusic.ui.main.activity.MainTabActivity.lambda$onResume$6$com-tencent-wemusic-ui-main-activity-MainTabActivity()' is inaccessible to class 'xxx.b9h'
(declaration of 'xxx.b9h' appears in /data/user/0/com.tencent.ibg.joox/code_cache/.jugg_classes_embed/com.tencent.wemusic.ui.main.activity.MainTabActivity$$ExternalSyntheticLambda8.dex)
```

### Crash 2 & 3: AbstractMethodError
```
java.lang.AbstractMethodError: abstract method "java.lang.Object xxx.lgq$b.a(xxx.lgq$c)"
java.lang.AbstractMethodError: abstract method "void xxx.c9e.a(xxx.swp)"
```

---

## 2. 根因分析

### 2.1 R8 全量构建 vs Jugg 增量编译的 access flag 差异

R8 在全量构建时会做以下优化：
1. **Lambda desugaring**：将 `MainTabActivity` 的 lambda 拆为 `ExternalSyntheticLambda8` 独立类
2. **Access flag 宽化**：将宿主类的 `private lambda$...()` 方法修改为 `package-private` 或 `public`，使合成类能访问
3. **混淆**：将 `ExternalSyntheticLambda8` 混淆为 `xxx.b9h`

Jugg 增量编译时：
1. **javac** 编译 `MainTabActivity.java`，lambda 方法仍为 `private`（javac 原始行为）
2. **D8** 将 `.class` 转为 `.dex`，不做 access flag 调整（D8 增量模式不生成 ExternalSyntheticLambda）
3. **DexObfuscator** 只做名称映射，**access flags 原样透传**
4. **部署到设备**：增量的 `MainTabActivity.dex`（lambda 方法 `private`）与 APK 中的 `xxx.b9h` 共存

**结果**：`xxx.b9h` 调用 `MainTabActivity.lambda$onResume$6$...()` → `IllegalAccessError`

### 2.2 代码级证据

#### DexObfuscator 不修改 access flags

```kotlin
// DexObfuscator.kt line 264
val classVisitor = super.visit(accessFlags, mappedClassName, ...)  // accessFlags 原样透传

// DexObfuscator.kt line 283
val fieldVisitor = super.visitField(accessFlags, mappedField, value)  // accessFlags 原样透传

// DexObfuscator.kt line 300
val methodVisitor = super.visitMethod(accessFlags, mappedMethod)  // accessFlags 原样透传
```

#### R8 mapping.txt 不包含 access flag 信息

`R8MappingReader` 的 `MethodMapping` 数据类仅有：
- `originalName` / `obfuscatedName` / `parameters` / `invocations`
- **没有** access flags 或 visibility 信息

#### 结构对比故意忽略 private 差异

```kotlin
// ClassStructure.kt line 95-101
fun isEffectedChanged(method: MethodNode): Boolean {
    val accessWithoutAbstract = access and ACC_ABSTRACT.inv() and ACC_PRIVATE.inv()  // mask 掉 PRIVATE
    val otherAccessWithoutAbstract = method.access and ACC_ABSTRACT.inv() and ACC_PRIVATE.inv()
    ...
}
```

### 2.3 AbstractMethodError 的可能原因

1. 增量编译的类实现了某些接口，但 R8 在全量构建时对接口做了优化（如默认方法 desugar、接口方法桥接）
2. 增量编译产物中的方法签名与 APK 中 R8 优化后的接口定义不匹配
3. R8 可能移除了某些看似未使用的接口方法实现，增量编译的类缺少这些方法

---

## 3. 影响范围

所有 release (R8 minified) APK 上的增量编译场景，只要涉及：
- Lambda 表达式（生成 `ExternalSyntheticLambda` 类）
- 接口默认方法（desugar 相关）
- R8 对 access flag 的优化

---

## 4. 可行方案

### 方案 A：从 APK DEX 中读取原始 access flags 并应用

**思路**：在 DexObfuscator 重混淆时，从 APK 中的原始 DEX 读取各方法的 access flags，将增量编译产物的 access flags 调整为与 APK 一致。

**优点**：
- 精确还原 R8 的 access flag 修改
- 不依赖 mapping.txt

**缺点**：
- 需要在重混淆阶段额外解析 APK DEX
- 增加编译时间
- 实现复杂度高

**实现路径**：
1. 在 `DexMinifyCompiler` 或 `DexObfuscator` 中增加 APK DEX 读取能力
2. 构建 `className + methodName + methodDesc → accessFlags` 映射
3. 在 `visitMethod()` 中用 APK DEX 的 access flags 替换 javac 生成的 access flags

### 方案 B：增量编译时将 lambda 方法无条件宽化为 package-private

**思路**：在 DexObfuscator 中检测 `lambda$` 开头的方法，将其 `private` 改为 `package-private`。

**优点**：
- 实现简单
- 不需要额外数据源

**缺点**：
- 只解决 lambda 相关的 IllegalAccessError
- 不解决 AbstractMethodError
- 可能引入新的安全/兼容性问题

### 方案 C：检测到 ExternalSyntheticLambda 相关类时触发 Gradle 回退

**思路**：在结构对比阶段检测到涉及 ExternalSyntheticLambda 的变更时，自动回退到 Gradle 全量构建。

**优点**：
- 安全可靠
- 实现简单

**缺点**：
- 用户体验差
- 回退频率可能较高

### 方案 D（⚠️ 已废弃 — 被方案 E' 取代）：从 APK 数据库中读取 access flags 并在重混淆时同步

**思路**：`DeployDataDatabase` 已经存储了 APK 的类结构信息（包括每个方法的 access flags）。在 DexObfuscator 中利用这些数据来修正 access flags。

**优点**：
- 数据源已存在（APK 解析后的数据库）
- 不需要额外 I/O
- 能处理所有 R8 access flag 修改（不仅限于 lambda）

**实现路径**：
1. 扩展 `DexMinifyCompiler` 或 `DexObfuscator`，传入 APK 中该类的方法 access flags 映射
2. 在 `visitMethod()` 中，根据混淆后的类名+方法名+方法签名查询 APK 中对应的 access flags
3. 如果 APK 中存在该方法，使用 APK 的 access flags 替换增量编译产物的 access flags

---

## 5. 关联代码

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `DexObfuscator.kt` | `super.visitMethod(accessFlags, ...)` | access flags 原样透传 |
| `DexMinifyCompiler.kt` | `obfuscateDexFile()` | 重混淆调度 |
| `ClassStructure.kt` | `isEffectedChanged()` | mask 掉 ACC_PRIVATE |
| `CompileEffectAnalyzer.kt` | `ExternalSyntheticLambda` filter | 只过滤跳过，无修复 |
| `ClassFileLookupHelper.kt` | `ExternalSyntheticLambda` filter | 只过滤跳过 |
| `DeployDataDatabase` | APK 类结构存储 | 可作为 access flags 数据源 |

---

## 6. R8 Access Flag 宽化行为调查结论

### 6.1 关键发现

1. **`-allowaccessmodification` 已启用**：在 `android_demo_project/app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-7.2.2` 第 17 行。
2. **R8 宽化行为（修正）**：启用 `-allowaccessmodification` 后，R8 的 `AccessModifier` pass 会宽化 `private`/`protected`/`package-private` 成员为 `public`，但 **并非无条件**——对 `private` 非 static 实例方法存在以下例外（详见 §6.3）。
3. **vtable 安全性**：R8 通过 minification（方法重命名）避免 vtable 冲突。Jugg 的 `DexObfuscator` 也做方法重命名，具备同等保护。
4. **DB 存储精确 access flags**：`DeployDataDatabaseSqLiteHelper.toMethodString()` 以 `"${it.access} ${it.name} ${it.desc}"` 格式存储，`toMethodList()` 以 `MethodNode(owner, parts[0].toInt(), parts[1], parts[2])` 读取。
5. **Jugg 增量链路无 access flag 修改**：`javac → D8 → DexObfuscator` 全程透传原始 access flags。

### 6.2 方案选择（已失效）

~~选择**方案 E（直接宽化 `private → public`）**作为最终实现方案。~~

~~理由：~~
- ~~与 R8 实际行为完全匹配~~
- ~~实现最简，无 DB 依赖~~
- ~~vtable 安全由 minification 保证~~
- ~~javac 已在编译阶段校验源码级可见性~~
- ~~DEX verifier 只检查 caller 权限，宽化 = 更宽松~~

> ⚠️ 方案 E 在实测中导致 `IncompatibleClassChangeError`（§8），已废弃。最终选择**方案 D**（§9）。

### 6.3 R8 AccessModifier 源码分析（补充）

> 源码位置：[r8.googlesource.com AccessModifier.java](https://r8.googlesource.com/r8/+/a03d9b630bcc316994b226a2b655cacb16e54df1/src/main/java/com/android/tools/r8/optimize/AccessModifier.java)

R8 `publicizeMethod()` 对 `private` 非 static 实例方法的处理流程：

```
private instance method
  ├─ 在接口中？ → 不宽化（宽化会变成 default method）
  ├─ 是 synthetic 方法？ → 不宽化（如 access$000 桥接方法）
  ├─ 命名冲突（methodPool 已见同签名）？ → 不宽化
  └─ 通过所有检查 → promoteToFinal() + doPublicize() + virtualizeMethods()
```

**三种不宽化 private 非 static 方法的情况**：

| 条件 | 说明 |
|------|------|
| 接口中的 private 方法 | 宽化会变成 default method，破坏接口契约 |
| synthetic 方法 | 编译器生成的合成方法（如 `access$000`、lambda 桥接方法等） |
| 命名冲突 | 方法签名在继承层级中已被其他方法占用（`wasSeen = true`），且无法通过重命名解决 |

**关键结论**：R8 对 `private` 非 static 实例方法的宽化**不是无条件的**。存在不宽化的边界情况，这意味着 APK 中可能存在仍为 `private`（direct method）的非 static 方法，其调用者使用 `invoke-direct`。

其他类型的宽化是安全的：
- `private static` → `public static`：static 方法始终在 direct section，不改变 dispatch 方式
- `protected` → `public`：已经是 virtual method，不改变分类
- `package-private` → `public`：同上

---

## 7. 方案 E：直接宽化实现（⚠️ 已废弃 — 导致 IncompatibleClassChangeError）

### 7.1 实现方案

在 `DexObfuscator.ObfuscationDexRemapper` 中添加 `widenAccessFlags()` 方法，将 `private`/`protected`/`package-private` 统一宽化为 `public`。在 `visit()`、`visitField()`、`visitMethod()` 三处调用。

### 7.2 代码变更

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscator.kt`

```kotlin
private fun widenAccessFlags(accessFlags: Int): Int {
    return (accessFlags and DexConstants.ACC_PRIVATE.inv() and DexConstants.ACC_PROTECTED.inv()) or
            DexConstants.ACC_PUBLIC
}
```

应用于三处：
- `visit()` → `super.visit(widenAccessFlags(accessFlags), ...)`
- `visitField()` → `super.visitField(widenAccessFlags(accessFlags), ...)`
- `visitMethod()` → `super.visitMethod(widenAccessFlags(accessFlags), ...)`

### 7.3 测试覆盖

**文件**：`main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscatorTest.kt`

新增 8 个测试用例：
| 测试 | 验证点 |
|------|--------|
| `testPrivateMethodWidenedToPublic` | private lambda 方法 → public |
| `testPrivateFieldWidenedToPublic` | private 字段 → public |
| `testProtectedMethodWidenedToPublic` | protected 方法 → public |
| `testPackagePrivateMethodWidenedToPublic` | package-private 方法 → public |
| `testPublicMethodRemainsPublic` | public 方法不变 |
| `testPrivateStaticMethodWidenedPreservesOtherFlags` | static flag 保留 |
| `testPrivateClassWidenedToPublic` | private 内部类 → public |
| `testUnmappedMethodInMappedClassStillWidened` | 未映射方法也宽化 |

### 7.4 边界情况

- **`-keep` 保护的 private 方法**：极少出现（`-keep` 通常保护 public 方法）。即使出现，宽化也不会导致运行时错误，仅比 R8 更宽松。
- **新增方法（不在 mapping 中）**：仍会宽化，与 R8 行为一致。

---

## 8. 关联代码

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `DexObfuscator.kt` | `widenAccessFlags()` + `visit/visitField/visitMethod` | access flags 宽化 |
| `DexMinifyCompiler.kt` | `obfuscateDexFile()` | 重混淆调度 |
| `ClassStructure.kt` | `isEffectedChanged()` | mask 掉 ACC_PRIVATE |
| `CompileEffectAnalyzer.kt` | `ExternalSyntheticLambda` filter | 只过滤跳过，无修复 |
| `ClassFileLookupHelper.kt` | `ExternalSyntheticLambda` filter | 只过滤跳过 |
| `DeployDataDatabase` | APK 类结构存储 | 可作为 access flags 数据源（方案 D 备选） |

---

## 8. 方案 E 失败：IncompatibleClassChangeError

### 8.1 崩溃现象

```
java.lang.IncompatibleClassChangeError: The method 'void com.tencent.wemusic.ui.main.activity.MainTabActivity.J1()'
was expected to be of type direct but instead was found to be of type virtual
(declaration of 'com.tencent.wemusic.ui.main.activity.MainTabActivity' appears in
/data/user/0/com.tencent.ibg.joox/code_cache/.jugg_classes_embed/com.tencent.wemusic.ui.main.activity.MainTabActivity.dex)
at com.tencent.wemusic.ui.main.activity.MainTabActivity.doOnCreate(MainTabActivity.java:612)
```

### 8.2 根因

方案 E 的 `widenAccessFlags()` 无条件将 `private` 非 static 方法宽化为 `public`，导致该方法从 DEX 的 **direct methods section** 移到 **virtual methods section**。

**DEX 方法分类规则**：

| 方法类型 | DEX 分类 | 调用指令 |
|---------|----------|---------|
| `private` (非 static) | direct method | `invoke-direct` |
| `private static` / `static` | direct method | `invoke-static` |
| `public`/`protected`/`package-private` (非 static) | virtual method | `invoke-virtual` |

**崩溃链路**：

1. R8 处理了 `MainTabActivity.J1()` 但**选择不宽化**（属于 §6.3 描述的例外情况——可能是 synthetic 方法或命名冲突）
2. APK 中 `J1()` 仍为 `private`（direct method），同类的 `doOnCreate()` 使用 `invoke-direct` 调用 `J1()`
3. Jugg 增量编译 + 方案 E 将 `J1()` 宽化为 `public`（virtual method）
4. 增量 DEX 部署后，APK 中的 `doOnCreate()` 仍使用 `invoke-direct` 调用 `J1()`
5. ART 检测到 `invoke-direct` 的目标实际上是 virtual method → 抛出 `IncompatibleClassChangeError`

### 8.3 方案 E 的场景分析

| 场景 | R8 APK 中的状态 | 方案 E 行为 | 结果 |
|------|-----------------|-------------|------|
| private static 方法 | public static | 宽化为 public static | ✅ safe（static 始终在 direct section） |
| private 非 static（R8 已宽化） | public (virtual)，caller 用 invoke-virtual | 宽化为 public (virtual) | ✅ 匹配 |
| **private 非 static（R8 未宽化）** | **private (direct)，caller 用 invoke-direct** | **宽化为 public (virtual)** | **❌ IncompatibleClassChangeError** |
| protected / package-private | public | 宽化为 public | ✅ safe |

### 8.4 为何"保守不宽化 private 非 static"（方案 F）也不安全

反过来，如果对所有 `private` 非 static 方法都不宽化，则会出现另一个问题：

| 场景 | R8 APK 中的状态 | 方案 F 行为 | 结果 |
|------|-----------------|-------------|------|
| private 非 static（R8 已宽化） | public (virtual)，caller 用 invoke-virtual | **保留 private (direct)** | **❌ caller 找不到 virtual method** |
| private 非 static（R8 未宽化） | private (direct)，caller 用 invoke-direct | 保留 private (direct) | ✅ 匹配 |

**结论**：方案 E 和方案 F 都有风险，方向相反。只有精确对齐 APK 实际 access flags 才是安全的。

---

## 9. 方案 D：从 APK 数据库查询精确 access flags（确认采用）

### 9.1 核心思路

不猜测 R8 的宽化行为，而是从 `DeployDataDatabase.getClassNodes()` 查询 APK 中该类的真实 access flags，在 `DexObfuscator` 重混淆时使用这些 flags 替换 javac/D8 产出的原始 flags。

### 9.2 数据流设计

```
DexMinifyCompiler
  ├─ 通过 context 获取 DeployDataDatabase
  ├─ 调用 getClassNodes(classNames) 获取 APK 中的 ClassNode（含方法 access flags）
  ├─ 构建混淆后类名+方法名+方法签名 → access flags 映射
  └─ 传入 DexObfuscator，在 visit()/visitField()/visitMethod() 中使用 APK 的 access flags
```

### 9.3 优势

- **精确**：直接使用 APK 中的真实 access flags，不依赖对 R8 行为的假设
- **安全**：不会改变方法的 direct/virtual 分类（与 APK 完全一致）
- **数据源已存在**：`DeployDataDatabase` 已在 APK 解析阶段存储了完整的类结构数据
- **统一解决所有问题**：IllegalAccessError、AbstractMethodError、IncompatibleClassChangeError

### 9.4 实现完成

已按 TDD 模式完成以下变更：

1. **`DexObfuscator.kt`**：
   - 新增 `obfuscate(dexBytes, apkClassNodes)` 重载，接受 `Map<String, ClassNode>` APK 数据
   - 更新 `obfuscateWithInlineRedirect()` 增加 `apkClassNodes` 参数
   - `ObfuscationDexRemapper` 增加 `apkClassNodes` 参数
   - 替换 `widenAccessFlags()` 为三个 `alignAccessFlags` 方法：
     - `alignClassAccessFlags()`: 从 APK ClassNode 对齐类 access flags
     - `alignMethodAccessFlags()`: 通过 name+desc 匹配方法，对齐 access flags
     - `alignFieldAccessFlags()`: 通过 name+type 匹配字段，对齐 access flags
   - 无 APK 数据时，access flags 原样透传（不做任何修改）

2. **`ICompileContext`**（`ICompiler.kt`）：
   - 新增 `getApkClassNodes(classNames)` 方法，默认返回 null（向下兼容）

3. **`BaseCompileContext.kt`**：
   - 实现 `getApkClassNodes()`，委托给 `deployFileManager.getApkClassNodes()`

4. **`DeployFileManager.kt`**：
   - 新增 `getApkClassNodes()`，委托给 `deployDataGenerator.deployDataDatabase.getClassNodes()`

5. **`DexMinifyCompiler.kt`**：
   - `process()` 中新增 `queryApkClassNodes()` 步骤：解析 DEX 文件提取类名，通过映射获取混淆名，批量查询 DB
   - `obfuscateDexFile()` 新增 `apkClassNodes` 参数，传给 obfuscator

6. **测试覆盖**（`DexObfuscatorTest.kt`）：
   - `testMethodAccessAlignedToApkPublic`: APK 为 public 时，private → public
   - `testMethodAccessAlignedToApkPrivate`: APK 为 private 时，保持 private（防止 IncompatibleClassChangeError）
   - `testFieldAccessAlignedToApk`: 字段 access flags 对齐
   - `testClassAccessAlignedToApk`: 类 access flags 对齐
   - `testNewMethodNotInApkPreservesOriginalAccess`: 新增方法保留原始 access
   - `testClassNotInApkDataPreservesOriginalAccess`: 类不在 APK 数据中保留原始 access
   - `testNoApkDataPassesThroughAccessFlags`: 无 APK 数据时透传 access
   - `testStaticFlagPreservedDuringAlignment`: static 等非可见性 flag 保留
   - `testMethodWithParamsAccessAligned`: 参数方法的 name+desc 精确匹配

### 9.5 关联代码

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `DexObfuscator.kt` | `alignClassAccessFlags()`/`alignMethodAccessFlags()`/`alignFieldAccessFlags()` | APK access flags 精确对齐 |
| `DexMinifyCompiler.kt` | `queryApkClassNodes()` + `obfuscateDexFile()` | 从 DB 获取 APK 数据并传入 |
| `ICompiler.kt` | `ICompileContext.getApkClassNodes()` | 编译上下文新接口 |
| `BaseCompileContext.kt` | `getApkClassNodes()` | 委托给 DeployFileManager |
| `DeployFileManager.kt` | `getApkClassNodes()` | 委托给 DeployDataDatabase |
| `DeployDataDatabase.kt` | `getClassNodes(classNames)` | APK access flags 数据源 |
| `ClassStructure.kt` | `ClassNode`/`MethodNode`/`FieldNode` | 数据结构，含 `access: Int` |

### 9.6 方案 D 废弃原因

方案 D 正确但过于复杂——依赖 DB 查询 APK 精确 access flags，增加了 `ICompileContext`/`BaseCompileContext`/`DeployFileManager` 三层传参。

经分析发现更简洁的方案 E' 可以替代（详见 §10）。

---

## 10. 方案 E'：无条件宽化 + invoke-direct → invoke-virtual（确认采用）

### 10.1 核心思路

在 `DexObfuscator` 中：
1. **无条件宽化**所有 `private`/`protected`/`package-private` 成员为 `public`（与方案 E 相同）
2. **同时修改调用指令**：对本类内的 `invoke-direct` 指令，当目标方法不是 `<init>` 且不是 `static` 时，改为 `invoke-virtual`

### 10.2 为何安全

方案 E 失败的根因是：宽化将 private 非 static 方法从 direct section 移到 virtual section，但 **APK 中的旧调用者仍使用 `invoke-direct`**。

方案 E' 解决了增量 DEX 文件内部的自洽性。而对于外部调用者：
- `private` 方法**天然不可能被外部类直接调用**（Java/Kotlin 语言保证）
- 外部类只能通过 `access$xxx` 桥接方法间接调用 private 方法
- Lambda desugaring 场景中，R8 一定已经宽化了 host 方法（否则 `ExternalSyntheticLambda` 无法调用）
- 因此不存在"APK 中有外部 caller 用 `invoke-direct` 直接调用某 private 方法"的情况

### 10.3 方案对比

| | 方案 D（DB 对齐） | 方案 E'（全宽化 + 改指令） |
|---|---|---|
| DB 依赖 | 需要 | 不需要 |
| 实现复杂度 | 高（查询、映射、三层传参） | 中（宽化 + visitMethodStmt 改指令） |
| 正确性 | ✅ 精确对齐 | ✅ 逻辑自洽 |
| 对 static 方法 | 安全 | 安全（static 的 invoke-static 不受影响） |
| 对 protected/pkg-private | 安全 | 安全（已是 virtual，不改指令） |
| 对 private 非 static | 安全（对齐到 APK 真实值）| 安全（宽化 + 改指令同步） |
| 新增方法（不在 APK 中） | 保持原始 access | 全部宽化（更宽松但不 crash） |

### 10.4 实现要点

1. **`widenAccessFlags()`**：清除 `ACC_PRIVATE`/`ACC_PROTECTED`，设置 `ACC_PUBLIC`。应用于 `visit()`、`visitField()`、`visitMethod()`。

2. **`visitMethodStmt()` 中修改 invoke-direct**：
   - 条件：`op == INVOKE_DIRECT` 且方法 owner 是当前类 且方法名不是 `<init>`
   - 行为：改为 `INVOKE_VIRTUAL`
   - 注意：`<init>` 构造方法**绝不能**改为 `invoke-virtual`

3. **不修改 `invoke-static`**：`private static` 宽化为 `public static` 后仍在 direct section，`invoke-static` 不受影响。

### 10.5 代码变更

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscator.kt`

- 恢复 `widenAccessFlags()` 方法
- 删除 `alignClassAccessFlags()`/`alignMethodAccessFlags()`/`alignFieldAccessFlags()` 方法
- 删除 `apkClassNodes` 参数
- 在 `visitMethodStmt(Op, int[], Method)` 中：当 `op == INVOKE_DIRECT && method.owner == currentClassName && method.name != "<init>"` 时，改 op 为 `INVOKE_VIRTUAL`

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt`

- 删除 `queryApkClassNodes()` 方法
- 删除 `apkClassNodes` 参数传递

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`

- 删除 `getApkClassNodes()` 方法

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`

- 删除 `getApkClassNodes()` override

**文件**：`main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFileManager.kt`

- 删除 `getApkClassNodes()` 方法

### 10.6 关联代码

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `DexObfuscator.kt` | `widenAccessFlags()` + `visitMethodStmt()` invoke-direct → invoke-virtual | access flags 宽化 + 调用指令同步修改 |
| `DexMinifyCompiler.kt` | `obfuscateDexFile()` | 重混淆调度（不再传 APK 数据） |

---

## 11. 新问题：D8 ExternalSyntheticLambda 编号漂移导致 AbstractMethodError

### 11.1 崩溃现象

方案 E' 实施后，`IllegalAccessError` 和 `IncompatibleClassChangeError` 已解决，但出现两个新的 `AbstractMethodError`：

**Crash A**：
```
java.lang.AbstractMethodError: abstract method "java.lang.Object xxx.lgq$b.a(xxx.lgq$c)"
  at xxx.lgq.b(SourceFile:3)
  at xxx.lgq.a(SourceFile:1)
  at com.tencent.wemusic.ui.main.activity.MainTabActivity.doOnResume(MainTabActivity.java:1005)
```

**Crash B**：
```
java.lang.AbstractMethodError: abstract method "void xxx.c9e.a(xxx.swp)"
  at xxx.t2a.m(SourceFile:1)
  at xxx.t2a.b(SourceFile:1)
  at com.tencent.wemusic.ui.main.activity.MainTabActivity.initView(MainTabActivity.java:631)
```

### 11.2 根因分析：D8 Lambda 编号漂移

#### 11.2.1 D8 ExternalSyntheticLambda 编号机制

D8 在 desugaring 阶段将 lambda 表达式拆分为独立的 `ExternalSyntheticLambdaN` 类。编号 `N` 由 D8 内部按**声明顺序**分配——这意味着：

- 当源码新增/删除/重排 lambda 时，编号会整体漂移
- 例如：原本 `Lambda7` 对应接口 `lgq$b`，源码改动后 `Lambda7` 可能变成对应接口 `c9e`，而 `lgq$b` 移到了 `Lambda13`

#### 11.2.2 漂移 + 过时 mapping.txt 的致命组合

Jugg 增量编译链路：

```
javac 编译 MainTabActivity.java
  → D8 产出新的 ExternalSyntheticLambdaN 类（编号已漂移）
  → DexObfuscator 用 APK 的 mapping.txt 做名称映射
    → mapping.txt 中 Lambda13 → s8h（基于 APK 全量构建时的编号）
    → 但新的 Lambda13 语义上已不是同一个类
    → 结果：新 Lambda13 被错误地映射为 s8h
```

#### 11.2.3 具体漂移证据

通过 dexdump 分析 staging DEX 文件：

| 增量 DEX 文件 | 映射后类名 | 实现接口 | 方法签名 |
|---|---|---|---|
| `MainTabActivity$$ExternalSyntheticLambda13.dex` | `Lxxx/s8h;` | `Lxxx/lgq$b;` | `run(Lxxx/lgq$c;)Ljava/lang/Object;` |
| `MainTabActivity$$ExternalSyntheticLambda10.dex` | `Lxxx/p8h;` | `Lxxx/c9e;` | `accept(Lxxx/swp;)V` |
| `MainTabActivity$$ExternalSyntheticLambda7.dex`  | `Lxxx/a9h;` | `Lxxx/lgq$b;` | `run(Lxxx/lgq$c;)Ljava/lang/Object;` |

**问题关键**：APK 中 `s8h` 原本实现的是某个接口 X（编号漂移前的 Lambda13 语义），但增量编译后 `s8h` 变成了实现 `lgq$b` 接口的类。

接口 `lgq$b` 的抽象方法签名为 `a(lgq$c)Object`，但增量 DEX 中 `s8h` 的方法名为 `run`（mapping.txt 中无对应映射条目，因为 `s8h.run()` 这个组合在旧 mapping 中不存在）。

#### 11.2.4 mapMethod() 查找失败的机制

`DexObfuscator.mapMethod()` 的查找键为 `ownerDot.methodName(params)`：

```kotlin
// DexObfuscator.kt line 575-578
val ownerDot = method.owner.asmSigFormat.replace('/', '.')
val params = protoToParams(method.proto)
val key = "$ownerDot.${method.name}($params)"
val nameMapped = methodNameMap[key] ?: method.name  // 找不到就保留原名
```

当漂移发生时：
- 新 Lambda13 实现 `lgq$b` 接口，functional 方法名为 `run`
- mapping.txt 中 `s8h`（旧 Lambda13）的条目对应的方法名不是 `run`
- `methodNameMap` 中无 `xxx.s8h.run(...)` 的条目
- `nameMapped` 回退为原名 `run`，而非接口要求的 `a`

**最终**：类 `s8h` 声明实现 `lgq$b` 接口，接口要求方法 `a(lgq$c)Object`，但类中方法为 `run(lgq$c)Object` → ART 在 vtable 查找时找不到 `a` 方法 → `AbstractMethodError`。

#### 11.2.5 编译日志佐证

编译日志中 `ClassNodeComparator` 已检测到结构变化：

```
deletedInterfaces: [Lxxx/some_old_interface;]
addedInterfaces: [Lxxx/lgq$b;]
isSameStructure: false
isCanHotReload: false
```

这表明部署系统**已知道**这些 lambda 类的结构发生了变化（接口改变），但仍然将它们部署到了设备上——因为 Hot Fix 路径会重启 App 来加载新的 DEX，而 DEX 内容本身就是错的。

### 11.3 问题本质总结

这不是 access flag 问题（方案 E' 已解决），而是**名称映射错误**：

```
编号漂移 → 错误的 mapping.txt 映射 → 方法名不匹配接口要求 → AbstractMethodError
```

等式：`(D8 lambda 编号不稳定) × (mapping.txt 基于旧编号) = 语义错位`

### 11.4 影响范围

所有满足以下条件的场景：
1. Release (R8 minified) APK 上进行增量编译
2. 被修改的源文件包含多个 lambda 表达式
3. 增量修改导致 lambda 数量或顺序变化（新增/删除/重排）
4. D8 重新 desugaring 后 `ExternalSyntheticLambdaN` 编号发生漂移

### 11.5 与方案 E' 的关系

方案 E' 解决的是 **access flag 不一致** 问题（IllegalAccessError / IncompatibleClassChangeError），不涉及名称映射。Lambda 编号漂移是一个**正交的、独立的问题**，需要单独的修复方案。

### 11.6 关联代码

| 文件 | 关键代码 | 说明 |
|------|---------|------|
| `DexObfuscator.kt` | `mapMethod()` line 559-587 | 方法名映射，漂移时查找失败导致方法名未被正确混淆 |
| `DexObfuscator.kt` | `mapType()` line 492-534 | 类型映射，漂移的 lambda 类名仍被映射到旧的混淆名 |
| `DexMinifyCompiler.kt` | `obfuscateDexFile()` | 重混淆调度，未检测 lambda 编号漂移 |
| `DeployDataGenerator.kt` | `ClassNodeComparator.compare()` | 已检测 `isSameStructure: false`，但 DEX 内容本身已经错误 |
| `ClassNodeComparator.kt` | `isSameStructure` line 124-131 | 严格结构对比，能检测到接口变化 |
| `CompileEffectAnalyzer.kt` | `ExternalSyntheticLambda` filter line 189 | 仅跳过 missing source file 查找，未处理漂移 |
| `ClassFileLookupHelper.kt` | `ExternalSyntheticLambda` filter line 36 | 仅跳过 class file 查找，未处理漂移 |

---

## 12. Lambda 编号漂移修复方案设计

### 12.1 候选方案

#### 方案 G：跳过 ExternalSyntheticLambda 的名称映射

**思路**：在 `DexObfuscator` 中检测 `ExternalSyntheticLambda` 类，不对其做名称映射（类名、方法名均保留原始名称），部署时以原始未混淆名称存在。

**优点**：实现简单

**致命缺陷**：
- Lambda 类实现的接口（如 `lgq$b`）已被混淆，未混淆的 lambda 类无法与混淆后的接口名称匹配
- Lambda 类调用宿主类（如 `MainTabActivity`）的方法，宿主类方法已被混淆，调用链断裂
- 本质上不可行：lambda 类与其上下文（宿主类、接口）之间存在双向引用，必须参与混淆

**结论**：❌ 不可行

#### 方案 H：检测漂移并回退到 Gradle 全量构建

**思路**：在增量编译阶段检测到 `ExternalSyntheticLambda` 的接口/方法签名与 APK 数据库中记录的不一致时，触发 Gradle 回退。

**优点**：安全可靠

**缺点**：
- 回退频率较高（lambda 变化非常常见）
- 用户体验差，失去增量编译优势
- 检测逻辑复杂（需要在 DexMinifyCompiler 阶段对比接口签名）

**结论**：⚠️ 可行但体验差，作为保底方案

#### 方案 I：基于接口签名重新匹配 mapping（确认采用）

**思路**：当检测到 `ExternalSyntheticLambda` 类的编号可能漂移时，不依赖类名匹配 mapping，而是**根据 lambda 类实现的接口 + 宿主类名，在 APK 数据库中查找语义上对应的旧 lambda 类**，使用正确的映射条目。

**核心洞察**：

D8 生成的 `ExternalSyntheticLambda` 类有确定性特征：
1. 类名格式：`HostClass$$ExternalSyntheticLambdaN`
2. 实现的接口：由 lambda 的 SAM (Single Abstract Method) 目标决定
3. 宿主类：从类名前缀可直接提取
4. 每个 lambda 类只有一个方法（functional interface 的方法实现）

因此，可以用 **宿主类名 + 实现的接口** 作为语义匹配键，在 APK 的 class nodes 中找到正确的旧 lambda 类。

**但存在歧义**：同一个宿主类中可能有多个 lambda 实现同一个接口（如多个 `Runnable` lambda）。此时仅靠接口无法区分。

**歧义处理策略**：当同一宿主类内有多个 lambda 实现同一接口时，这些 lambda 之间无法可靠区分。安全做法是：**对产生歧义的这组 lambda 类，全部跳过混淆映射，使用未混淆名称部署**——但这又回到方案 G 的不可行之处。

**进一步思考**：实际上 lambda 类的 DEX 文件名就包含了编号（如 `MainTabActivity$$ExternalSyntheticLambda13.dex`），而 mapping.txt 中也有对应条目（如 `MainTabActivity$$ExternalSyntheticLambda13 -> xxx.s8h`）。问题本质是**D8 增量编译产出的 Lambda13 与 APK 全量构建的 Lambda13 不是同一个语义类**。

**结论**：⚠️ 思路正确但歧义问题使得通用匹配不可靠

#### 方案 J：对 ExternalSyntheticLambda 跳过 mapping 映射 + 宿主类 bridge 重定向（确认采用）

**思路**：

1. **ExternalSyntheticLambda 类不做名称混淆**：在 `DexObfuscator` 中检测到 `ExternalSyntheticLambda` 类时，类名保留原始名称，但**内部引用的其他类型/方法仍然做混淆映射**（即：接口名映射为混淆名、宿主类方法调用映射为混淆名）
2. **方法名跟随接口映射**：lambda 类的 functional method 名称不从 mapping.txt 按类名查找，而是**从其实现的接口的混淆映射中推导**——接口要求什么方法名，lambda 就用什么方法名
3. **DEX 文件以原始（未混淆）类名部署**：部署时 `ExternalSyntheticLambda` 类以原始名部署到 code_cache

**关键区别于方案 G**：方案 G 是完全跳过所有映射，方案 J 是**只跳过类名和方法名的映射，但内部引用的类型仍然映射**。同时方法名不是保留原名，而是**从接口推导正确的方法名**。

**具体实现**：

在 `DexObfuscator.ObfuscationDexRemapper` 中：

1. `visit()` 阶段：检测类名是否包含 `$$ExternalSyntheticLambda`
   - 是：类名**不做 classNameMap 映射**，保留原始类名
   - 接口名仍做正常映射（`mapType()`）
2. `visitMethod()` 阶段：对 lambda 类的方法
   - 从已映射的接口名中查找接口的方法映射
   - 使用接口方法的混淆名作为 lambda 方法名
3. 内部代码引用：`visitMethodStmt()`、`visitFieldStmt()` 等仍正常做类型/方法映射

**为何安全**：
- Lambda 类以未混淆名部署，不存在"用错了混淆名"的问题
- Lambda 方法名跟随接口定义，不会出现 `AbstractMethodError`
- 内部调用宿主类的方法已正确映射为混淆名

**缺陷分析**：
- APK 中已有同名混淆类 `ExternalSyntheticLambdaN` → **不存在**，APK 中 lambda 类已被混淆为短名（如 `s8h`），不会与原始名冲突
- 类名未混淆会暴露原始类信息 → 可接受，lambda 类名本身信息量有限
- 需要解析接口的方法映射 → 实现复杂度中等，需要建立接口方法名查找表

**实现复杂度评估**：~~高~~ → 实际复杂度中等。但方案 J 只针对 lambda 类做特殊处理，存在通用性不足的问题（见 §12.2）。

### 12.2 最终方案选择：方案 L（接口/父类优先的通用方法名混淆策略）

> ⚠️ 历史演变：
> - 方案 K（漂移检测+跳过部署）→ 依赖假设，边界 case 多
> - 方案 J（lambda 专用接口推导）→ 只解决 lambda 问题
> - 方案 L（通用接口/父类优先）→ **解决所有类的方法名映射缺失问题**

#### 12.2.1 从 lambda 问题到通用问题

方案 J 仅对 `ExternalSyntheticLambda` 做特殊处理，但这暴露了一个更通用的问题：

**只要一个类不在 mapping.txt 中（新类、类名变更、编号漂移等），且它继承了接口或父类，当前 `mapMethod()` 就会因查不到方法名映射而保留原名 → 但 APK 中接口/父类的方法已被混淆 → AbstractMethodError。**

这不仅仅是 lambda 的问题，而是所有类都可能遇到的问题。例如：
- 新增的内部类实现了已有接口
- 类名因重构发生变化
- 匿名类编号漂移（不限于 ExternalSyntheticLambda）

#### 12.2.2 方案 L 核心思路

**对所有类的所有方法（非 `<init>`/`<clinit>`），统一采用"接口/父类优先"的方法名映射策略：**

1. **优先从接口和父类的 mapping 条目推导方法名**（遍历所有实现的接口和继承的父类）
2. **未命中时，再从类自身的 mapping 条目查找**（现有逻辑）
3. **都未命中时，保留原名**

这个策略不需要区分是否是 lambda 类，不需要判断类名是否在 mapping 中，对所有类一视同仁。

#### 12.2.3 为何安全

- **当类自身在 mapping 中**：接口/父类的方法映射与类自身的方法映射**必然一致**（R8 保证继承链上方法名混淆一致性）。所以接口优先与类自身查找结果相同，行为不变。
- **当类不在 mapping 中**：接口/父类优先提供了正确的混淆名，修复了原来的 bug。
- **当接口/父类也不在 mapping 中**：两步都未命中 → 保留原名（接口/父类未被混淆，保留原名是正确的）。

#### 12.2.4 方案对比

| | 旧方案（仅查类自身） | 方案 J（lambda 专用） | 方案 L（通用接口/父类优先） |
|---|---|---|---|
| lambda 漂移 | ❌ AbstractMethodError | ✅ 修复 | ✅ 修复 |
| 新增类实现已有接口 | ❌ AbstractMethodError | ❌ 未处理 | ✅ 修复 |
| 匿名类编号漂移 | ❌ AbstractMethodError | ❌ 未处理 | ✅ 修复 |
| 类名重构 | ❌ AbstractMethodError | ❌ 未处理 | ✅ 修复 |
| 无需特殊判断 | — | ❌ 需判断 lambda | ✅ 通用逻辑 |
| lambda 类名处理 | 正常映射 | 保留原名（特殊逻辑） | 正常映射（无特殊逻辑） |

### 12.3 方案 L 详细设计

#### 12.3.1 改动范围

只需改 `DexObfuscator.kt` 的 `ObfuscationDexRemapper` 内部类。**不需要改 `DexMinifyCompiler.kt`、`ICompileContext.kt`。**

| 文件 | 改动 | 说明 |
|------|------|------|
| `DexObfuscator.kt` | `visit()` 中记录当前类的**原始接口列表**和**原始父类** | 后续 `mapMethodForCurrentClass()` 使用 |
| `DexObfuscator.kt` | 新增 `mapMethodNameFromHierarchy()` 方法 | 遍历接口/父类的 mapping 条目推导方法名 |
| `DexObfuscator.kt` | `visitMethod()` 中调用 `mapMethodNameFromHierarchy()` 优先于类自身查找 | 核心修复 |

**不需要**：
- 不需要判断 `$$ExternalSyntheticLambda`
- 不需要改变类名映射逻辑（所有类名仍正常走 `mapType()`）
- 不需要 `antiClassNameMap` 反查

#### 12.3.2 数据流

```
ObfuscationDexRemapper.visit(className, superClass, interfaceNames)
  1. 正常映射类名、父类、接口（现有逻辑不变）
  2. 额外记录：
     a. originalInterfaces = interfaceNames（未映射的原始接口描述符列表）
     b. originalSuperClass = superClass（未映射的原始父类描述符）

visitMethod(method) [针对当前类声明的方法]
  1. 跳过 <init>/<clinit>（现有逻辑不变）
  2. 方法名映射改为两步：
     step1: mapMethodNameFromHierarchy(method) — 从接口/父类推导
     step2: 若 step1 未命中 → 用现有逻辑从类自身 mapping 查找
  3. owner、proto 映射不变

mapMethodNameFromHierarchy(method)
  for each interface in originalInterfaces:
    originalIfaceDot = interface.asmSigFormat.replace('/', '.')
    key = "$originalIfaceDot.${method.name}(${protoToParams(method.proto)})"
    if methodNameMap[key] exists → return it
  for superClass (if not java/lang/Object):
    originalSuperDot = superClass.asmSigFormat.replace('/', '.')
    key = "$originalSuperDot.${method.name}(${protoToParams(method.proto)})"
    if methodNameMap[key] exists → return it
  return null  // not found, fall through to class-own lookup
```

#### 12.3.3 关键实现细节

**1. visit() 阶段记录原始继承信息**

```kotlin
override fun visit(accessFlags: Int, className: String, superClass: String?,
                   interfaceNames: Array<out String>?): DexClassVisitor {
    // Existing mapping logic (unchanged)
    val mappedClassName = mapType(className)
    val mappedSuperClass = superClass?.let { mapType(it) }
    val mappedInterfaces = interfaceNames?.map { mapType(it) }?.toTypedArray()
    // ...

    return object : DexClassVisitor(classVisitor) {
        private val originalClassName = className
        private val currentMappedClassName = mappedClassName
        // NEW: record original hierarchy for method name resolution
        private val originalInterfaces = interfaceNames ?: emptyArray()
        private val originalSuperClass = superClass
        // ...
    }
}
```

**2. mapMethodNameFromHierarchy() 实现**

```kotlin
private fun mapMethodNameFromHierarchy(method: Method): String? {
    val methodName = method.name
    val params = protoToParams(method.proto)

    // Step 1: search all interfaces
    for (iface in originalInterfaces) {
        val ifaceDot = iface.asmSigFormat.replace('/', '.')
        val key = "$ifaceDot.$methodName($params)"
        methodNameMap[key]?.let { return it }
    }

    // Step 2: search super class (skip java.lang.Object)
    if (originalSuperClass != null && originalSuperClass != "Ljava/lang/Object;") {
        val superDot = originalSuperClass.asmSigFormat.replace('/', '.')
        val key = "$superDot.$methodName($params)"
        methodNameMap[key]?.let { return it }
    }

    return null // not found
}
```

**3. visitMethod() 中的调用顺序**

```kotlin
override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
    val mappedMethod = mapMethodWithHierarchy(method)
    // ...
}

private fun mapMethodWithHierarchy(method: Method): Method {
    if (method.name == "<init>" || method.name == "<clinit>") {
        // unchanged
    }

    val ownerMapped = mapType(method.owner)

    // NEW: hierarchy-first method name resolution
    val nameMapped = mapMethodNameFromHierarchy(method)
        ?: run {
            // Fallback: existing class-own lookup
            val ownerDot = method.owner.asmSigFormat.replace('/', '.')
            val params = protoToParams(method.proto)
            val key = "$ownerDot.${method.name}($params)"
            methodNameMap[key] ?: method.name
        }

    val protoMapped = mapProto(method.proto)
    // ...
}
```

**重要：这个改动只影响 `visitMethod()` 中当前类自身声明的方法的名称映射，不影响 `mapMethod()` 中方法调用指令的映射（`visitMethodStmt` 等中 `method.owner` 已经是声明方的类型，走现有逻辑就对了）。**

#### 12.3.4 需要区分 visitMethod 和 visitMethodStmt 的 mapMethod

当前代码中 `visitMethod()` 和 `visitMethodStmt()` 共用同一个 `mapMethod()` 函数。方案 L 需要**仅对 `visitMethod()`（类声明的方法）应用层级优先逻辑**，而 `visitMethodStmt()`（方法调用指令）仍用原有的 `mapMethod()`。

原因：`visitMethodStmt()` 中的 `method.owner` 是**被调用方的声明类型**，如 `invoke-interface Lcom/example/IFoo;.bar()V` 中 owner 是 `IFoo`，在 `methodNameMap` 中可以直接找到 `com.example.IFoo.bar()` 的映射。

实现方式：在 `DexClassVisitor` 子类的 `visitMethod()` 中使用新的 `mapMethodForCurrentClass()`，而保留 codeVisitor 中的 `mapMethod()` 不变。

#### 12.3.5 泛型擦除问题分析

**结论：不需要特殊处理。**

mapping.txt 中记录的方法签名是**泛型擦除后**的签名。DEX 字节码中方法的 proto 同样是**泛型擦除后**的描述符。两者天然一致。

举例验证：
```java
interface Function<T, R> { R apply(T t); }
```

- mapping.txt 中记录的是：`java.lang.Object apply(java.lang.Object) -> a`
- DEX 字节码中 `Function.apply` 的 proto 是：`(Ljava/lang/Object;)Ljava/lang/Object;`
- `protoToParams()` 转换后：`java.lang.Object`
- 查找键：`com.example.Function.apply(java.lang.Object)` → 精确匹配 ✅

实现类（如 lambda）中的 bridge 方法签名也是擦除后的，与接口声明一致。如果存在具体类型的特化方法（如 `apply(SomeClass)V`），那是实现类自身的方法，不是接口方法，不需要从接口推导。

**待验证项**：构造测试用例确认 D8 生成的 lambda 类中，functional method 的签名是否确实与接口声明的擦除签名一致。如果不一致（即 lambda 方法使用了具体类型参数），则需要回退到前缀匹配。此为 TDD 验证项。

#### 12.3.6 边界情况

| 边界情况 | 处理 |
|---------|------|
| 类自身和接口/父类都有映射 | 接口/父类优先命中。由于 R8 保证继承链上方法名一致，结果相同 |
| 类不在 mapping 中，接口在 | 接口优先命中，返回正确的混淆名 ✅（核心修复场景） |
| 类不在 mapping 中，接口也不在 | 两步都未命中 → 保留原名（接口未被混淆，正确） |
| 类在 mapping 中，接口不在 | 接口步骤未命中 → 回退到类自身查找命中 |
| 方法只在父类中声明 | superClass 步骤命中 |
| 接口继承了另一个接口 | 当前 DEX 的 `interfaceNames` 只包含直接接口。但 R8 会为所有间接接口方法也生成映射条目到直接接口上。如果不够，需要递归——但实际上 R8 mapping 中，实现类条目会包含从顶层接口继承下来的所有方法映射。**待验证** |
| 父类继承了另一个父类 | 同上，DEX 的 `superClass` 只有直接父类。当前方案只查直接父类。如果方法声明在祖父类上，需要递归。但由于我们是对增量编译的类做混淆，这些类通常自身直接实现/覆盖了接口方法，不太可能只在遥远的祖先类中声明。**如果遇到此 case，后续扩展为多级查找** |
| 类实现多个接口，不同接口有同名同参方法 | 遍历接口取第一个命中。由于 R8 保证同名同参方法映射为相同的混淆名（虚方法表约束），取哪个结果都一样 |
| `<init>` / `<clinit>` | 跳过（现有逻辑不变） |

### 12.4 实现计划

1. **TDD 测试先行**（`DexObfuscatorTest.kt`）：
   - `testMethodNameFromInterface_classNotInMapping`: 类不在 mapping 中，方法名从接口推导正确
   - `testMethodNameFromInterface_classInMapping`: 类在 mapping 中，接口优先结果与类自身一致
   - `testMethodNameFromSuperClass_classNotInMapping`: 类不在 mapping 中，方法名从父类推导正确
   - `testMethodNameFromHierarchy_interfaceNotInMapping_fallbackToClass`: 接口不在 mapping 中，回退到类自身查找
   - `testMethodNameFromHierarchy_neitherInMapping_keepOriginal`: 都不在 mapping 中，保留原名
   - `testMethodNameFromHierarchy_multipleInterfaces`: 多接口场景
   - `testGenericInterface_erasedSignatureMatch`: 泛型接口的擦除签名匹配验证
   - `testNonOverrideMethod_usesClassOwnMapping`: 类自身的非覆盖方法仍走类自身映射
   - `testMethodStmt_unaffectedByHierarchyLogic`: 方法调用指令不受层级逻辑影响（回归保护）

2. **实现**（仅 `DexObfuscator.kt`）：
   - `visit()` 中额外记录 `originalInterfaces` 和 `originalSuperClass`
   - 新增 `mapMethodNameFromHierarchy()` 方法
   - `visitMethod()` 中先调 `mapMethodNameFromHierarchy()`，未命中再走类自身查找
   - `mapMethod()`（用于 `visitMethodStmt` 等）保持不变

3. **文档同步**：更新 `02_compile_manifest_obfuscation.md` 中相关章节
