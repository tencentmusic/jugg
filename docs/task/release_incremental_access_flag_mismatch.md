# Release 增量编译后 IllegalAccessError / AbstractMethodError 分析方案

> 创建时间：2026-03-27
> 状态：已修复（方案 E）

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
2. **R8 宽化行为**：启用 `-allowaccessmodification` 后，R8 的 `AccessModifier` pass 无条件将所有 `private`/`protected`/`package-private` 成员宽化为 `public`（`-keep` 保护的 private 方法除外）。
3. **vtable 安全性**：R8 通过 minification（方法重命名）避免 vtable 冲突。Jugg 的 `DexObfuscator` 也做方法重命名，具备同等保护。
4. **DB 存储精确 access flags**：`DeployDataDatabaseSqLiteHelper.toMethodString()` 以 `"${it.access} ${it.name} ${it.desc}"` 格式存储，`toMethodList()` 以 `MethodNode(owner, parts[0].toInt(), parts[1], parts[2])` 读取。
5. **Jugg 增量链路无 access flag 修改**：`javac → D8 → DexObfuscator` 全程透传原始 access flags。

### 6.2 方案选择

选择**方案 E（直接宽化 `private → public`）**作为最终实现方案。

理由：
- 与 R8 实际行为完全匹配
- 实现最简，无 DB 依赖
- vtable 安全由 minification 保证
- javac 已在编译阶段校验源码级可见性
- DEX verifier 只检查 caller 权限，宽化 = 更宽松

---

## 7. 方案 E：直接宽化实现

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
