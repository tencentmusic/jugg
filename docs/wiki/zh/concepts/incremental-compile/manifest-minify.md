---
title: Android Manifest 编译与 release 增量编译
description: 说明 Jugg 如何增量合并 AndroidManifest，并在 release/minified 场景保持 mapping 一致。
status: active
tags:
  - concept
  - compile
  - manifest
  - minify
---

# Android Manifest 编译与 release 增量编译

这两条链路经常被放在一起讨论，但它们解决的问题不同。Android Manifest 编译处理的是 APK 里的 `AndroidManifest.xml`；release 增量编译处理的是混淆后 APK 与本轮 dex 的 mapping 一致性。

## Android Manifest 编译

`AndroidManifest.xml` 最终在 APK 中是二进制文件，系统不会从普通资源 overlay 读取新的 Manifest。因此 Manifest 变化需要生成新的 Manifest overlay，并在部署阶段走更保守的处理。

Jugg 不重新执行完整 Gradle manifest merge，而是在上一次 merged manifest 上打补丁：

```text
ResourceOverlayCompiler
  -> AndroidManifestCompiler
  -> 选择基准 merged manifest
     -> 优先上轮 Jugg 写入的 tempModule/res/AndroidManifest.xml
     -> 否则使用 Gradle application module merged manifest
  -> 为变更 manifest 补 applicationId / namespace placeholder
  -> ManifestDiffer 生成真实 diff
  -> AndroidManifestMerger 把 diff patch 到基准 merged manifest
  -> 写回 tempModule/res/AndroidManifest.xml
```

这种做法保留了 Gradle 已经合并好的 variant、placeholder 和库 Manifest 结果。标准 merge 在增量现场拿不到完整上下文，因此 Jugg 只 patch 最终 merged manifest。

Manifest 产物不会按普通资源 overlay 下发。部署数据生成时，`AndroidManifest.xml` 会进入 `updateApkFiles`；如果本轮同时产出 `resources.arsc`，也会一起写回 APK。部署阶段会更新 APK、重新签名，然后通过 update apk 模式恢复部署状态并安装更新后的 APK。

## Manifest 过滤

Manifest 输出为空是有效结果。library manifest 未变化、CRC 相同或 diff 后没有真实变更时，Jugg 不会输出 `AndroidManifest.xml`。这样可以避免触发无意义的 APK repackage。

Manifest merge 会忽略 `tools:*` 属性、manifest `package` 和 application `android:name` 更新，避免增量 patch 覆盖运行时身份相关字段。

## release 增量编译

release 或开启混淆的变体需要保持增量 dex 与已安装 APK 的 mapping 一致。Jugg 会读取已安装 APK 或增量数据目录中的 `mapping.txt`，并在 dex 阶段执行名称、字段、方法和内部引用重映射。

基本链路如下：

```text
DexCompiler
  -> 输出未混淆 dex 到 temp/un_minify
  -> DexMinifyCompiler 读取 mapping.txt
  -> 临时混淆 dex，用 APK 中的混淆类名查询影响数据
  -> 根据 inline 影响和原始 class 生成 _jugg_fix dex
  -> 普通增量 dex 执行 obfuscate 或 inline redirect
  -> 输出与 APK mapping 一致的 dex
```

`mapping.txt` 缺失时，Jugg 会打印 warn 并继续，排查 release 增量异常时应先确认 mapping 是否加载成功。

## `_jugg_fix`

`_jugg_fix` 用于桥接 inline 或 minify 影响场景。Jugg 会把原始 class 经过 `usage.txt` 兼容改写、D8、混淆和类声明改名，生成带 `_jugg_fix` 后缀的 dex。

它的内部调用仍指向原混淆类，类声明名带后缀，避免桥接类变成一套脱离 APK mapping 的新实现。

`usage.txt` 只增强 `_jugg_fix` 输入 class 的兼容改写。已删除方法会保留签名并改为空实现或默认返回；字段删除由 reader 记录，但当前链路主要消费 removed methods。

release 增量编译本身不等于 update apk。它主要让 dex 与已安装 APK 的混淆结果对齐；只有 Manifest、`resources.arsc`、native lib 等进入 `updateApkFiles` 的产物，才会触发 update apk 模式。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [源码增量编译](./source.md)
- [AndroidManifest 编译能力](../../capabilities/compile/manifest.md)
- [Release 编译能力](../../capabilities/compile/release-compile.md)
