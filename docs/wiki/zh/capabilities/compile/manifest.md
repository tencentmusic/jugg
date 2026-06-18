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

Jugg 支持对 `AndroidManifest.xml` 做增量编译。它不会重新完整运行 Gradle 的 Manifest merge，而是在最近一次构建得到的 merged manifest 上应用本轮变化。本页说明支持范围和用户可见结果，merged manifest patch 的机制见 [Android Manifest 编译与 release 增量编译](../../concepts/incremental-compile/manifest-minify.md)。

## Manifest 变化的处理方式

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 普通 AndroidManifest 节点或属性变化 | 支持增量 patch | 更新 APK 并重签名后生效 |
| AndroidManifest 参与资源 link | 支持 | 与资源产物一起进入部署判断 |
| 无真实变化的 AndroidManifest | 自动过滤 | 不触发无意义 APK 更新 |

> [!TIP]
> 如果变更依赖 Gradle placeholder 来源、variant 合并规则或构建脚本生成逻辑，先完成对应 Gradle 构建或 Sync，让新的 merged manifest 成为基线。

## 触发与结果

```text
AndroidManifest.xml 变化
  -> 基于最近一次 merged manifest 生成结果
  -> 需要时更新 APK 并重签名
  -> 安装更新后的 APK
```

AndroidManifest 编译的关键点是：Jugg 增量处理的是已经合并过的 manifest 基线，而不是重新接管完整 Gradle Manifest merge。

## 使用边界

- 修改 Gradle placeholder 来源、variant 合并规则或构建脚本生成逻辑时，先完成对应 Gradle 构建或 Sync。
- Manifest 变化会进入 APK 更新路径，不等同于普通资源 overlay。
- 签名配置不可用时，APK 更新无法完成，需要回到更保守的部署路径。

## 关联能力

- [资源编译](./resource-compile.md)
- [so 更新](./so-update.md)
- [编译阶段说明](../../guide/compile.md)
- [Android Manifest 编译与 release 增量编译](../../concepts/incremental-compile/manifest-minify.md)
