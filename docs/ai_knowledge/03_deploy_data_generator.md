# 部署系统：影响分析与部署数据生成

> 最后核对：2026-03-30（新增 MINIFY_MEMBER_REMOVED 类型、merge 优先级修复）
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页关注“改了哪些文件 -> 需要部署哪些类/资源”的分析逻辑。

---

## 2. 关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `DeployDataGenerator` | `main/.../deploy/data/DeployDataGenerator.kt` | 构建 `JuggDeployData` |
| `DeployDataDatabase` | `main/.../deploy/data/DeployDataDatabase.kt` | 维护类引用关系与增量索引 |
| `ClassNodeComparator` | `main/.../deploy/data/ClassNodeComparator.kt` | 类结构差异比较（热更能力判定） |
| `InlineMethodDetector` | `main/.../deploy/data/InlineMethodDetector.kt` | 内联影响分析 |
| `ClassFileParser` / `DexFileNodeCollector` | `main/.../deploy/data/` | 字节码节点提取与解析 |
| `EffectedClassNode` | `main/.../deploy/data/EffectedClassNode.kt` | 受影响类数据模型 |

---

## 3. 核心流程

1. 从编译产物和历史索引读取变更类集合。  
2. 用 `ClassNodeComparator` 识别结构性变化。  
3. 查询/更新 `DeployDataDatabase` 依赖关系。  
4. 结合内联检测补齐受影响调用方。  
5. 生成最终 `JuggDeployData`（决定热更/热修/重装倾向）。

---

## 4. 为什么这层关键

- 增量部署是否安全，核心依赖这里的“影响面判断”。  
- 判定过松会引入运行时风险，判定过紧会导致频繁回退。

---

## 5. 重编译（effectedSource）完整机制

### 5.1 触发入口：ClassNodeComparator 结构差异

`DeployDataGenerator.buildDeployData()` 对每个发生变化的类调用 `ClassNodeComparator.compare()`，输出 `ClassNodeDiffResult`，其中关键字段：

| 字段 | 含义 | 进入下游 |
|------|------|----------|
| `effectMethods` | 结构性变化的方法（删除/签名改变/可见性从 private 变 public 等） | → `changedMethodRef` |
| `deletedFields` | 被删除的字段 | → `changedFieldRef` |
| `isAddedAbstractMethodForNonAbstractClass` | 抽象类/接口新增了 abstract 方法 | → `changedAbstractClasses` |
| `modifiedGenericSignature` | 类级 generic signature 变化（如 `T` → `T extends Number`） | → `changedGenericSignatureClasses` |

**`effectMethods` 的判定规则（`isEffectedChanged`）**：两个 `MethodNode` 满足以下条件则视为"结构未变"（不进入 effectMethods）：
- `owner`、`name`、`desc` 完全相同
- `access` 忽略 `ACC_ABSTRACT` 和 `ACC_PRIVATE` 位后相同

换言之，以下变化会产生 `effectMethods`（触发重编译）：
- 方法删除
- 方法签名（name/desc）变化
- `private` ↔ 非 `private` 可见性变化
- 其他 access flag 变化（如 `static` / `final`）

以下变化**不会**触发重编译（isCanHotReload = true）：
- 仅方法体内容变化（方法签名不变）
- 新增 non-static 字段（但会触发 hotfix 而非 hotreload）
- `abstract` flag 变化（不影响调用方字节码）

**特殊过滤：R subclass**：`R$xxx` 类不加入 changedMethodRef / changedFieldRef，避免 RFileFixer 处理后字段大量变化触发全量重编译。

**特殊过滤：`$` 方法**：编译器合成方法（名称含 `$`）虽进入 effectMethods，但不写入 `deletedNormalMethodClasses`，区分用户代码与合成代码的删除。

---

### 5.2 重编译开关

```kotlin
buildDeployData(
    isNeedCheckRecompile = true,            // 总开关；false 时完全跳过，effectedSource 为空
    isNeedCheckRecompileMinifyRemovedClass = false, // release 场景：检查 minify 删类
    isCompilingEffectedSourceFiles = false, // 正在编译受影响文件时，跳过 inline 检测（防循环）
)
```

