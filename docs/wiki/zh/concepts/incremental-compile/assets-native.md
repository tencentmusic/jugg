---
title: assets 与 native lib
description: 解释 Gradle 与 Jugg 如何处理 assets 和 native lib，以及两类增量产物为何采用不同的生效方式。
status: active
tags:
  - concept
  - compile
  - assets
  - native
---

# assets 与 native lib

Android APK 不只包含经过 aapt2 编译的资源。`assets/` 会按原有目录结构进入 APK，native lib 则以按 ABI 划分的 `.so` 文件进入 APK。Jugg 复用最近一次 Gradle 构建的 APK，只组织本轮变化文件，不重新执行完整打包流程。

## Gradle 如何把文件放进 APK

完整 Android 构建会根据输入类型生成不同的 APK 内容：

| 输入 | 标准构建过程 | APK 中的产物 |
|---|---|---|
| `res/` | aapt2 编译并链接资源 | 编译后资源和 `resources.arsc` |
| `assets/` | AGP 合并 asset 目录并参与 APK 打包 | `assets/**` |
| C/C++ 源码或预编译 `.so` | Gradle/NDK 生成或收集各 ABI 的动态库，并参与 APK 打包 | `lib/<abi>/*.so` |

`assets/` 不会像 `res/` 一样生成资源 ID，也不进入 `resources.arsc`。native lib 是已经编译完成的二进制文件，同样不属于 Android 资源表。完整构建仍会收集这些文件，并把它们放到 APK 约定的路径中。

## Jugg 如何组织本轮增量产物

Jugg 以 Gradle APK 为基线，检测变化文件后保留它们在 APK 中的相对路径：

```text
assets 变化文件
  -> 保留 assets 下的相对路径
  -> 生成归属于目标 APK 的 asset 增量产物

已经生成的 .so 变化
  -> 保留 ABI 和 lib 下的相对路径
  -> 生成归属于目标 APK 的 native lib 增量产物
```

这个过程只复制并组织变化文件，不执行 aapt2，也不会生成 `resources.arsc`。对于 native lib，Jugg 的输入是已经生成的 `.so`；从 C/C++ 源码到 `.so` 的编译仍由 Gradle、CMake 和 NDK 完成。

多 APK 工程中，每份产物还必须保留自己的目标 APK 归属。Jugg 不会把同一份 asset 或 native lib 默认复制到所有 APK。

## 加载方式决定生效方式

生成增量产物后，Jugg 还要根据 Android 运行时读取文件的方式选择部署路径：

```text
asset 增量产物
  -> 作为目标 APK 的 overlay 下发
  -> 运行时通过 AssetManager 读取新文件

native lib 增量产物
  -> 写回目标 APK 的 lib/<abi> 目录
  -> 重新签名并安装更新后的 APK
```

asset overlay 会保持 `assets/**` 路径，供新的资源加载路径读取。普通 asset 或资源 overlay 不会成为 APK 的 native library 搜索目录，因此当前 `.so` 更新路径会修改目标 APK，而不是把 `.so` 当作 asset overlay 下发。

## 需要回到 Gradle 的情况

- 删除 asset 文件时，Jugg 不会生成移除设备端文件的 overlay；原有 asset 仍可通过 `AssetManager` 读取。只有需要让删除真正生效时，才执行完整 Gradle 构建。
- 修改 C/C++ 源码、CMake、NDK、ABI、native source set 或 packaging 规则后，需要先由 Gradle/NDK 生成新的 `.so`。
- 修改 asset source set、variant 或影响 APK 路径与归属的构建配置后，需要刷新 Gradle 基线。
- native lib 更新依赖可用的 APK 签名配置；无法完成重签名时，不能继续使用这条增量更新路径。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
- [so 更新能力](../../capabilities/compile/so-update.md)
- [多 APK 部署](../../capabilities/deploy/multi-apk.md)
- [部署策略](../deploy-strategy.md)
