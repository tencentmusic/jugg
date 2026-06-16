---
title: assets 与 native lib
description: 说明 Jugg 如何分别处理 assets 文件和 native lib 更新。
status: active
tags:
  - concept
  - compile
  - assets
  - native
---

# assets 与 native lib

`assets/` 和 native lib 都不需要 aapt2，也不属于 Java/Kotlin 源码编译。但它们的部署方式不同：assets 是普通 overlay；native lib 需要写回 APK。

## assets

`assets/` 文件不需要资源表。Jugg 只负责把变化文件复制成可部署产物，并保留目标 APK 归属。

```text
assets 变化文件
  -> AssetOverlayCompiler
  -> 输出 assets overlay
  -> 写入 staging
  -> 交给部署阶段消费
```

assets 变化不触发 aapt2 compile/link，也不会生成 `resources.arsc`。多 APK 场景下，输出仍需要带上 APK scoped 归属，避免把 assets 下发到错误 APK。

## native lib

native lib 也由 `AssetOverlayCompiler` 识别，但它不会像 assets 一样作为普通 overlay 生效。部署数据生成时，native lib 会进入 `updateApkFiles`。部署阶段把 `.so` 写入目标 APK、重新签名，再安装更新后的 APK。

```text
native lib / .so 变化
  -> AssetOverlayCompiler 输出 NativeLib
  -> DeployDataGenerator 放入 updateApkFiles
  -> IncrementalDeployHelper 写回 APK 并重签名
  -> 部署阶段按 update apk 模式安装更新后的 APK
```

C/C++ 源码、CMake、NDK、ABI 或 packaging 规则的编译仍由 Gradle/NDK 完成。Jugg 处理的是已经产出的 `.so` 文件。

## 与资源编译的区别

| 类型 | 是否需要 aapt2 | 是否生成资源表 | 主要输出 |
|---|---|---|---|
| `res/` | 是 | 是 | `resources.arsc`、compiled res、可选 `R.java` |
| `assets/` | 否 | 否 | assets overlay |
| native lib | 否 | 否 | update apk 文件 |

## 约束

- assets 变化不应触发资源 link。
- native lib 更新需要 update apk；不能按普通 assets overlay 理解。
- 输出必须保留 APK scoped 归属；不能默认复制给所有 APK。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [so 更新能力](../../capabilities/compile/so-update.md)
- [多 APK 部署](../../capabilities/deploy/multi-apk.md)
