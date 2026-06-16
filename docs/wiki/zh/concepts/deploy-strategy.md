---
title: 部署策略
description: 说明 Jugg 如何在热重载、热修复和 APK 更新之间选择部署方式。
status: active
tags:
  - concept
  - deploy
---

# 部署策略

Jugg 增量编译通常不会生成完整 APK，只会生成 DEX、资源、assets 等局部产物。部署阶段要把这些产物应用到设备，并让改动生效。

Jugg 使用混合部署策略。能在线更新的内容优先走 Apply Changes / JVMTI；需要改 APK 内容的产物走 update apk；不适合在线热重载的 class 走热修复。设备状态不可信时，会先 recover 或重装来恢复基线。

## 热重载：Apply Changes / JVMTI

Apply Changes 使用 JVMTI 的类重定义能力，可以在运行时替换 class 实现。Jugg 复用这条通道，向 Android Studio deployer 提供上次部署 ID、新类字节码、可热重载类字节码和资源 overlay 等数据。

这个路径适合结构未变的 class 修改，以及可以通过 overlay 生效的资源或 assets 变化。它的限制也来自 JVMTI：删除方法、修改方法签名、修改字段等结构变化，通常不能在线热重载。

部署时，`JuggDeployData` 会把结构未变的 class 放入 `hotReloadModifiedClasses`。如果本轮不需要重启 App，部署任务会选择 `APPLY_CHANGES`；如果需要重建 Activity，则使用 `APPLY_CHANGES_AND_RESTART_ACTIVITY`。

## APK 更新：update apk

有些产物不能靠普通 overlay 生效。Manifest、配套的 `resources.arsc`、native lib 会进入 `updateApkFiles`。

```text
JuggDeployData.updateApkFiles 非空
  -> IncrementalDeployHelper.updateApk()
  -> 把文件写入目标 APK
  -> 重新签名 APK
  -> recoverDeployState(isInstallUpdateApk=true)
  -> 安装更新后的 APK
```

update apk 不是完整 Gradle 构建。它基于当前 APK，把本轮必须写回 APK 的文件插进去并重签名。签名配置缺失时，这条路径会失败并允许回退。

## 热修复

热修复是在 App 启动时插入新的 DEX、native lib 或资源路径，让新产物优先生效。它覆盖面比在线 class redefine 更宽，但通常需要重启 App。

典型做法包括：

- 把新的 DEX 插入 `BaseDexClassLoader` 的 `pathList.dexElements` 前面。
- 把新的 native lib 路径插入 `pathList.nativeLibraryPathElements`。
- 构造新的 `AssetManager`，通过 `addAssetPath` 加载资源包，并更新 `ResourcesManager` 中的资源引用。

Jugg 会把不适合热重载的 class 放入 hot fix 路径。发生 JVMTI 不兼容、class redefine 失败或用户启用兼容部署时，也可能从热重载退到热修复。

## Jugg 的混合策略

判断逻辑如下：

```text
编译产物成为 JuggDeployData
  -> hotReloadModifiedClasses 非空：优先 Apply Changes / JVMTI
  -> hotFixModifiedClasses 非空：重启 App 后走热修复
  -> updateApkFiles 非空：写回 APK、重签名并安装
  -> 设备状态不匹配：recover，必要时重装
```

实际部署时，这三类可以出现在同一轮数据里。Jugg 会先处理需要 update apk 的产物，保证 APK 内容和签名已经更新；再根据 class 和 overlay 数据选择 Apply Changes、重建 Activity、重启 App 或兼容热修复。retry 过程中如果检测到 JVMTI / Apply Changes 兼容问题，会把可热重载数据转换为 HOT_FIX 再部署。

## 相关页面

- [部署数据与影响分析](./deploy-data-and-impact.md)
- [JVMTI Agent](./jvmti-agent.md)
- [回退与限制](./fallback-and-limits.md)
- [Android Manifest 编译与 release 增量编译](./incremental-compile/manifest-minify.md)
- [assets 与 native lib](./incremental-compile/assets-native.md)
