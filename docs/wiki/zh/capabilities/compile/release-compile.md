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

Jugg 支持 release / minified 场景下的增量编译。它会基于最近一次构建生成的 `mapping.txt` 和可选 `usage.txt`，让增量 class/dex 与已安装 APK 的混淆结果保持一致。本页说明支持范围和风险边界，mapping 对齐、inline 和 `_jugg_fix` 补偿机制见 [Android Manifest 编译与 release 增量编译](../../concepts/incremental-compile/manifest-minify.md)。

## 已支持能力

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 已混淆 class/dex 增量 | 支持 | 增量产物尽量对齐已安装 APK 的混淆名称 |
| R8 inline 方法影响 | 支持补偿 | 旧 inline 调用方影响会进入补偿判断 |
| R8/ProGuard 删除成员 | 支持部分补偿 | release 场景下生成兼容产物 |
| 缺失 mapping | 降级继续 | 打印 warning，按未混淆产物继续，运行时一致性保障变弱 |

> [!IMPORTANT]
> release 增量依赖完整且匹配当前 APK 的 mapping 基线。升级 R8/ProGuard、修改 keep 规则、出现反射/注解/类型引用异常时，优先执行一次 Gradle release 构建验证。

## 触发与结果

```text
release / minified 产物变化
  -> 尝试按当前 APK 的 mapping 基线对齐
  -> 补偿 inline 或删除成员影响
  -> 产物交给部署阶段
```

用户需要关注的是：release 增量更依赖基线新鲜度。mapping、keep 规则或 R8 行为与当前 APK 不一致时，Jugg 会按当前可用信息降级继续；结果需要用 Gradle release 构建对照。

## 使用边界

- `mapping.txt` 缺失时不会硬失败，但运行时一致性保障会变弱。
- `usage.txt` 主要用于被删除方法的 compatibility stub；字段删除当前更多作为影响分析信号。
- 如果 release 增量后出现 `NoClassDefFoundError`、`NoSuchMethodError`、`IllegalAccessError`、注解查找失败等，先保留日志并用 Gradle release 构建对照。

## 关联能力

- [常量引用分析](./const-ref.md)
- [AabResGuard](./aab-resguard.md)
- [编译问题排查](../../troubleshooting/compile.md)
- [Android Manifest 编译与 release 增量编译](../../concepts/incremental-compile/manifest-minify.md)
