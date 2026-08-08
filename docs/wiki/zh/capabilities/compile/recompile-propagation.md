---
title: 重编译/扩散编译
description: 说明 Jugg 在源码编译成功后如何发现受影响源码并继续补编译。
status: active
tags:
  - capability
  - compile
  - recompile
---

# 重编译/扩散编译

Jugg 支持在一轮源码编译成功后继续分析影响范围，把没有直接修改但必须重新编译的源码加入下一轮。这类行为也可以理解为重编译或扩散编译。本页只说明支持范围和可见现象，结构对比、引用索引和传播收敛机制见[重编译 / 扩散编译原理](../../concepts/incremental-compile/recompile-propagation.md)。

## 已支持能力

| 触发场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 方法签名、字段、父类或接口变化 | 支持 | 调用方、子类或实现类可能被追加编译 |
| 泛型 signature 变化 | 支持部分确定场景 | 相关声明链或直接调用方可能被追加编译 |
| Java/Kotlin 内联常量变化 | 支持 | 常量引用方可能被追加编译；详见 [常量引用分析](./const-ref.md) |
| release inline 方法变化 | 支持补偿 | release 产物会补齐旧 inline 调用方影响 |
| R8/ProGuard 删除成员影响 | 支持补偿 | release 场景下生成兼容产物 |
| 资源生成源码变化 | 支持回流 | `R.java`、DataBinding/ViewBinding 生成源码进入 [源码编译](./source-compile.md) |

> [!NOTE]
> 扩散编译是编译成功后的正常追加步骤，不代表第一次编译失败。它的目标是避免运行时仍使用旧调用方、旧常量值或旧 inline 副本。

## 触发与结果

```text
直接修改的源码编译成功
  -> 发现可能受影响的源码
  -> 追加下一轮源码编译
  -> 直到没有新的受影响文件
  -> 产物继续进入部署
```

扩散编译是编译成功后的正常追加步骤，不代表第一次编译失败。它的目标是避免设备继续运行旧调用方、旧常量值或旧 inline 副本。

## 常见用户可见现象

| 现象 | 含义 |
|---|---|
| 日志出现 `Detect effected sources` | 本轮编译后发现受影响源码 |
| 日志出现 `continue compile` | Jugg 正在追加下一轮编译 |
| 小改动触发多文件编译 | 可能是接口、父类、泛型、常量或 release inline 影响 |

## 相关页面

- [源码编译](./source-compile.md)
- [常量引用分析](./const-ref.md)
- [Release 编译](./release-compile.md)
- [编译阶段说明](../../guide/compile.md)
- [重编译 / 扩散编译原理](../../concepts/incremental-compile/recompile-propagation.md)
