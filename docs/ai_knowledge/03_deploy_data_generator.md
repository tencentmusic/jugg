# 部署系统：影响分析与部署数据生成

> 最后核对：2026-02-23  
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

## 5. 常见问题定位

- “改动很小却全量回退”：检查结构差异判定与引用扩散。  
- “调用方没更新导致运行异常”：检查 `DeployDataDatabase` 索引更新和 inline 检测。  
- “分析耗时异常”：关注 sqlite 数据量、索引重建频率与 class 解析规模。

---

## 6. 关联文档

- 部署核心：`03_deploy_core.md`
- 编译主流程：`02_compile_core.md`
