---
title: Android Manifest 编译与 release 增量编译
description: 说明 Manifest 为何不能走普通资源 overlay、增量现场缺少完整 merge 上下文的应对方式，以及 release 场景如何保持混淆 mapping 一致，并交代各自的边界。
status: active
tags:
  - concept
  - compile
  - manifest
  - minify
---

# Android Manifest 编译与 release 增量编译

日常 debug 增量希望只处理本轮变化，但有两类产物不能简单按“变化文件 -> overlay”理解：Manifest 决定 APK 的安装与运行身份，release DEX 必须匹配已安装 APK 的混淆结果。它们都站在完整 Gradle 构建的边界上，直接重做会很重，跳过又会让设备结果不可信。

这页把两条链路放在一起，是因为它们解决的是同一个增量难题：在现场拿不到完整构建上下文时，如何只补必要部分，同时不破坏 APK 身份和混淆 mapping。Manifest 增量处理 APK 里的 `AndroidManifest.xml`；release 增量处理混淆后 APK 与本轮 DEX 的命名一致性。

## Android Manifest 增量

### Manifest 不是普通资源，也无法现场重做 merge

`AndroidManifest.xml` 在 APK 中是二进制文件，系统不会从普通资源 overlay 读取新的 Manifest。所以 Manifest 一旦变化，不能像普通资源那样叠加生效。

更麻烦的是 merge 上下文。完整的 Manifest 合并要把 variant、占位符（placeholder）和所有库 Manifest 一起算进来，这些只有 Gradle 完整执行时才齐全。增量现场没有这套上下文，重新跑一遍标准 merge 既慢又容易得到与 Gradle 不一致的结果。

### 在上轮 merged manifest 上打补丁

Jugg 不重做完整 merge，而是把「最近一次合并好的 merged manifest」当基准，只把本轮真实变化补丁上去：

```text
选择基准 merged manifest
  -> 优先用上轮 Jugg 写回的合并结果
  -> 否则用 Gradle 上次构建的 application 合并结果
为变化的 Manifest 补回 applicationId / namespace 占位符
比对出真实新增或变更的节点
把这些节点补丁到基准 merged manifest
写回合并结果，供下一轮继续作为基准
```

这样保留了 Gradle 已经算好的 variant、占位符和库 Manifest 结果，只在最终产物上做最小改动。

Manifest 产物不会按普通资源 overlay 下发，而是进入「需要写回 APK 的文件」清单。部署阶段会把它写回 APK、重新签名，再通过 `update apk` 模式安装更新后的 APK；如果本轮同时产出了 `resources.arsc`，也会一起写回。

### Manifest 增量的过滤与约束

打补丁的前提是只动真实变化的节点，因此这条链路有两处刻意的过滤与保护：

- **空输出是有效结果**：库 Manifest 未变化、内容校验一致或比对后没有真实变更时，不输出 `AndroidManifest.xml`，避免触发无意义的 APK 重打包。
- **忽略身份相关字段**：合并会忽略 `tools:*` 属性、manifest `package` 以及 application `android:name` 的更新，避免增量补丁覆盖运行时身份。这不是漏合并，而是刻意的保护。

## release 增量编译

### 增量 DEX 必须对齐已安装 APK 的混淆结果

release 或开启混淆的变体，已安装 APK 里的类名、方法名、字段名都已经被 R8/ProGuard 重命名过。本轮增量编译出的 DEX 如果还用原始名字，就和设备上的混淆产物对不上，运行时会找不到类或方法。

更隐蔽的是 inline 和成员移除：R8 会把一些方法内联到调用处、删除未使用的成员。增量编译只看本轮改动的源码，无法直接还原这些已经发生在 APK 里的变换。

### 按 mapping 重映射，并用 `_jugg_fix` 桥接 inline 与移除

Jugg 读取已安装 APK 或增量数据目录中的 `mapping.txt`，在 DEX 阶段对名称和内部引用做重映射，使增量 DEX 与设备上的混淆结果一致：

```text
生成未混淆 DEX
  -> 读取 mapping.txt，按已安装 APK 的混淆名重映射
  -> 对受 inline 影响或被移除的成员，生成 _jugg_fix 桥接类
  -> 输出与 APK mapping 一致的 DEX
```

`_jugg_fix` 用来桥接 inline 和成员移除场景。Jugg 把原始类先完全混淆，再只改类声明名、加上 `_jugg_fix` 后缀；它的内部调用仍然指向原混淆类，所以不会变成一套脱离 APK mapping 的独立实现。配合 `usage.txt`，被删除的方法会保留签名、改为空实现或默认返回，避免桥接类在编译期缺少符号。`_jugg_fix` 类会出现在设备的 DEX 和类名里，是用户可见的产物。

### release 增量的降级与触发边界

重映射依赖 mapping 是否就位，也决定了它何时会牵动整包刷新，对应两条边界：

- **mapping 缺失降级**：找不到 `mapping.txt` 时只打印 warn 并继续，不会硬失败。排查 release 增量异常时，应先确认日志里 mapping 是否加载成功。
- **release ≠ update apk**：release 增量本身只让 DEX 对齐已安装 APK 的混淆结果；只有 Manifest、`resources.arsc`、native lib 等需要写回 APK 的产物，才会触发 `update apk` 模式。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [源码增量编译](./source.md)
- [AndroidManifest 编译能力](../../capabilities/compile/manifest.md)
- [Release 编译能力](../../capabilities/compile/release-compile.md)