---

### 5.3 getEffectedSourceAndClass 四步传播逻辑

`DeployDataDatabase` / `DeployDataDatabaseSqLiteHelper` 中的 `getEffectedSourceAndClass()` 按五步计算受影响类集合：

| 步骤 | 说明 | 设计语义 |
|------|------|----------|
| Step 1 | 构建 `changedMethodRefs` 的 classId 映射 | 准备 DB 查询所需 ID |
| Step 2 | 查 `subclass_refs`，为 **非 static** `changedMethodRefs` 的 owner 找所有子类，生成子类虚拟 method node 写入 `changedMethodRefsWithSubclasses` | 处理 `invokevirtual`/`invokeinterface`：父类虚方法被删改时，调用子类同名方法的调用方也需感知 |
| Step 3 | 查 `method_refs`，找所有调用了 `changedMethodRefsWithSubclasses`（含 static 方法）的类 | 直接引用关系命中 |
| Step 4 | 查 `subclass_refs`，找 `changedAbstractClasses` 的所有非抽象子类 | 父类新增 abstract 方法，子类必须重编 |
| Step 5 | 先查 `method_refs/field_refs` 找 `changedGenericSignatureClasses` 及其受影响子类的 direct member callers，再递归查 `subclass_refs` | 类级 generic signature 改变时，子类声明和具体类调用方的泛型校验都可能失效，需重编 |

**⚠️ Step 2 的 static 过滤（2026-03-17 修复）**

修复前，Step 2 对所有 `changedMethodRefs` 均做子类传播，包括 `ACC_STATIC` 方法。
D8 在 `--file-per-class` 模式下会将 Kotlin lambda 编译为父类的 `$r8$lambda$xxx` 静态方法，lambda 重编号后这些方法以 `changedMethodRefs` 形式出现，错误触发所有子类重编译。

修复方案：Step 2 while 循环的初始 `currentSuperClassIds`（SQLite 版）/ `classesToCheckSubclasses`（内存版）只取 `access == MISS_ACCESS || (access and ACC_STATIC) == 0` 的方法 owner，`changedMethodRefsWithSubclasses` 本身保留全部方法供 Step 3 使用。

详见 `docs/task/recompile_cascade_bug_analysis.md`。

**⚠️ Step 5 的 generic signature 传播（2026-04-02 修复）**

修复前，`ClassNode` / `ClassNodeComparator` 只比较 DEX 擦除后的 `super/interface/method/field` 结构。像 `class Parent<T>` 改成 `class Parent<T extends Number>` 这类**不改变擦除后 descriptor** 的修改，会被误判为 `isSameStructure = true`，导致 `effectedSource` 为空。

修复方案：
- `ClassNode` 新增 `genericSignature`，从 DEX 的 `Ldalvik/annotation/Signature;` 注解还原
- `ClassNodeComparator` 将 `modifiedGenericSignature` 视为结构变化
- `getEffectedSourceAndClass` 新增 Step 5，递归标记所有子类为 `SOURCE`
- Step 5 继续扩展为：把 generic signature 变化类及其受影响子类的 direct member callers 也标记为 `SOURCE`

当前能力边界：Step 5 目前保证两类传播：
- **子类级联**重编译
- **直接调用/访问 generic signature 变化具体类成员**的调用方重编译

仍未覆盖的范围：
- 仅通过源码泛型类型约束间接受影响、但 DEX 中没有落到该类 direct member refs 的场景
- 例如只在局部变量/方法泛型声明里出现类型约束、但没有对应 constructor / method / field direct ref 的源码

---

### 5.4 EffectedType 类型说明

`EffectedClassNode.EffectedType` 枚举标识受影响类的检测来源和处理路径：

