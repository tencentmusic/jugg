---
title: so 更新
description: 说明 Jugg 如何处理已有 .so、C/C++ 源码和 Flutter native 产物，并通过 APK 重签名让更新生效。
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# so 更新

Jugg 支持更新已产出的 native lib / `.so` 文件。对于 Gradle 管理的 C/C++ 模块，源码变化会先执行当前变体的 native 构建任务；Flutter 混合工程产生的 native library 也会进入同一条 APK 更新流程。Jugg 随后写入目标 APK、重新签名并安装。

## 支持范围

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 更新项目目录中已有、位于 ABI 目录下的 `.so` | 支持 | 更新目标 APK，重新签名并安装 |
| 修改 Gradle 管理的 C/C++ 源码 | 支持 | 执行当前变体的 native 构建任务，再更新生成的 `.so` |
| Flutter Profile/Release 生成 `app.so` 或 native assets | 支持 | 转为目标 ABI 下的 native lib，再更新 APK |
| 同轮更新多个 ABI 的 native lib | 支持按目标 APK 归属处理 | 每个目标 APK 只接收属于自己的 native lib |
| 删除 `.so` | 不生成移除结果 | 已安装 APK 继续包含原有 native lib |
| 修改 CMake、ndk-build、NDK、ABI 或 packaging 配置 | 不作为源码增量输入 | 通过完整 Gradle 构建刷新项目模型和 APK 基线 |

## 触发与结果

```text
C/C++ 源码变化
  -> 执行当前变体的 native Gradle task
  -> 从中间产物目录收集新 .so

项目目录中已有的 .so 或 Flutter native 产物发生变化
  -> 根据 ABI 和 APK 归属确定目标路径
  -> 写入目标 APK 的 lib/<abi> 目录
  -> 对 APK 重新签名
  -> 安装更新后的 APK
```

安装完成后，App 使用更新后 APK 中的 native lib。从 C/C++ 源码生成 `.so` 仍由 Gradle、CMake 和 NDK 完成；Jugg 负责在检测到源码变化时启动对应任务，并把新产物接入既有 native lib 部署。

## 使用边界

- 直接文件变化入口只识别项目目录中已经存在、父目录为 `armeabi`、`armeabi-v7a`、`arm64-v8a`、`x86` 或 `x86_64` 的 `.so`。
- C/C++ 源码入口要求 Android Gradle 配置提供 CMake 或 ndk-build 文件，并能够找到当前变体的 native task。Jugg 不监听 `.cxx`、`.externalNativeBuild` 或 Gradle `build` 目录中的生成文件。
- 每次检测到 C/C++ 源码变化都会执行 native task；产物内容校验只避免重复写入 APK，不跳过 native 编译。
- 已识别 C/C++ 源码根但缺少任务或输出目录元数据时，Jugg 会回退完整 Gradle 构建；外部任务失败或没有生成有效 `.so` 时，本轮编译失败，不使用旧中间产物继续部署。
- 修改 CMake、ndk-build、NDK、ABI、source set 或 packaging 规则后，先完成 Sync 和完整 Gradle 构建以刷新项目模型与 APK 基线。
- 删除 `.so` 不会生成 APK 内文件的移除数据，也不会仅因此让增量编译失败。已安装 APK 继续包含原有 native lib，只有需要让删除真正生效时才执行完整 Gradle 构建。
- 多 APK 工程按目标 APK 归属更新，不会把同一份 native lib 默认写入所有 APK。
- 签名配置缺失或无效时，本轮增量 APK 更新失败；需要通过 Gradle 构建恢复可安装的 APK 基线。

## 相关页面

- [编译阶段说明](../../guide/compile.md)
- [assets 与 native lib 原理](../../concepts/incremental-compile/assets-native.md)
- [多 APK 部署](../deploy/multi-apk.md)
