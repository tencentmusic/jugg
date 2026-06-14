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

Jugg 支持在一轮源码编译成功后继续分析影响范围，把没有直接修改但必须重新编译的源码加入下一轮。这类行为也可以理解为重编译或扩散编译。

## 已支持能力

| 触发场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 方法签名、字段、父类或接口变化 | 支持 | 通过历史 class 引用索引找到调用方和子类 |
| 泛型 signature 变化 | 支持部分确定场景 | 对声明链和 direct member caller 做扩散 |
| Java/Kotlin 内联常量变化 | 支持 | 通过 [常量引用分析](./const-ref.md) 找到引用方源码 |
| release inline 方法变化 | 支持补偿 | 通过 mapping 找到旧 inline 调用方 |
| R8/ProGuard 删除成员影响 | 支持补偿 | 通过 release/minify 链路生成兼容产物 |
| 资源生成源码变化 | 支持回流 | `R.java`、DataBinding/ViewBinding 生成源码进入 [源码编译](./source-compile.md) |

> [!NOTE]
> 扩散编译是编译成功后的正常追加步骤，不代表第一次编译失败。它的目标是避免运行时仍使用旧调用方、旧常量值或旧 inline 副本。

## 扩散编译如何生效

```text
直接修改的源码编译成功
  -> 解析新旧 class / dex 差异
  -> 查询历史引用索引和子类关系
  -> 查询常量引用影响
  -> release 场景补充 inline / minify 补偿
  -> 找到受影响源码
  -> 作为下一轮源码输入继续编译
  -> 直到没有新的受影响文件
```

扩散编译不会在编译阶段直接提交长期状态。只有后续部署成功后，部署历史、class 索引和资源状态才会更新。

## 常见用户可见现象

| 现象 | 含义 |
|---|---|
| 日志出现 `Detect effected sources` | 本轮编译后发现受影响源码 |
| 日志出现 `continue compile` | Jugg 正在追加下一轮编译 |
| 小改动触发多文件编译 | 可能是接口、父类、泛型、常量或 release inline 影响 |

## 关联能力

- [源码编译](./source-compile.md)
- [常量引用分析](./const-ref.md)
- [Release 编译](./release-compile.md)
