---
title: so 更新
description: 说明 Jugg 对 native lib / .so 文件的增量更新与 APK 重签名生效方式。
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# so 更新

Jugg 支持更新已产出的 native lib / `.so` 文件，并通过写入 APK、重新签名后安装生效。

## 可更新的 so 产物

| 场景 | 当前支持情况 | 生效方式 |
|---|---|---|
| 更新已有 `jniLibs/**/*.so` 或 `lib/<abi>/*.so` 产物 | 支持 | 写入目标 APK，并对 APK 重新签名 |
| 多 ABI native lib 更新 | 支持按目标 APK 归属处理 | 每个目标 APK 更新自己的 native lib 产物 |

> [!TIP]
> 如果变更的是 C/C++ 源码、CMake、NDK、ABI、packaging 规则或 native source set，先完成对应 Gradle 构建或 Sync，让新的 `.so` 产物成为 Jugg 可更新的输入。

## so 如何更新

```text
发现 native lib / .so 产物变化
  -> 归属到目标 APK
  -> 部署阶段写入 APK
  -> 对 APK 重新签名
  -> 安装更新后的 APK
```

`.so` 更新需要修改 APK 内容并重新签名。Jugg 负责把已有 `.so` 产物放入正确 APK；C/C++ 源码到 `.so` 的产物生成仍由 Gradle/NDK 完成。

## 关联能力

- [AndroidManifest 编译](./manifest.md)
- [资源编译](./resource-compile.md)
- [编译阶段说明](../../guide/compile.md)
