# 部署系统：影响分析与部署数据生成

> 最后核对：2026-03-17
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

## 5. getEffectedSourceAndClass 四步传播逻辑

`DeployDataDatabase` / `DeployDataDatabaseSqLiteHelper` 中的 `getEffectedSourceAndClass()` 按四步计算受影响类集合：

| 步骤 | 说明 | 设计语义 |
|------|------|----------|
| Step 1 | 构建 `changedMethodRefs` 的 classId 映射 | 准备 DB 查询所需 ID |
| Step 2 | 查 `subclass_refs`，为 `changedMethodRefs` 的 owner 找所有子类，生成子类虚拟 method node，**直接写入 `refClassIds`** | 处理 `invokevirtual`/`invokeinterface`：父类方法被删改时，调用子类同名方法的调用方也需感知 |
| Step 3 | 查 `method_refs`，找所有调用了 `changedMethodRefsWithSubclasses` 的类 | 直接引用关系命中 |
| Step 4 | 查 `subclass_refs`，找 `changedAbstractClasses` 的所有非抽象子类 | 父类新增 abstract 方法，子类必须重编 |

**⚠️ 已知 Bug（2026-03-17 确认）**

Step 2 对 **static 方法**做了不必要的子类传播。

`$$ExternalSyntheticLambda.<init>()` / `$r8$lambda$xxx()` 等 D8 desugar 产物均为 `public static synthetic` 方法，子类不继承 static 方法。当 `BasePager.kt` 修改导致 Lambda 重编号（`ExternalSyntheticLambda` 序号偏移），step 2 错误地将 `BasePager` 的所有子类写入 `refClassIds`，触发大量不必要的重编译。

验证方式：`found effected source` 日志中，子类文件不会被 step 3 命中，全部来自 step 2 提前写入。

修复方向：Step 2 遍历子类前，跳过 `owner` 为 static 方法的节点（`access and ACC_STATIC != 0`），或对 `$$ExternalSyntheticLambda` 类直接跳过（此类没有子类）。详见 `docs/task/recompile_cascade_bug_analysis.md`。

---

## 6. 常见问题定位

- “改动很小却全量回退”：检查结构差异判定与引用扩散。
- “调用方没更新导致运行异常”：检查 `DeployDataDatabase` 索引更新和 inline 检测。
- “分析耗时异常”：关注 sqlite 数据量、索引重建频率与 class 解析规模。
- “`Isolated process parsing failed` 且 `ClassNotFoundException: ApkParserProcess`”：优先检查 `ApkParserProcessLauncher` 的 `-cp` 构建是否包含 URL 编码路径（如 `%20`）。子进程 classpath 必须使用可访问的本地文件路径。
- “修改基类内部类/lambda 触发所有子类重编译”：Step 2 对 static 方法的错误 subclass 传播，见第 5 节 Bug 说明及 `docs/task/recompile_cascade_bug_analysis.md`。

---

## 7. 关联文档

- 部署核心：`03_deploy_core.md`
- 编译主流程：`02_compile_core.md`
- 级联重编译 Bug 详细分析：`docs/task/recompile_cascade_bug_analysis.md`
