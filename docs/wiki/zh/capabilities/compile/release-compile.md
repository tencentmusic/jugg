---
title: Release 编译
description: 说明 Jugg 在 release / minified 场景下如何保持 mapping 一致、处理 inline 和删除成员补偿。
status: active
tags:
  - capability
  - compile
  - release
  - minify
---

# Release 编译

Jugg 支持 release / minified 场景下的增量编译。它会基于最近一次构建生成的 `mapping.txt` 和可选 `usage.txt`，让增量 class/dex 与已安装 APK 的混淆结果保持一致。本页说明支持范围和风险边界，mapping 对齐、inline 和 `_jugg_fix` 补偿机制见 [release 增量编译](../../concepts/incremental-compile/release-compile.md)。

> [!WARNING]
> Release 编译是实验性能力，尚未经过大规模实际工程验证。使用时可能出现改动未生效或运行时 crash；如果遇到问题，请提供可复现 Demo 并提交 issue。

## 已支持能力

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 已混淆 class/dex 增量 | 支持 | 增量产物尽量对齐已安装 APK 的混淆名称 |
| R8 inline 方法影响 | 支持补偿 | 旧 inline 调用方影响会进入补偿判断 |
| R8/ProGuard 删除成员 | 支持部分补偿 | release 场景下生成兼容产物 |
| 缺失 mapping | 不进入重新混淆 | 按普通 DEX 路径输出，不能保证与已混淆 APK 对齐 |

## 触发与结果

```text
release / minified 产物变化
  -> 尝试按当前 APK 的 mapping 基线对齐
  -> 补偿 inline 或删除成员影响
  -> 产物交给部署阶段
```

Jugg 只根据当前可用的 mapping 执行名称重映射，不会重新运行完整 R8 来核对 keep 规则和优化结果。mapping、keep 规则或 R8 行为与当前 APK 不一致时，编译可能完成，但改动可能不生效或引发运行时 crash。

## 使用边界

- `mapping.txt` 缺失时不会进入重新混淆，输出不能可靠部署到已混淆 APK。
- `usage.txt` 主要用于被删除方法的 compatibility stub；字段删除当前更多作为影响分析信号。
- 如果 release 增量后出现 `NoClassDefFoundError`、`NoSuchMethodError`、`IllegalAccessError`、注解查找失败等，请保留日志，提供可复现 Demo 并提交 issue。

## 相关页面

- [重编译/扩散编译](./recompile-propagation.md)
- [AabResGuard](./aab-resguard.md)
- [编译失败](../../troubleshooting/compile-failed.md)
- [部署后 App 崩溃](../../troubleshooting/runtime-crash.md)
- [release 增量编译](../../concepts/incremental-compile/release-compile.md)
