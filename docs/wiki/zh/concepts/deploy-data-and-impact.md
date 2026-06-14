---
title: 部署数据与影响分析
description: 解释 Jugg 如何从编译产物判断 hot reload、hot fix、补编译和 APK 更新。
status: active
tags:
  - concept
  - deploy
  - impact
---

# 部署数据与影响分析

Jugg 编译成功后，并不会直接把所有产物推到设备。它会先把 staging 产物和部署历史放在一起分析，生成本轮部署数据。部署数据决定了哪些内容可以 hot reload，哪些必须 hot fix，哪些源码需要继续补编译，哪些文件需要写回 APK。

## 部署数据包含什么

| 内容 | 含义 |
|---|---|
| 新增类 | 设备历史中没有出现过的类，通常不能只做轻量热更新。 |
| 可热更新类 | 类结构未变，适合更轻量的在线更新。 |
| 需要 hot fix 的类 | 新增类、结构变化、多 dex、library dex 或不适合在线更新的类。 |
| 受影响源码 | 虽然未直接修改，但因为调用、继承、泛型、常量等关系需要补编译的源码。 |
| 资源和 assets overlay | 可直接下发到设备 overlay 的资源或 assets。 |
| APK 内更新文件 | Manifest、`resources.arsc`、native lib 等需要写回 APK 的文件。 |
| APK 归属 | 标明产物应该影响哪些 APK，避免 multi APK 场景错投。 |

## 为什么小改动会影响其他源码

Jugg 会比较新旧 class 结构，并结合历史索引寻找受影响范围。典型情况包括：

- 删除或改变方法签名。
- 删除字段。
- 给非抽象类或接口链增加抽象要求。
- 修改泛型 signature。
- 修改编译期常量并影响调用方。
- release/minify 场景中，旧方法曾被内联到其他类。

如果命中这些情况，Jugg 会把受影响源码送回编译流水线继续补编译。这样可以避免只更新被修改文件后，调用方仍保留旧字节码。

## Hot Reload 与 Hot Fix 的差别

| 判断 | Hot Reload | Hot Fix |
|---|---|---|
| 类是否已存在 | 通常已存在 | 新增类或复杂归属更容易进入 |
| 结构是否变化 | 结构未变 | 方法、字段、继承或其他结构变化 |
| 是否需要重启 App | 尽量不需要 | 通常需要 |
| 适合场景 | 方法体小改动 | 新类、结构变化、兼容路径、library dex 等 |

这不是用户手动选择的模式，而是部署数据分析后的结果。

## 资源、Manifest 和 native lib 的处理

资源和 assets 通常以 overlay 形式部署。首次资源 overlay 会补齐更多资源，避免设备端缺少基础资源文件。

Manifest 和 native lib 更特殊：

- Manifest 变化会触发 APK 内更新。
- `resources.arsc` 可能随 Manifest 或资源策略一起写回 APK。
- `.so` 属于 native lib 更新，不是普通资源 overlay。

这些文件一旦需要写回 APK，就可能触发重新签名、安装或更保守的部署路径。

## 分析结果何时生效

部署数据只是“本轮准备部署什么”。只有整轮部署成功后，Jugg 才会：

1. 提交本轮 staging 产物。
2. 更新部署历史和引用索引。
3. 记录新的设备 checkpoint。

如果部署失败，这些状态不会被当作已经成功部署，下一轮仍可以重新处理。

## 相关页面

- [编译流水线](./compile-pipeline.md)
- [部署策略](./deploy-strategy.md)
- [增量编译](./incremental-compile.md)
