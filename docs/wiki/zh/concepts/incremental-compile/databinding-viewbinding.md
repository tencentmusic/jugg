---
title: DataBinding / ViewBinding 增量编译
description: 说明 DataBinding / ViewBinding 如何从 layout 生成资源与源码，以及 Jugg 如何基于 Gradle 中间产物完成两阶段增量处理。
status: active
tags:
  - concept
  - compile
  - databinding
---

# DataBinding / ViewBinding 增量编译

DataBinding 和 ViewBinding 都会在编译期读取 layout，并生成开发者代码可以直接使用的 binding class。ViewBinding 主要把 view id 转成类型安全字段；DataBinding 还会处理变量、表达式、`<include>` 和自定义属性，生成 BindingImpl、mapper 与 BR 等配套代码。

完整 Gradle 构建会在资源处理与源码编译之间传递 layout info、生成源码和注解处理器输入。Jugg 复用最近一次 Gradle 构建留下的这些中间产物，只重新处理本轮变化的 layout：资源阶段准备可供 aapt2 使用的 XML 和生成输入，源码阶段继续生成并编译 binding 相关代码。

## DataBinding / ViewBinding 如何从 layout 生成代码

两者都以 layout 为输入，但生成范围不同：

| 机制 | layout 中关注的内容 | 主要生成结果 |
|---|---|---|
| ViewBinding | layout 文件及带 id 的 view | 对应 layout 的 binding class，包含 view 字段以及 inflate / bind 入口。 |
| DataBinding | `<layout>`、变量、表达式、`<include>`、可绑定字段和自定义属性 | stripped XML、layout info、binding base/implementation、mapper 和 BR。 |

ViewBinding 的 layout 仍作为普通 Android 资源进入 aapt2；生成的 binding class 作为 Java 源码继续编译。DataBinding 会先把绑定表达式转换成 aapt2 可以处理的 stripped XML，同时把变量、表达式和 include 关系写入 layout info，再由官方 DataBinding annotation processor 生成 BindingImpl、mapper 和 BR。

```text
layout 源文件
  -> DataBinding / ViewBinding layout 处理
  -> 可供 aapt2 使用的 XML + layout info / binding 源码
  -> aapt2 生成资源产物
  -> annotation processor 按需生成 DataBinding 源码
  -> Java/Kotlin 编译生成 class 和 DEX
```

## Jugg 如何完成两阶段增量处理

Jugg 不重新执行完整 DataBinding Gradle task，而是从 Gradle 基线中读取 layout info、BR、setter store 和已生成的绑定产物，只重建本轮变化涉及的部分：

```text
变化 layout + Gradle 基线
  -> 资源阶段更新 XML、layout info 和 binding 生成输入
  -> XML 进入 aapt2 资源编译
  -> 生成源码和 DataBinding 触发源进入源码阶段
  -> 源码阶段生成 mapper / BR / BindingImpl
  -> binding 相关源码与本轮 Java/Kotlin 一起编译
```

### 资源阶段准备 XML 和 binding 输入

ViewBinding 不需要 DataBinding mapper。它在资源阶段生成 binding class，随后作为普通 Java 源码进入语言编译；原 layout 继续进入资源编译。

DataBinding layout 中的表达式和绑定标签不能原样交给 aapt2。资源阶段会生成去除绑定信息的 stripped XML 作为资源输入，同时保存 layout info，并生成一个只用于启动 DataBinding 注解处理器的触发源。

### 源码阶段生成 DataBinding 代码

DataBinding 的源码阶段不能重新初始化一套空工作区。mapper、BindingImpl 和 BR 依赖资源阶段刚生成的 layout info，因此两阶段必须共享本轮状态：

```text
资源阶段输出 layout info 和触发源
  -> 补齐受 <include> 影响的 layout info
  -> 运行 DataBinding mapper 注解处理
  -> 生成 BindingImpl、mapper、BR 和 Java 源码
  -> 与本轮 Java/Kotlin 一起编译为 class 和 DEX
```

