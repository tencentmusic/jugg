---
title: 重编译 / 扩散编译
description: 说明 Jugg 如何在首轮源码编译后继续查找受影响源码，并触发下一轮增量编译。
status: active
tags:
  - concept
  - compile
  - recompile
---

# 重编译 / 扩散编译

Jugg 的首轮源码编译只处理本轮直接变化的文件。编译成功后，Jugg 会用新旧 class 结构和 APK 引用索引查一遍：有没有旧源码也需要重新编译。

如果有，`IncrementalCompilerHelper` 会把这些源码还原成 `ChangedFile`，递归进入下一轮增量编译。日志里会看到：

```text
Compile success, but found effected source files, continue compile.
```

## 从部署数据开始

重编译判断发生在编译成功之后。`DeployFileManager.getRecompileFiles()` 会调用 `DeployDataGenerator.buildDeployData()`，先构造一份部署数据，再从里面取出需要重编译的源码。

```text
首轮编译成功
  -> staging 写入 DeployItem
  -> DeployFileManager.getRecompileFiles()
  -> DeployDataGenerator.buildDeployData()
  -> CompileEffectAnalyzer 还原源码文件
  -> IncrementalCompilerHelper 进入下一轮
```

这里的部署数据还不是已提交状态。只有整轮部署成功后，`DeployFileManager.commit()` 才会推进历史。

## class 结构信号

`DeployDataGenerator` 解析本轮 dex，拿新 class 与数据库里的旧 class 对比。`ClassNodeComparator` 把差异压成几类信号：

| 信号 | 后续用途 |
|---|---|
| 方法删除、签名变化、有效 access flag 变化 | 查 method caller。 |
| 字段删除 | 查 field caller。 |
| 抽象父类或接口新增 abstract 方法 | 查子类。 |
| 类级 generic signature 变化 | 查直接 member caller 和子类。 |

`R$xxx` class 不进入 method/field 传播。资源修复可能产生大量 R 字段变化，直接传播会把太多引用 R 的源码拉进重编译。

## 六步传播

`DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()` 用 APK / 已部署数据库里的引用表做查询。当前代码按六步收敛：

```text
1. 把变化 method / field / abstract / generic class 转成 DB classId
2. 对非 static 变化方法查 subclass_refs，补出虚方法分发下的子类 method ref
3. 查 method_refs / field_refs，找到直接调用方或访问方
4. 对新增 abstract method 的 class/interface 递归找子类
5. 对 generic signature 变化类查 caller，并递归找子类
6. 把 classId 反查 class name / source，生成 EffectedClassNode(SOURCE)
```

Step 2 有一个容易看错的点：static 方法会保留在 `changedMethodRefsWithSubclasses` 里，供 Step 3 查直接调用；但 static 方法不能作为子类遍历的起点。代码里用 access flag 过滤了这件事。

## 下一轮过滤

影响传播不能无限循环。`IncrementalCompilerHelper` 会过滤已经处理过的结果：

- 排除上一轮已经编译过的源码。
- 对同一个 session 内已经满足过的影响触发键，不再重复跟编。
- Kotlin top-level file facade 命中时，可以突破“上一轮已编译”过滤再编一次。

触发键来自 `ContinueCompileEffectFilter`。普通 class 传播用 `effectedPath + effectedByClasses`；const-ref 使用自己的批次键。

## release/minify 补偿

minified 场景还会查两类补偿：

| 类型 | 来源 | 处理 |
|---|---|---|
| `MINIFY_MEMBER_REMOVED` | 增量 dex 引用了 APK 中已被 R8/ProGuard 移除的类或成员 | 交给 `DexMinifyCompiler` 补偿。 |
| `INLINE_IMPL_CHANGE` | `InlineMethodDetector` 从 mapping 找到曾经被 inline 的调用方 | 交给 `DexMinifyCompiler` 补偿。 |

同一个 class 如果已经是 `SOURCE`，merge 时保留 `SOURCE`。源码重编译的修复范围大于字节码补偿，反过来不成立。

## 与 const-ref 的关系

常量引用不走 method/field/subclass 表。`DeployDataGenerator` 会单独调用 `ConstRefEffectProvider`，结果写进 `JuggDeployData.constRefEffectedSourcePaths`。`CompileEffectAnalyzer` 最后把它和普通受影响源码合并。

## 相关页面

- [常量引用分析](./const-ref.md)
- [源码增量编译](./source.md)
- [部署数据与影响分析](../deploy-data-and-impact.md)
- [重编译/扩散编译能力](../../capabilities/compile/recompile-propagation.md)
