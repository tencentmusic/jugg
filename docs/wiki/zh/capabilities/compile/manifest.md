---
title: AndroidManifest 编译
description: 说明 Jugg 对 AndroidManifest.xml 的增量编译与 APK 重签名生效方式。
status: active
tags:
  - capability
  - compile
  - manifest
---

# AndroidManifest 编译

Jugg 支持对 `AndroidManifest.xml` 做增量编译。它不会重新完整运行 Gradle 的 Manifest merge，而是在最近一次构建得到的 merged manifest 上应用本轮变化。

## Manifest 变化的处理方式

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 普通 AndroidManifest 节点或属性变化 | 支持增量 patch | patch 到 merged manifest 后，写入 APK 并重签名 |
| AndroidManifest 参与资源 link | 支持 | 作为 aapt2 link 输入，和 `resources.arsc` 一起进入部署产物 |
| 无真实变化的 AndroidManifest | 自动过滤 | 不输出根 `AndroidManifest.xml`，避免无意义 APK 更新 |

> [!TIP]
> 如果变更依赖 Gradle placeholder 来源、variant 合并规则或构建脚本生成逻辑，先完成对应 Gradle 构建或 Sync，让新的 merged manifest 成为基线。

## AndroidManifest 如何生效

```text
发现 AndroidManifest.xml 变化
  -> 读取最近一次 merged manifest
  -> 对本轮变化做 diff / patch
  -> 作为资源 link 输入参与产物生成
  -> 部署阶段写入 APK
  -> 对 APK 重新签名并安装更新后的 APK
```

AndroidManifest 编译的关键点是：Jugg 增量处理的是已经合并过的 manifest 基线，而不是重新接管完整 Gradle Manifest merge。

## 关联能力

- [资源编译](./resource-compile.md)
- [so 更新](./so-update.md)
- [编译阶段说明](../../guide/compile.md)