mapper 默认通过 Java APT 运行官方 DataBinding processor。Jugg 只负责准备 layout、classpath、setter store 和触发源，不自行推导 BindingImpl 或 BindingAdapter 的方法签名。

## Kotlin BindingAdapter 为什么需要提前准备 setter store

DataBinding mapper 在处理自定义属性时，需要先知道 BindingAdapter 的声明类型和方法信息。Java BindingAdapter 可以由同一轮 mapper APT 处理；Kotlin BindingAdapter 的元数据则需要先通过项目 KAPT 生成 setter store。

```text
检测到 Kotlin BindingAdapter 声明
  -> 在隔离的项目 KAPT 环境中只生成当前模块 setter store
  -> 编译 Kotlin adapter class
  -> class 成功后，把当前 store 合入 Gradle 基线和上一轮增量 store
  -> mapper APT 使用合并后的 store 生成 BindingImpl 和 mapper
```

setter store 只有在 Kotlin adapter class 编译成功后才会发布。这样生成元数据失败或 adapter 源码本身编译失败时，不会把尚未生效的声明写进后续增量基线。

隔离 KAPT 使用项目自己的 Kotlin 编译器和 DataBinding 依赖，不继承 Android Studio 宿主进程中的编译器状态。它只处理 BindingAdapter 元数据，mapper 本身仍走 Java APT。

## 跨轮状态如何避免生成结果错位

DataBinding 增量处理还需要维护几类跨轮状态：

- **layout info 连接两个阶段。** 源码阶段继续使用资源阶段生成的 layout info，不能在 mapper 前清空。
- **`<include>` 影响从 layout info 递归补齐。** 被 include 的 layout 发生变化时，Jugg 会从当前模块和依赖模块的 layout info 找回相关输入，而不是只扫描本轮 XML 文本。
- **BR 字段保持已有顺序。** 新字段追加到 Gradle 基线 BR 的末尾，避免已部署代码中的 BR id 因重新排序而错位。
- **setter store 以 Gradle 结果为基线。** 当前模块结果会与 Gradle 模块 store 和上一轮有效增量结果合并，mapper 还会加载依赖 AAR 的 store；Gradle 基线变化后，旧增量缓存不再复用。
- **layout info 保留稳定备份。** 新增 layout 后再删除文件时，后续完整 Gradle 构建仍需要一致的 layout 信息，Jugg 会保留可供恢复的基线。

## 失败重试与适用边界

- **mapper 缺少 Kotlin class 时只重试一次。** mapper APT 失败且本轮包含 Kotlin 源码时，Jugg 会先编译 Kotlin class，再运行一次 mapper；第二次失败会保留最终异常，不继续循环。
- **普通 ViewBinding 不进入 mapper。** 只启用 ViewBinding 的模块生成 binding class 后进入语言编译，不应生成 DataBinding mapper 或 BR。
- **Gradle 基线文件必须存在。** layout info、BR 或 setter store 缺失时，无法从空状态可靠恢复，应执行对应 Gradle 构建刷新基线。
- **AGP 版本会改变中间产物位置。** Jugg 会在已知候选目录中匹配；升级 AGP、首次启用绑定功能或修改相关 Gradle 配置后，应先完成 Gradle 构建或 Sync。
- **BindingAdapter 删除和改名需要完整基线。** 当前增量 store 支持声明新增和同一 declaring class 内的修改；删除全部声明或修改 declaring class 名称时，应执行 Gradle 构建清除旧元数据。
- **删除 layout 遵循资源删除边界。** stripped XML、layout info 和资源表都需要重新建立，不能只依赖本轮 overlay 删除旧状态。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [源码增量编译](./source.md)
- [DataBinding / ViewBinding 能力](../../capabilities/compile/databinding-viewbinding.md)
- [注解处理器](../../capabilities/compile/annotation-processors.md)
