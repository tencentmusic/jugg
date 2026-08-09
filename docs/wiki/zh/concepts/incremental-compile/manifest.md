---
title: Android Manifest 编译
description: 解释 Manifest 如何从 merged manifest 增量 patch 进入 aapt2 link，并生成可写回 APK 的二进制产物。
status: active
tags:
  - concept
  - compile
  - manifest
---

# Android Manifest 编译

Manifest 增量编译包含两个不同阶段：Jugg 先在最近一次 merged manifest 上应用本轮能够确定的变化，再把结果作为 aapt2 link 的 Manifest 输入。前一阶段生成可继续合并的 XML，后一阶段才生成能够写回 APK 的二进制 `AndroidManifest.xml`。

## Manifest 在资源编译阶段的位置

完整 Android 资源构建通常先把 `res/` 文件编译成 `.flat`，再由 aapt2 link 结合 Manifest、`android.jar` 和依赖资源生成资源表及其他 APK 资源产物。Manifest 不会像 layout 或 drawable 一样先编译成 `.flat`，而是在 link 阶段通过 Manifest 参数直接参与输出。

Jugg 保留了这组输入关系，只把完整资源 link 换成基于当前 APK 资源表的增量 link：

```text
本轮 Manifest 变化
  -> 在 merged manifest 基线上生成增量合并 XML
  -> 加载目标 APK 当前的资源表和 android.jar
  -> 与本轮变化资源的 .flat 一起进入 aapt2 inclink
  -> 生成二进制 AndroidManifest.xml、resources.arsc 和必要的 R.java
  -> 过滤本轮不需要部署的资源产物
  -> 将 Manifest 写回 APK、重新签名并安装更新
```

只有 Manifest 变化、没有普通资源变化时，这条链路仍会执行 aapt2 link：`.flat` 输入可以为空，增量合并 XML 仍通过 Manifest 参数进入 link。反过来，只有资源变化而 Manifest 没有变化时，link 可以继续生成资源产物，但最终会过滤根目录的 `AndroidManifest.xml`，避免无意义的 APK 重打包。

## merged manifest 是增量合并的基线

完整 Gradle 构建会把 application、build variant、占位符和依赖库中的 Manifest 合并为最终结果。Jugg 的增量现场没有完整保留标准 Manifest merge 所需的所有输入；重新从原始 Manifest 发起合并，可能丢失 variant、依赖库或构建脚本提供的结果。

Jugg 因此优先使用上一轮增量写回的 merged manifest；没有这份结果时，使用最近一次 Gradle 构建生成的 application merged manifest。随后补入当前模块能够确定的 `applicationId`、namespace 和 Manifest placeholder，比较新旧 Manifest，只把新增节点和属性更新应用到基线：

```text
选择最近一次 merged manifest
  -> 补全当前模块能够确定的 placeholder
  -> 比较本轮 Manifest 与上次构建结果
  -> 应用新增节点和属性更新
  -> 保存新的 merged manifest，供下一轮继续使用
```

库 Manifest 内容没有变化，或比较后没有产生有效更新时，本轮不会继续输出新的 Manifest。

## aapt2 如何生成可部署的 Manifest

增量合并得到的仍是普通 XML，不能直接替换 APK 中的二进制 Manifest。Jugg 的 aapt2 `inclink` 会先加载目标 APK 当前的资源表；如果之前已经增量部署过 `resources.arsc`，则使用最新资源表和对应 Manifest 组成新的加载基线。dynamic feature 还会同时引用 base APK 的资源结果，保持包 ID 与资源引用一致。

完成加载后，aapt2 link 接收增量合并 XML 和本轮 `.flat` 文件，输出二进制 `AndroidManifest.xml`、`resources.arsc`、编译后的资源文件以及可能变化的 `R.java`。其中只有本轮真实需要的产物会进入部署：Manifest 变化会触发 APK 更新和重签名；同时存在新的 `resources.arsc` 时，两者会一起写回 APK。

aapt2 无法加载当前资源表或 link 失败时，Manifest 不会绕过资源阶段单独部署，本轮资源编译会整体失败并进入既有失败收口。

## 删除操作为什么不会改变已安装 Manifest

增量阶段只能应用来源明确的新增和更新，不能可靠判断一个声明是否应该从最终 merged manifest 删除。旧声明可能来自其他 source set 或依赖库，直接删除会破坏 Gradle 已经合并好的结果。因此下列变化不会由增量 patch 完整处理：

- 删除节点或属性。
- `tools:node="remove"`、`tools:remove`、`tools:replace` 等依赖完整 merge 上下文的指令。
- `uses-sdk`、manifest `package`、`versionCode` 和 `versionName`。
- application `android:name`。

Jugg 会忽略这些删除或完整 merge 指令，不生成删除 patch，也不会仅因此让本轮增量编译失败。设备继续使用已安装 APK 中原有的节点和属性，通过系统或 `PackageManager` 查询时仍能看到旧内容。只有需要让删除真正生效时，才执行完整 Gradle 构建，重新生成 merged manifest 和 APK 基线。

找不到可信的 merged manifest 属于另一类情况：此时新增和更新也缺少可用基线，Manifest 增量编译会失败，并提示通过 Gradle 构建恢复。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [AndroidManifest 编译能力](../../capabilities/compile/manifest.md)
