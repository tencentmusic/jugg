---
title: APK 更新与安装
description: 解释 Manifest、resources.arsc 和 native lib 为什么需要写回 APK，以及 Jugg 如何在安装后继续应用本轮增量产物。
status: active
tags:
  - concept
  - deploy
  - apk
---

# APK 更新与安装

Android 系统从已安装 APK 读取 Manifest、native lib 和部分打包信息。这些内容不能只写进 Apply Changes overlay。Jugg 增量编译得到对应变化后，会修改最近一次可信的 Gradle APK、重新签名并安装，让系统重新读取安装包中的结果。

这仍然属于增量部署：Jugg 复用已有 APK，只替换已经生成的局部文件，不重新执行完整 Gradle 构建。安装完成后，同一轮还可以继续应用 class、资源和 assets overlay。

## 哪些产物需要进入 APK

| 本轮产物 | 为什么需要更新 APK | 后续结果 |
|---|---|---|
| `AndroidManifest.xml` patch | 系统从安装包读取组件、权限和其它 Manifest 信息 | 写回目标 APK，重新签名并安装 |
| 与 Manifest 配套的 `resources.arsc` | Manifest 中的资源引用必须和资源表保持一致 | 与 Manifest 一起更新 APK |
| 已经生成的 native lib | 系统和 linker 从安装包或安装目录加载 `.so` | 写回对应 APK 后重新安装 |
| 普通 class、`res/**`、`assets/**` | 可以由增量 overlay 承载 | 安装后继续通过 Apply Changes 或 Hot Fix 下发 |

Jugg 只能写入已经由当前增量流程生成的文件。C/C++ 源码编译、ABI 变化、packaging 配置变化或完整 Manifest merge 超出当前增量结果时，仍需 Gradle 重新生成 APK。

## 更新 APK 需要可用的签名配置

Android 不接受内容被修改但签名未更新的 APK。Jugg 写入目标文件后，会使用当前工程的签名配置重新签名。签名配置缺失或无效时，APK 更新会明确失败，并把 Gradle 回退资格交给 Run 层。

多 APK 工程会根据产物的真实归属修改对应的 base、split 或 test APK。一个产物只写入它所属的 APK，不会为了方便全部塞进 base APK。

## 安装会建立新的设备基线

更新后的 APK 已经不再等同于设备上的旧安装。本轮不能继续假设旧 deployment cache 和 overlay ID 仍然有效，因此部署流程会进入状态恢复并安装更新后的 APK。

```text
生成 Manifest 或 native lib 增量产物
  -> 写入最近的 Gradle APK
  -> 使用工程签名重新签名
  -> 安装更新后的 APK
  -> 重建 deployment cache 与设备 checkpoint
  -> 重新生成待下发的 class 和 overlay
  -> 完成本轮剩余增量部署
```

安装替换了旧进程和旧 overlay 基线。Jugg 会清理已经不能继续复用的部署文件状态，再根据本轮编译结果重新组织剩余数据。这样 Manifest 或 native lib 与普通 class、资源可以在一次 Run 中共同生效。

## APK 更新和 Gradle 回退的区别

两条路径都会安装 APK，但重新生成 APK 的范围不同。

| 路径 | 复用内容 | 适用场景 |
|---|---|---|
| Jugg APK 更新 | 最近一次可信 Gradle APK，以及本轮已经生成的局部文件 | Manifest patch、已有 native lib 等可确定性写回的变化 |
| Gradle 构建后安装 | 重新执行构建、打包和签名流程 | 构建脚本、依赖、C/C++ 编译、ABI 或 packaging 结果需要刷新 |

Recover 触发的重新安装也不等于 Gradle 回退。它通常安装当前已有 APK，用于修复设备状态；只有构建基线本身不可信时，才需要回到 Gradle。

## 相关页面

- [增量部署总览](./deploy-strategy.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [Android Manifest 编译](./incremental-compile/manifest.md)
- [assets 与 native lib](./incremental-compile/assets-native.md)
- [部署状态与恢复](./deploy-state-recover.md)
- [Gradle 回退与基线重建](./gradle-fallback-baseline.md)
- [Clean Reinstall 能力](../capabilities/deploy/clean-reinstall.md)
