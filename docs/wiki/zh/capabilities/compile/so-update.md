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

## 支持范围

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 更新项目目录中已有、位于 ABI 目录下的 `.so` | 支持 | 更新目标 APK，重新签名并安装 |
| 同轮更新多个 ABI 的 native lib | 支持按目标 APK 归属处理 | 每个目标 APK 只接收属于自己的 native lib |
| 删除 `.so` | 不生成移除结果 | 已安装 APK 继续包含原有 native lib |
| 修改 C/C++ 源码、CMake、NDK、ABI 或 packaging 配置 | 不直接增量编译 | 通过 Gradle/NDK 构建刷新 APK 基线 |

## 触发与结果

```text
项目目录中已有的 .so 发生变化
  -> 根据 ABI 和 APK 归属确定目标路径
  -> 写入目标 APK 的 lib/<abi> 目录
  -> 对 APK 重新签名
  -> 安装更新后的 APK
```

安装完成后，App 使用更新后 APK 中的 native lib。Jugg 只负责组织和部署已经生成的 `.so`，从 C/C++ 源码生成 `.so` 仍由 Gradle、CMake 和 NDK 完成。

## 使用边界

- 直接文件变化入口只识别项目目录中已经存在、父目录为 `armeabi`、`armeabi-v7a`、`arm64-v8a`、`x86` 或 `x86_64` 的 `.so`。
- Gradle `build` 目录不会作为直接增量输入。修改 C/C++ 源码或 native 构建配置时，执行对应 Gradle 构建刷新 APK；工程模型同时变化时先完成 Sync。
- 删除 `.so` 不会生成 APK 内文件的移除数据，也不会仅因此让增量编译失败。已安装 APK 继续包含原有 native lib，只有需要让删除真正生效时才执行完整 Gradle 构建。
- 多 APK 工程按目标 APK 归属更新，不会把同一份 native lib 默认写入所有 APK。
- 签名配置缺失或无效时，本轮增量 APK 更新失败；需要通过 Gradle 构建恢复可安装的 APK 基线。

## 相关页面

- [编译阶段说明](../../guide/compile.md)
- [assets 与 native lib 原理](../../concepts/incremental-compile/assets-native.md)
- [多 APK 部署](../deploy/multi-apk.md)
