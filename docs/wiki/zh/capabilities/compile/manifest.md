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

Jugg 支持对 `AndroidManifest.xml` 做增量编译。它不会重新完整运行 Gradle 的 Manifest merge，而是在最近一次构建得到的 merged manifest 上应用本轮变化。本页说明支持范围和用户可见结果，merged manifest patch 的机制见 [Android Manifest 编译](../../concepts/incremental-compile/manifest.md)。

## 支持范围

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 新增节点或更新属性 | 支持增量 patch | 更新 APK 并重签名后生效 |
| 删除节点、删除属性或 `tools:remove` / `tools:replace` | 不生成对应的移除或完整 merge 结果 | 已安装 APK 继续使用原有 merged manifest 内容 |
| Manifest 与资源同时变化 | 支持同轮处理 | 更新后的 Manifest 和相关资源产物一起写入 APK |
| 无真实变化的 AndroidManifest | 自动过滤 | 不触发无意义 APK 更新 |

> [!TIP]
> 如果变更依赖 Gradle placeholder 来源、variant 合并规则或构建脚本生成逻辑，工程模型变化时先完成 Sync，再执行对应 Gradle 构建生成新的 merged manifest 基线。

## 触发与结果

```text
AndroidManifest.xml 变化
  -> 在最近一次 merged manifest 上应用可确定的新增和更新
  -> 需要时更新 APK 并重签名
  -> 安装更新后的 APK
```

没有产生真实 patch 时，Jugg 不会更新 APK。完整的 merged manifest patch 和资源 link 机制见 [Android Manifest 编译](../../concepts/incremental-compile/manifest.md)。

## 使用边界

- 修改 Gradle placeholder 来源、variant 合并规则或构建脚本生成逻辑时，先执行对应 Gradle 构建；工程模型同时变化时先完成 Sync。
- 删除节点、删除属性以及依赖完整 merge 上下文的 `tools:*` 指令会被忽略，不会让本轮增量编译失败；已安装 APK 仍保留原有 merged manifest 内容。只有需要让删除真正生效时，才执行完整 Gradle 构建。
- `uses-sdk`、manifest `package`、`versionCode`、`versionName` 和 application `android:name` 不会由增量 patch 更新。
- Manifest 变化会进入 APK 更新路径，不等同于普通资源 overlay。
- 签名配置缺失或无效时，本轮增量 APK 更新失败；需要通过 Gradle 构建恢复可安装的 APK 基线。

## 相关页面

- [资源编译](./resource-compile.md)
- [编译阶段说明](../../guide/compile.md)
- [Android Manifest 编译](../../concepts/incremental-compile/manifest.md)
