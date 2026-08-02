# 修改内部类/Lambda 触发子类级联重编译问题排查

> 创建日期：2026-03-17
> 状态：根因已确认，待修复

---

## 一、现象

修改 `BasePager.kt` 中的内部类 `NativeEventListener`（仅新增 lambda 表达式），触发了 42 个
`BasePager` 子类的第二轮批量重编译，整体耗时约 12 秒。

---

## 二、复现日志

关键节点（第一轮编译后）：

```
[13:49:36.403] CheckEffectByTopLevelClass
  undeployedFiles: [BasePager.kt]
  effectedSourceFiles: [ArtistEditPage.kt, BubbleChatPager.kt, ... 共 42 个]
[13:49:36.403] Compile success, but found effected source files, continue compile.
```

---

## 三、根因分析

### 3.1 Lambda 重编号

D8 以 `--file-per-class` 模式编译（非 R8 全量优化）。
在 `BasePager.kt` 内新增一个 lambda → D8 对 `BasePager` 的 `$$ExternalSyntheticLambda` 序号重排：

```
旧：ExternalSyntheticLambda10.<init>(BasePager)V  （捕获了外部类实例）
新：ExternalSyntheticLambda10.<init>()V           （不捕获，是另一个 lambda）
```

多个 Lambda 类结构发生变化（`structure changed, need hot fix`），触发 `changedMethodRef`：

```
Lcom/xxx/pages/BasePager$$ExternalSyntheticLambda10;.<init>(BasePager)V  → deleted
Lcom/xxx/pages/BasePager$$ExternalSyntheticLambda11;.<init>(BasePager)V  → deleted
...（共 6+ 个 Lambda 结构变化）
```

### 3.2 误触发的子类传播（Bug 根因）

`DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()` 中 **step 2**（subclass 传播）：

```kotlin
// step 2: 为 changedMethodRefs 的 owner 查找所有子类
subclass_refs WHERE class_id IN (BasePager_id)
→ 找到 ArtistEditPage, BubbleChatPager, DressCenterPage, ... 42 个子类
→ 直接写入 refClassIds（未等 step 3 的 method_refs 查询验证）
```

**这是 Bug**：step 2 的设计语义是处理 `invokevirtual`/`invokeinterface` 场景——父类虚方法被删改，
子类同名方法的调用方需要感知。
但 `$$ExternalSyntheticLambda.<init>()` 是 **static** 方法，子类不继承 static 方法，
step 2 的传播完全是多余的。

验证证据：
- `found effected source` 日志中，所有命中均为 `BasePager.kt`，**没有任何子类文件**被 step 3 的
  `method_refs` 查询命中
- 子类进入 `effectClassNodesMap` 完全来自 step 2 提前写入的 `refClassIds`

### 3.3 `BasePager` 自身的 `$r8$lambda$` static 方法变化

（注意：这里的 `$r8$lambda$` 名称是 D8 desugar 产物，不是 R8 minify 产物）

`BasePager` 自身也出现了 `addedMethods` / `deletedMethods`（lambda 编号偏移导致方法名哈希变化），
进一步产生了大量 `changedMethodRef`，step 2 对这些 static 方法同样错误地做了子类传播。

---

## 四、传播路径对比

| 路径 | 是否实际命中子类 | 是否应该触发 |
|------|-----------------|-------------|
| Step 2: subclass_refs 传播 `$$ExternalSyntheticLambda.<init>` | ✅ 命中（直接写入） | ❌ 不应该（static 方法） |
| Step 3: method_refs 查询子类 | ❌ 未命中 | - |
| Step 4: changedAbstractClasses 传播 | ❌ 未参与 | - |

---

## 五、修复方案

**位置**：`main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabaseSqLiteHelper.kt`

**Step 2 中加 static 方法过滤**：在查询 `subclass_refs` 前，排除 `changedMethodRefs` 中 owner 为
static 方法的条目，避免对 static 方法做子类传播。

当前 `MethodNodeDb` 只有 `(classId, name, desc)`，无 access flag。需要：
1. 在 `changedMethodRefs` 传入时携带 access flag（`MethodNode` 本身有 `access` 字段）
2. 在 step 2 循环前，过滤掉 `access and ACC_STATIC != 0` 的方法节点

或更简单的启发式方案：
- `$$ExternalSyntheticLambda` 类的方法名为 `<init>` 或 `invoke`，owner 含 `$$ExternalSyntheticLambda`
  → 直接跳过 subclass 传播（因为 synthetic lambda 类不存在子类）

---

## 六、影响范围评估

修复后，修改 `BasePager.kt`（内部 lambda 变化）：
- 第一轮编译 `BasePager.kt`：正常
- Lambda 重编号产生的 `changedMethodRef`：只命中 `BasePager` 自身（因为 static 方法只有 `BasePager`
  自己调用）
- 42 个子类：**不再被误触发**
- 预计节省第二轮编译时间（约 9 秒）

---

## 七、相关代码位置

| 文件 | 行号 | 说明 |
|------|------|------|
| `DeployDataDatabaseSqLiteHelper.kt` | ~826-884 | Step 2 subclass 传播逻辑 |
| `DeployDataDatabaseSqLiteHelper.kt` | 853 | `refClassIds` 提前写入（bug 触发点） |
| `DeployDataDatabase.kt` | 454-480 | In-memory 版同等逻辑（也需修复） |
| `ClassStructure.kt` | 64 | `MethodNode` 定义（含 access 字段） |
| `DeployDataGenerator.kt` | 123-134 | `changedMethodRef` 填充入口 |
