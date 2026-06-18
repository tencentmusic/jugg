---
title: DataBinding / ViewBinding 增量编译
description: 解释 DataBinding / ViewBinding 为何必须拆成资源阶段和源码阶段处理，以及两阶段之间如何交接工作区与 layout 信息。
status: active
tags:
  - concept
  - compile
  - databinding
---

# DataBinding / ViewBinding 增量编译

DataBinding / ViewBinding 不是单个阶段能完成的：它既要参与资源处理，又要生成 Java/Kotlin 源码。Jugg 把它拆成资源阶段和源码阶段两段，分别处理 base class / stripped XML 和 mapper / BR。

## 一份功能横跨资源与源码两条链路

layout 不是普通资源。开启 DataBinding / ViewBinding 后，同一份 layout 既要生成进入 APK 的资源（去掉绑定标签后的 stripped XML），又要生成开发者代码引用的绑定类（base class、mapper、BR）。

资源处理和源码编译在 Jugg 里是两个不同阶段，前者先于后者执行。如果把绑定逻辑塞进任意单一阶段，要么资源阶段还拿不到完整源码上下文，要么源码阶段已经错过了资源链接的时机。所以这份功能要拆成两段，并且两段之间必须严格交接中间产物。

## 资源阶段与源码阶段的两段交接

```text
资源阶段
  -> 解析 DataBinding 中间产物目录
  -> 处理本轮变化的 layout
  -> 生成 base class、触发源、stripped XML 和 layout 信息
  -> stripped XML 进入资源 overlay
  -> 生成的源码转交源码阶段

源码阶段
  -> 继续消费资源阶段刚写入的 layout 信息（不重置工作区）
  -> 运行 DataBinding 注解处理
  -> 生成 mapper、BR 和 Java 源码
  -> 合入语言编译输入
```

ViewBinding 产物在资源阶段完成生成并转交后续编译；DataBinding 还要在源码阶段继续生成 mapper、BR 和 Java 源码。

拆成两段后，几条约束保证了增量结果与全量构建一致：

- **源码阶段不重置资源阶段的工作区**：mapper 生成依赖资源阶段刚写入的 layout 信息，如果源码阶段清空工作区，mapper 会缺输入。
- **备份 Gradle 的 layout 信息**：新增 layout 后又删除时，如果不保留备份，后续完整 Gradle 构建会因为缺 layout 信息文件而失败。Jugg 在工作区里备份一份稳定的 layout 信息来兜底。
- **BR 字段只追加到末尾**：BR 合并基于上一次 Gradle 生成的 BR，新增字段追加在末尾，保持 BR id 稳定，避免已部署代码里的 BR 引用错位。
- **`<include>` 关系按 layout 信息补齐**：被引用 layout 的影响范围不是靠扫描 XML 文本得出，而是基于 layout 信息补齐受影响 layout，再交给 mapper 阶段消费。
- **注解处理走 APT 路径**：当前默认用 Java APT 触发 DataBinding 注解处理；KAPT 失败回退 Java APT 的分支仍保留，但日常行为以 APT 路径为准。

## 两阶段处理的边界与排查信号

跨阶段处理也带来几个边界，排查 layout 异常时可以据此判断：

- **版本路径漂移**：不同 AGP 版本下，DataBinding 中间产物的目录位置不同。Jugg 通过候选目录匹配来定位；遇到中间产物找不到，优先怀疑版本路径差异，而不是编译参数。
- **不能只看 Java 输出判断成功**：stripped XML 是资源产物，会进入资源 overlay；只检查生成的 Java 是否产出，不能说明本轮 layout 处理成功。
- **ViewBinding 不应触发 mapper**：普通 ViewBinding layout 只走资源阶段，不应进入 DataBinding 的 mapper / BR 处理。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [源码增量编译](./source.md)
- [DataBinding / ViewBinding 能力](../../capabilities/compile/databinding-viewbinding.md)