| 类型 | 含义 | 检测来源 | 处理路径 |
|------|------|----------|----------|
| `SOURCE` | 常规引用变更，需源码重编译 | `getEffectedSourceAndClass` 四步传播 | 源码重编译（`SourceCompiler`） |
| `INLINE_IMPL_CHANGE` | R8 内联方法实现变更，调用方持有旧副本 | `InlineMethodDetector` 解析 mapping inline 标记 | 字节码补全（`DexMinifyCompiler`） |
| `MINIFY_MEMBER_REMOVED` | R8/ProGuard 移除了类或其成员，增量 dex 引用缺失成员 | `DeployDataDatabaseSqLiteHelper.getEffectedClassNodesForMinify` | 字节码补全（`DexMinifyCompiler`） |

扩展属性：`Collection<EffectedClassNode>.sources` / `.inlineImplChanges` / `.minifyMemberRemoved` 分别过滤对应类型。

---

### 5.5 inline 检测补充（release 场景）

仅当 `isNeedCheckRecompileMinifyRemovedClass = true`（release 构建）且 `isCompilingEffectedSourceFiles = false` 时，`InlineMethodDetector` 会检查 R8 mapping 中的 inline 链，将内联了被删除方法的调用方也加入受影响列表，`effectedType = INLINE_IMPL_CHANGE`（由 `DexMinifyCompiler` 处理，不触发源码重编译）。

---

### 5.6 minify 移除检测（release 场景）

`getEffectedClassNodesForMinify` 在 release 构建中检测两类问题：
- **Check 1**：整个类被 R8 移除（APK 数据库中无该类记录），`effectedType = MINIFY_MEMBER_REMOVED`
- **Check 2**：类存在但部分成员被移除（方法/字段在增量 dex 中被引用但 APK 中不存在），`effectedType = MINIFY_MEMBER_REMOVED`

---

### 5.7 merge 合并优先级

`DeployDataGenerator.merge()` 将 `InlineMethodDetector` 的结果合并到 `effectedNodes` 中。当同一类同时被标记为 `SOURCE`（常规引用检测）和 `INLINE_IMPL_CHANGE`（inline 检测）时，**保留 `SOURCE`**——源码重编译能力严格强于字节码补全（源码重编能解决 inline 问题，反之不行）。

---

### 5.8 constRef 补充（常量引用）

与上述 method/field/abstract 传播并行，`ConstRefEffectProvider.getEffectedFiles()` 独立查询常量引用影响，结果写入 `JuggDeployData.constRefEffectedSourcePaths`（不在 `effectedClassNodes` 中）。详见 `03_deploy_const_ref.md`。

---

## 6. 常见问题定位

- **”改动很小却触发大量重编译”**：先看日志 `found effected source`，确认命中来自 Step 2（子类传播）还是 Step 3（直接引用）。若全量子类被 Step 2 扫入，检查 `changedMethodRef` 中是否混入了 static 方法（`$r8$lambda$` 等合成方法）。
- **”调用方没更新导致运行异常”**：检查 `DeployDataDatabase` 索引更新，以及 inline 检测是否在 release 构建中启用（`isNeedCheckRecompileMinifyRemovedClass`）。
- **”某方法改了但 effectedSource 为空”**：确认该方法的 access flag 变化是否触发了 `isEffectedChanged` 判断，以及 `changedMethodRef` 是否被 R subclass 过滤掉。
- **”分析耗时异常”**：关注 SQLite 数据量、索引重建频率与 class 解析规模。
- **”`Isolated process parsing failed` 且 `ClassNotFoundException: ApkParserProcess`”**：优先检查 `ApkParserProcessLauncher` 的 `-cp` 构建是否包含 URL 编码路径（如 `%20`）。子进程 classpath 必须使用可访问的本地文件路径。
- **”修改基类 lambda 触发所有子类重编译”**（已修复）：Step 2 static 方法误传播，见第 5.3 节及 `docs/task/recompile_cascade_bug_analysis.md`。
- **”修改类泛型没有触发级联重编译”**（已修复）：确认 `ClassNodeComparator` 是否输出 `modifiedGenericSignature`，再看 Step 5 是否将子类和 direct member callers 加入 `effectedSource`。

---

## 7. 关联文档

- 部署核心：`03_deploy_core.md`
- 编译主流程：`02_compile_core.md`
- 级联重编译 Bug 详细分析：`docs/task/recompile_cascade_bug_analysis.md`
