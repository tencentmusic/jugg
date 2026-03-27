# Release 增量编译后 IllegalAccessError / AbstractMethodError 分析方案

> 创建时间：2026-03-27
> 状态：进行中（方案 E 已失败，切换至方案 D）

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

### 方案 D（推荐）：从 APK 数据库中读取 access flags 并在重混淆时同步

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
