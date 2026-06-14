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

Jugg 支持 release / minified 场景下的增量编译。它会读取最近一次构建生成的 `mapping.txt` 和可选 `usage.txt`，让增量 class/dex 与已安装 APK 的混淆结果保持一致。

## 已支持能力

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 已混淆 class/dex 增量 | 支持 | 按 `mapping.txt` 重写类、字段和方法名 |
| R8 inline 方法影响 | 支持补偿 | 从 mapping 识别 inline 调用方，生成补偿 dex |
| R8/ProGuard 删除成员 | 支持部分补偿 | 通过 `usage.txt` 生成 `_jugg_fix` compatibility stub |
| 缺失 mapping | 降级继续 | 打印 warning，按未混淆产物继续 |

> [!IMPORTANT]
> release 增量依赖完整且匹配当前 APK 的 mapping 基线。升级 R8/ProGuard、修改 keep 规则、出现反射/注解/类型引用异常时，优先执行一次 Gradle release 构建验证。

## Release 增量如何生效

```text
源码编译生成未混淆 class / dex
  -> 读取 mapping.txt
  -> 先临时混淆以查询已安装 APK 的类索引
  -> 分析 inline 和被删除成员影响
  -> 生成普通重混淆 dex 和可选 _jugg_fix dex
  -> 交给部署阶段
```

`_jugg_fix` 的目标是给 release 场景提供兼容桥接：类声明名带 `_jugg_fix` 后缀，但内部引用仍指向 APK 中的混淆类名。

## 使用边界

- `mapping.txt` 缺失时不会硬失败，但运行时一致性保障会变弱。
- `usage.txt` 主要用于被删除方法的 compatibility stub；字段删除当前更多作为影响分析信号。
- 如果 release 增量后出现 `NoClassDefFoundError`、`NoSuchMethodError`、`IllegalAccessError`、注解查找失败等，先保留日志并用 Gradle release 构建对照。

## 关联能力

- [常量引用分析](./const-ref.md)
- [AabResGuard](./aab-resguard.md)
- [编译问题排查](../../troubleshooting/compile.md)
