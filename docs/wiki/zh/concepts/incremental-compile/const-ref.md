---
title: 常量引用分析
description: 说明 Jugg 如何记录 Java/Kotlin 编译期常量的定义和引用，并在常量值变化后补编译引用方。
status: active
tags:
  - concept
  - compile
  - const-ref
---

# 常量引用分析

Java 的 `static final` 常量和 Kotlin 的 `const val` 会进入调用方编译结果。只编译常量定义所在文件，不一定能更新引用方。Jugg 为这类变化单独维护一套索引，部署数据生成时再把命中的引用方源码加入下一轮编译。

这套逻辑不混在普通 class 结构传播里。普通传播看 method、field、subclass 和 generic signature；常量引用看源码里的常量定义和引用候选。

## 索引写入

`DeployFileManager` 把 Java/Kotlin 保存、删除和 source dir 初始化事件交给 `ConstRefEngine`。`ConstRefEngine` 不在每次保存时立刻解析当前文件。它会把前一个编辑文件放入 pending，当前文件先标记为 editing；编译前 `awaitAnalysis()` 会把当前 editing 文件冲刷进分析队列。

```text
文件保存
  -> ConstRefEngine.onFileSaved()
  -> 前一个 editing 文件进入 pending
  -> 当前文件记录为 editing

编译前
  -> awaitAnalysis()
  -> flush 当前 editing 文件
  -> PRE_COMPILE 分析目标文件
```

解析由 `ConstRefAnalyzer` 分发到 Java/Kotlin parser。写入 SQLite 的不是完整语义解析结果，而是两类事实：

| 数据 | 含义 |
|---|---|
| `ConstDefinition` | 一个可参与内联的常量定义，包含文件、包名、类名、常量名、类型和值。 |
| `ConstReferenceCandidate` | 源码里出现的引用候选，包含 const 名、owner 形式、import、package 等信息。 |

引用候选不要求定义方已经被扫描。Jugg 在影响查询阶段再用变更后的 definition key 与候选行匹配。这样 full scan 没完成时，编译前仍能用已完成缓存继续查一部分结果。

## 真实变化 key

`ConstRefChangeTracker` 对比同一个文件前后的常量签名。签名由类型和值组成。空白改动不会生成 changed key。

```text
previous definitions
  -> current definitions
  -> 找出签名变化的 (fqClassName, constName)
  -> 找出被删除或 const -> val 的旧 key
```

删除常量，或把 `const val` 改成普通 `val`，会进入 removed key。影响查询会同时消费 changed key 和 removed key。

`private const val` 与 `private static final` 不进入 definition 索引。它们只影响声明所在源码文件，本文件已经在首轮编译里。

## 影响查询

`DeployDataGenerator.buildDeployData()` 在普通 class 影响分析后调用 `ConstRefEffectProvider`：

```text
DeployDataGenerator
  -> ensureReadyForRecompile(changedSourcePaths)
  -> getEffectedFiles(changedSourcePaths)
  -> 写入 JuggDeployData.constRefEffectedSourcePaths
```

`ConstRefImpactResolver` 会把命中的引用方过滤一遍：

- 引用方不能是本轮已经变化的源码文件。
- 引用方文件必须仍然存在。
- 同一个 `refFilePath + defFqClassName + constName` 只保留一次。

`CompileEffectAnalyzer` 再把 `constRefEffectedSourcePaths` 转成源码文件，和普通 `effectedClassNodes` 的源码结果合并，交给下一轮编译。

## 清理时机

常量 diff 不在查询阶段清掉。部署成功后，`DeployFileManager.commit()` 才会调用 `ConstRefEngine.acknowledgeEffectedFilesAfterDeployCommit()`。

这个顺序来自代码里的状态边界：查询只是在构造本轮 `JuggDeployData`。如果后面的重编译或部署失败，同一批 const diff 还要在下一次编译里继续可查。

## 缓存

常量索引使用两个 SQLite 文件：

| 文件 | 用途 |
|---|---|
| `const_ref_shared.db` | 存源码 checksum、analysis head、definitions、reference candidates。 |
| `repo_fingerprint.db` | 存 repo/worktree 共享的文件指纹，减少重复解析。 |

`ConstRefSessionCache` 只做会话内热点缓存。DB 才是跨轮影响查询的来源。

## 边界

- `awaitAnalysis()` 超时后返回未 ready，部署数据生成会记录 warn，并用已完成缓存继续查。
- `getEffectedFiles()` 异常时返回空列表，不阻断部署数据生成。
- Java parser 只记录可内联类型的 `static final` 字段。
- Kotlin parser 记录 top-level、object、companion、嵌套 class/object 中的 `const val`。
- Java/Kotlin parser 会忽略注释和字符串里的伪引用。

## 相关页面

- [重编译 / 扩散编译](./recompile-propagation.md)
- [源码增量编译](./source.md)
- [部署数据与影响分析](../deploy-data-and-impact.md)
- [常量引用分析能力](../../capabilities/compile/const-ref.md)
