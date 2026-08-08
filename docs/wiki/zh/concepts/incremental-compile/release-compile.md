---
title: release 增量编译
description: 解释实验性的 release 增量编译如何接入 D8、复用 mapping 重新混淆 DEX，并与扩散编译协作。
status: active
tags:
  - concept
  - compile
  - release
  - minify
---

# release 增量编译

release 或其他启用 minify 的 APK 中，类名、方法名和字段名已经被 R8 / ProGuard 改写。本轮源码仍会先生成使用原始名称的 class 和 DEX；Jugg 随后复用当前 APK 的 mapping 重新混淆增量 DEX，使它能够继续引用 APK 中已有的类和成员。

> [!WARNING]
> **实验性能力**
>
> release 增量编译目前实际工程覆盖有限，不能替代完整 Gradle release 构建。修改 keep 规则、升级 R8 / ProGuard、切换 APK 基线，或遇到反射、注解、类型引用和成员访问异常时，应执行 Gradle release 构建验证并刷新基线。

## release 处理插在 D8 之后

release 重新混淆属于源码增量编译的末端阶段。Kotlin 和 Java 编译仍先生成普通 class，D8 也仍先生成使用原始名称的 DEX；只有当前构建基线存在 `mapping.txt` 时，这些 DEX 才会先进入临时未混淆目录，再交给 release 处理：

```text
Kotlin / Java 源码
  -> 生成未混淆 class
  -> D8 生成未混淆 DEX
  -> 按 mapping.txt 重新混淆 DEX
  -> 生成必要的 _jugg_fix DEX
  -> 最终 DEX 进入 staging
  -> 分析是否需要继续扩散编译
```

这不是重新执行一次完整 R8。增量阶段不会重新做整包 shrinking、optimization，也不会生成一份新的 mapping；它只复用当前 APK 的 mapping 重放名称转换，并补偿部分 inline 和删除成员结果。因此 mapping 必须和设备上的 APK 来自同一份可信 Gradle 基线。

## release 增量编译的触发判断

Jugg 不根据 variant 名称决定是否执行 release 增量编译，而是检查当前 application 构建路径下是否存在 `mapping.txt`：

- mapping 存在时，D8 先把未混淆 DEX 写入临时目录，再执行 mapping 重放、影响分析和 `_jugg_fix` 补偿。
- mapping 不存在时，D8 直接把 DEX 写入最终输出目录，不创建重新混淆任务，也不读取 `usage.txt`。

普通 debug 构建通常不会生成 mapping，因此自然走第二条路径。这个判断也覆盖自定义变体：名称中包含 `release`、但没有 mapping 的变体不会进入重新混淆；名称是 debug、但确实启用了 minify 并生成 mapping 的变体仍会进入。variant 名称只描述构建用途，不是 release 增量编译的触发条件。

## 未混淆 DEX 如何重新混淆

进入 release 阶段后，Jugg 加载 `mapping.txt`，对 DEX 中的类声明、输出路径、方法名、字段名、类型引用和内部调用引用执行同一套名称映射。mapping 中没有对应记录的类会保持原名，而不是为本轮增量重新分配名称。

影响分析使用的是从基线 APK 建立的索引，其中保存的是混淆后的类名。为了查询 inline 和删除成员影响，Jugg 会先把本轮 DEX 临时映射一次，再用混淆名称查询 APK 索引。这份临时 DEX 只用于分析；最终输出仍由正式重新混淆步骤生成：

```text
未混淆 DEX
  -> 临时重映射，用混淆类名查询 APK 索引
  -> 得到 inline / 删除成员影响信息
  -> 正式重映射类、成员、类型与调用引用
  -> 按混淆后的类名生成最终 DEX 路径
```

## `_jugg_fix` 补偿 inline 和删除成员

R8 可能把方法实现 inline 到调用方，或删除未使用的成员。只重新映射本轮 DEX 的名称，无法恢复这些已经写入基线 APK 的变换。

Jugg 会根据影响分析结果为需要补偿的原始 class 生成 `_jugg_fix` DEX：

```text
读取受影响类的原始 class
  -> 使用 usage.txt 将已删除方法改写为兼容空实现
  -> D8 生成 DEX
  -> 按 mapping.txt 完成混淆
  -> 只把类声明名改为混淆名 + _jugg_fix
  -> 将本轮调用重定向到桥接 DEX
```

桥接 DEX 内部的方法调用和字段访问仍然指向 APK 中的原混淆类，使它复用当前 APK 的成员布局，而不是形成另一套独立命名结果。`usage.txt` 缺失或解析失败时，只跳过删除方法的兼容改写；mapping 重映射和其他可用产物仍继续执行。单个 `_jugg_fix` DEX 生成失败时，也只舍弃受影响的桥接产物并记录 warning。

## 扩散编译如何继续重新混淆

每轮源码编译结束后，进入 staging 的已经是重新混淆后的最终 DEX。影响分析用这些 DEX 与基线 APK 的引用索引比较，得到还需要编译的调用方、子类或其他受影响源码。

APK 索引返回的类名可能已经混淆，Jugg 会通过同一份 mapping 把它们还原为原始类名，再定位工程中的源码。下一轮扩散编译重新从这些原始源码开始，完整经过语言编译、D8 和重新混淆，而不是直接修改上一轮的混淆 DEX：

```text
本轮最终混淆 DEX
  -> 查询 APK 引用索引
  -> 将受影响类名还原为原始名称
  -> 定位并编译受影响源码
  -> D8 再次生成未混淆 DEX
  -> 再次按同一 mapping 重新混淆
  -> 新的最终 DEX 进入 staging
```

后续扩散轮仍会执行重新混淆，但普通影响传播不会反复追加同一批 inline 影响，避免由同一个 release 补偿原因形成循环。

## mapping 基线缺失或失配时

`mapping.txt` 既是重新混淆输入，也是是否进入 release 处理的门禁。文件不存在时，源码编译链会按非 minified 路径直接输出 DEX，不会执行 mapping 重放；这份 DEX 不能可靠部署到已经混淆的 APK，应通过 Gradle release 构建恢复 mapping 和 APK 基线。

当前 mapping 与已安装 APK 不匹配时，即使名称转换本身成功，运行时仍可能出现 `NoClassDefFoundError`、`NoSuchMethodError`、`IllegalAccessError`、注解查找失败等问题。实验性 release 增量出现异常时，应保留日志，并首先使用同一代码执行完整 Gradle release 构建对照。

## 相关页面

- [增量编译总览](./index.md)
- [源码增量编译](./source.md)
- [重编译 / 扩散编译](./recompile-propagation.md)
- [Release 编译能力](../../capabilities/compile/release-compile.md)
