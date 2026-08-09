---
title: 资源增量编译
description: 解释单个 Android 资源修改为什么仍需要完整 link 上下文，以及 Jugg 如何基于 APK 资源表用 inclink 生成资源 overlay。
status: active
tags:
  - concept
  - compile
  - resource
---

# 资源增量编译

开发者只修改一个 layout、drawable 或 values XML 时，aapt2 `compile` 可以只处理这个文件，但标准 `link` 仍需要读取完整资源集合、分配资源 ID 并重建资源表。Jugg 从最近一次可信的 APK 资源表恢复 link 上下文，再通过定制 aapt2 的 `inclink` 注入本轮变化，避免每次资源修改都重新链接全部历史输入。

## 只改一个 XML，为什么 link 仍需要完整状态

aapt2 把 Android `res/` 资源处理拆成两个阶段：

```text
aapt2 compile
  -> 把单个 XML、图片等资源编译为 flat 中间产物

aapt2 link
  -> 读取全部 flat、Manifest 和符号信息
  -> 合并资源并分配资源 ID
  -> 输出 resources.arsc、编译后资源、Manifest 和 R.java
```

`compile` 的输入可以局部化，`link` 的判断却依赖全局资源表。新增资源需要获得不冲突的 ID，已有资源覆盖需要保留原来的 ID，Manifest、自定义属性和跨模块引用也必须与同一份资源表对齐。因此标准 link 的工作量主要由完整资源集合决定，而不是由本轮只修改了几个文件决定。

## inclink 如何复用 APK 中的资源基线

Jugg 定制了 aapt2，新增 `inclink` 命令，把完整 link 拆成一次基线加载和多轮增量注入：

```text
加载基线
  -> 从 APK 读取 resources.arsc、编译后资源和 Manifest
  -> 恢复资源 ID 与 link 上下文
  -> 把上下文保留在 aapt2 daemon 中

每轮增量
  -> 只编译本轮变化资源得到 flat
  -> 在已有上下文中新增或覆盖资源
  -> 输出新的 resources.arsc、资源 overlay 和可选 R.java
```

这条路径不需要保存并重新读取所有历史 flat。APK 中的 `resources.arsc` 已经包含 Gradle 构建确定的资源 ID，`inclink` 以它为基线，只处理本轮变化及必要的状态更新。若本轮没有产生新的 `R.java`，源码阶段也不需要为资源变化额外编译 R 类。

## 连续多轮增量必须从最新资源表继续

资源基线不能始终只读最初的 Gradle APK。假设上一轮 Jugg 已经部署了新的 `resources.arsc`，下一轮仍从原始 APK 加载，就会丢失上一轮新增的资源和 ID。

Jugg 按当前 APK 状态选择基线：

| 当前状态 | link 基线 |
|---|---|
| 尚未增量部署资源 | 最近一次可信的 Gradle APK。 |
| 已部署新的 `resources.arsc` | 用已部署资源表和当前 Manifest 组成临时资源 APK。 |
| dynamic feature APK | 加载 feature 自身资源表，同时引用 base APK 的最新资源表。 |

当 base 本轮也发生资源变化时，feature link 还会带上 base 本轮产生的 flat，使两边在同一轮使用一致的资源 ID。

## resources.arsc 之外还需要补回哪些信息

`resources.arsc` 是最终资源表，但它没有保存完整的 `R.styleable` 聚合声明。Jugg 会从目标 APK 相关模块的 `R.jar`、`R.class` 或 Java classpath 中读取 styleable 字段，并在加载基线时补给 `inclink`。否则新增或修改自定义属性时，资源表与生成的 R 声明可能无法对齐。

release 或 AabResGuard 工程还有一项额外输入：Gradle 基线可能使用了混淆后的资源名称。Jugg 会读取已有 `resources-mapping.txt`，转换成 `inclink` 使用的 mapping，使本轮资源继续沿用已安装 APK 的命名。

styleable 和资源混淆 mapping 都是辅助输入。生成失败时，Jugg 会记录原因并尝试在缺少该输入的情况下继续加载；随后若修改涉及新 styleable 或混淆资源引用，资源编译或运行结果仍可能失败。核心资源表加载失败则会直接结束本轮资源编译，不会伪造成功。

## 变化资源如何进入部署和源码编译

资源阶段按目标 APK 分流，再串联 Manifest、DataBinding/ViewBinding、flat 编译和增量 link：

```text
资源变化输入
  -> 按目标 APK 拆分
  -> 处理真实发生变化的 Manifest
  -> DataBinding / ViewBinding 拆分 layout 并生成源码
  -> aapt2 compile 生成本轮 flat
  -> inclink 注入最新资源基线
  -> 过滤不应部署的额外产物
  -> 输出 resources.arsc、资源 overlay、可选 Manifest 和 R.java
```

layout 生成的 Java/Kotlin 源码不会作为资源直接部署，而是交给后续源码阶段编译。`R.java` 只有在 `inclink` 实际输出时才进入源码阶段。Manifest 没有真实变化时，资源阶段会过滤 aapt2 附带生成的根 Manifest，避免无意义地触发 APK 更新和重签名。

## 有状态缓存失败后如何恢复

每个目标 APK 都有独立的 link 上下文，因为不同 APK 的资源表、package id 和 Manifest 可能不同。aapt2 daemon 不存在、已经退出或尚未加载该 APK 时，Jugg 会重新加载对应资源基线。

如果基线加载失败，本轮资源编译直接失败；如果增量 link 失败，Jugg 会释放当前 daemon，不再复用可能损坏的内存状态。下一次资源编译会创建新的 daemon 并重新加载基线，从改变失败条件后的干净状态开始。

## inclink 的适用边界

- **资源表只新增或覆盖，不生成删除操作。** 删除资源文件时，设备端原有 entry 和资源 ID 保持不变，仍可通过资源 API 读取；只有需要让删除真正生效时，才通过完整 Gradle 构建刷新基线。因此 `inclink` 只用于开发期增量，不是生产资源链接器。
- **多 APK 必须分别链接。** 资源产物按目标 APK 独立生成，不能把同一份 overlay 复制给 base、feature 或其他 APK。
- **dynamic feature 依赖 base 状态。** base 资源表变化需要参与 feature 的同轮 link，避免资源 ID 分叉。
- **构建上下文变化需要刷新基线。** 修改 variant、source set、资源生成逻辑或资源混淆配置后，工程模型变化应先完成 Gradle Sync，再为目标变体执行完整 Gradle 构建，刷新 APK 和资源表基线。
- **Compose Multiplatform 资源不走 aapt2 inclink。** 它们通过独立的资源生成和 asset / classpath resource overlay 链路处理；删除 Compose 资源同样需要完整 Gradle 构建。

## 相关页面

- [增量编译总览](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest 编译](./manifest.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
- [AabResGuard](../../capabilities/compile/aab-resguard.md)
- [KMP / Compose Multiplatform](../../capabilities/compile/kmp-compose-multiplatform.md)
- [编译问题排查](../../troubleshooting/compile.md)
