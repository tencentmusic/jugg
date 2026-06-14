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

Jugg 支持 Java / Kotlin 内联常量的影响分析。当 `static final` 或 `const val` 的真实值发生变化时，Jugg 会查找可能引用该常量的源码文件，并触发补编译。

## 已支持能力

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| Java 可内联 `static final` 常量 | 支持 | 记录定义和引用候选，值变化后查找引用方 |
| Kotlin `const val` | 支持 | 覆盖 top-level、object、companion、嵌套 class/object 等常见形式 |
| 常量删除或 `const -> val` | 支持 | 使用 removed definition key 命中旧引用候选 |
| 跨 worktree 缓存 | 支持 | 通过共享 DB 和 fingerprint 减少重复解析 |
| 分析未就绪 | 降级继续 | 打印 warning，使用已完成缓存或空结果，不阻断主流程 |

> [!NOTE]
> 常量引用分析遵循“宁可多编译，不能漏编译”的原则。它用 syntax-only 引用候选做保守匹配，不要求引用扫描时目标常量已经被扫描完成。

## 常量引用如何触发补编译

```text
源码保存或编译前 flush
  -> 分析常量定义和引用候选
  -> 编译成功后比较真实变化的 definition key
  -> 查询引用方源码路径
  -> 写入 constRefEffectedSourcePaths
  -> 下一轮继续编译这些源码
```

Jugg 不会把常量影响混入普通 class 结构影响集合；它有独立的 `constRefEffectedSourcePaths`，最后统一进入继续编译链路。

## 支持边界

- Java 侧只处理可内联类型的 `static final` 字段。
- Kotlin 侧重点处理 `const val`，普通 `val` 不按内联常量处理。
- 注释和字符串里的伪引用会被忽略。
- 分析缓存异常或超时时不会中断编译，但可能降低本轮补编译覆盖度。

## 关联能力

- [重编译/扩散编译](./recompile-propagation.md)
- [Release 编译](./release-compile.md)
- [编译问题排查](../../troubleshooting/compile.md)
