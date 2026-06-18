---
title: 常量引用分析
description: 说明 Jugg 如何识别 Java/Kotlin 内联常量变化并触发引用方源码补编译。
status: active
tags:
  - capability
  - compile
  - const
---

# 常量引用分析

Jugg 支持 Java / Kotlin 内联常量的影响分析。当 `static final` 或 `const val` 的真实值发生变化时，Jugg 会查找可能引用该常量的源码文件，并触发补编译。本页只说明支持范围和结果，为什么普通结构传播覆盖不到常量内联见[常量引用分析原理](../../concepts/incremental-compile/const-ref.md)。

## 已支持能力

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| Java 可内联 `static final` 常量 | 支持 | 值变化后，可能引用旧值的源码会被补编译 |
| Kotlin `const val` | 支持 | top-level、object、companion、嵌套 class/object 等常见形式可触发补编译 |
| 常量删除或 `const -> val` | 支持 | 旧引用方仍可能被补编译，避免继续携带旧字面量 |
| 跨 worktree 缓存 | 支持 | 多个 worktree 可以复用分析结果，减少重复等待 |
| 分析未就绪 | 降级继续 | 打印 warning，使用已完成缓存或空结果，不阻断主流程 |

> [!NOTE]
> 常量引用分析遵循“宁可多编译，不能漏编译”的原则。它用 syntax-only 引用候选做保守匹配，不要求引用扫描时目标常量已经被扫描完成。

## 触发与结果

```text
内联常量真实值变化
  -> 查找可能引用该常量的源码
  -> 把引用方加入后续源码编译
  -> 编译产物继续进入部署
```

常量引用分析遵循“宁可多编译，不能漏编译”的原则。它可能让一次看似很小的常量修改追加编译多个使用方。

## 支持边界

- Java 侧只处理可内联类型的 `static final` 字段。
- Kotlin 侧重点处理 `const val`，普通 `val` 不按内联常量处理。
- 注释和字符串里的伪引用会被忽略。
- 分析缓存异常或超时时不会中断编译，但可能降低本轮补编译覆盖度。

## 关联能力

- [重编译/扩散编译](./recompile-propagation.md)
- [Release 编译](./release-compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [常量引用分析原理](../../concepts/incremental-compile/const-ref.md)
